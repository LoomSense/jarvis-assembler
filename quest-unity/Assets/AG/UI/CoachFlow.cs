using System;
using System.Threading;
using System.Threading.Tasks;
using AG.Core;
using AG.Guide;
using AG.Net;
using AG.Vision;
using UnityEngine;

namespace AG.UI
{
    /// <summary>
    /// First version of <see cref="ICoach"/>: the happy path with plain English/Greek lines. UI agent: copy from
    /// docs/design/XR_NATIVE_SPEC.md, retries, the Register stage UX (marker found / touch two corners).
    /// </summary>
    public sealed class CoachFlow : ICoach
    {
        CoachContext _c;
        int _stepIndex;
        TaskCompletionSource<bool> _rescan;

        public CoachStage Stage { get; private set; }
        public int Step => _c?.Guide != null && _stepIndex < _c.Guide.Steps.Count ? _c.Guide.Steps[_stepIndex].N : 0;
        public int Action { get; private set; }
        public event Action<CoachStage> StageChanged;

        string L(string en, string el) => _c.Lang == "el" ? el : en;

        void Enter(CoachStage s, string en, string el, float? progress = null)
        {
            Stage = s;
            AgLog.Ev("coach", new { stage = s.ToString() });
            _c.Panel?.ShowMessage(L(en, el), null, progress);
            StageChanged?.Invoke(s);
        }

        public async Task RunAsync(CoachContext ctx, CancellationToken ct)
        {
            _c = ctx;
            try
            {
                string job = ctx.JobId;
                if (string.IsNullOrEmpty(job))
                {
                    Enter(CoachStage.Look, "Look at the product or its box.", "Κοίτα το προϊόν ή το κουτί του.");
                    var jpeg = await NextJpeg(ct);
                    Enter(CoachStage.Identify, "Finding the product…", "Βρίσκω το προϊόν…");
                    var id = await ctx.Api.IdentifyAsync(jpeg, ctx.Lang, ct);
                    _c.Panel?.ShowMessage(id.Message);
                    job = id.Match?.JobId;
                    bool ready = id.Match?.Ready ?? false;
                    if (job == null || !ready)
                    {
                        if (job == null && string.IsNullOrEmpty(id.Query)) { Enter(CoachStage.Failed, "Product not found.", "Δεν βρέθηκε το προϊόν."); return; }
                        if (job == null) { var open = await ctx.Api.CatalogOpenAsync(id.Query, ct); job = open.JobId; ready = open.Ready; }
                        while (!ready)
                        {
                            var st = await ctx.Api.JobStatusAsync(job, ct);
                            Enter(CoachStage.BuildGuide, "Building the guide…", "Φτιάχνω τον οδηγό…", st.Progress);
                            if (st.Failed) { Enter(CoachStage.Failed, "The guide could not be built.", "Ο οδηγός δεν φτιάχτηκε."); return; }
                            ready = st.Done;
                            if (!ready) await Task.Delay(2000, ct);
                        }
                    }
                }
                Enter(CoachStage.BuildGuide, "Loading the guide…", "Φορτώνω τον οδηγό…");
                ctx.Guide = await ctx.Jobs.LoadGuideAsync(job, ctx.GuideParent, ct);
                ctx.Ghost?.Bind(ctx.Guide, ctx.AssemblyAnchor, ctx.Lang);

                Enter(CoachStage.Locate, "Checking how far you are…", "Ελέγχω πού έχεις φτάσει…");
                int startStep = ctx.Guide.Steps.Count > 0 ? ctx.Guide.Steps[0].N : 1;
                try
                {
                    var loc = await ctx.Api.LocateAsync(job, await NextJpeg(ct), ctx.Lang, ct);
                    startStep = loc.NextStep;
                    _c.Panel?.ShowMessage(loc.Message);
                }
                catch (Exception e) { AgLog.Error("coach.locate", e.Message); }
                _stepIndex = Math.Max(0, ctx.Guide.Steps.FindIndex(s => s.N == startStep));

                Enter(CoachStage.Register, "Point at the marker, or touch the two front corners.", "Κοίτα το σημάδι ή άγγιξε τις δύο μπροστινές γωνίες.");
                for (int i = 0; i < 100 && !(ctx.Tracker?.Latest.Tracked ?? true); i++) await Task.Delay(100, ct);

                Enter(CoachStage.Guide, "", "");
                if (ctx.Socket != null)
                {
                    await ctx.Socket.ConnectAsync(job);
                    ctx.Socket.EventReceived += OnEvent;
                    ctx.Socket.Start(Step, 0);
                }
                ShowCurrent("auto");
                _rescan = new TaskCompletionSource<bool>();
                using (ct.Register(() => _rescan.TrySetCanceled())) await _rescan.Task;
            }
            catch (OperationCanceledException) { }
            catch (Exception e)
            {
                AgLog.Error("coach." + Stage, e.Message);
                Enter(CoachStage.Failed, "Something went wrong. Pinch to retry.", "Κάτι πήγε στραβά. Τσίμπησε για νέα προσπάθεια.");
            }
        }

        async Task<byte[]> NextJpeg(CancellationToken ct)
        {
            for (int i = 0; i < 600; i++)
            {
                if (_c.Camera != null && _c.Camera.TryGetLatest(out var f))
                {
                    var jpeg = FrameJpeg.Encode(f);
                    if (jpeg != null) return jpeg;
                }
                await Task.Delay(100, ct);
            }
            throw new TimeoutException("no camera frame");
        }

        void OnEvent(PerceptionEvent ev)
        {
            if (ev.Speak && ev.SayText != null) _ = _c.Voice?.SayAsync(ev.SayText.Get(_c.Lang), ev.Event != "step_complete");
            if (ev.Event == "step_complete") Advance(+1, "auto", wholeStep: true);
        }

        void ShowCurrent(string by)
        {
            var g = _c.Guide;
            if (g == null || g.Steps.Count == 0) return;
            var s = g.Steps[_stepIndex];
            var a = s.Actions.Count > 0 ? s.Actions[Mathf.Clamp(Action, 0, s.Actions.Count - 1)] : null;
            _c.Ghost?.ShowAction(s.N, Action);
            _c.Panel?.Show(new StepView
            {
                Step = s.N, StepCount = g.Steps.Count, Action = Action, ActionCount = s.Actions.Count,
                Title = a?.Text?.Get(_c.Lang) ?? s.Title,
                Detail = s.Page.HasValue ? L($"Step {s.N} · page {s.Page}", $"Βήμα {s.N} · σελίδα {s.Page}") : null,
            });
            if (a?.Text != null) _ = _c.Voice?.SayAsync(a.Text.Get(_c.Lang));
            AgLog.Ev("step", new { step = s.N, action = Action, by });
        }

        void Advance(int dir, string by, bool wholeStep = false)
        {
            var g = _c?.Guide;
            if (g == null || Stage != CoachStage.Guide) return;
            var s = g.Steps[_stepIndex];
            int prevStep = _stepIndex;
            if (wholeStep) { _stepIndex = Mathf.Clamp(_stepIndex + dir, 0, g.Steps.Count - 1); Action = 0; }
            else if (dir > 0 && Action + 1 < s.Actions.Count) Action++;
            else if (dir < 0 && Action > 0) Action--;
            else { _stepIndex = Mathf.Clamp(_stepIndex + dir, 0, g.Steps.Count - 1); Action = dir > 0 ? 0 : Math.Max(0, g.Steps[_stepIndex].Actions.Count - 1); }
            if (_stepIndex != prevStep) _c.Socket?.SetStep(Step, Action); else _c.Socket?.SetAction(Action);
            ShowCurrent(by);
        }

        public void Next() => Advance(+1, "button");
        public void Back() => Advance(-1, "button");
        public void Repeat() => ShowCurrent("button");
        public void Rescan() { _c?.Tracker?.Reset(); }
    }
}
