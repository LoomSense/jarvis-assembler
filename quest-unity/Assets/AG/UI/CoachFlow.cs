using System;
using System.Collections.Generic;
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
    /// <see cref="ICoach"/>: look -> identify -> (catalog/open + build) -> load -> locate -> lock on -> guide, one
    /// compact card at a time with the XR_NATIVE_SPEC §5.4 copy (EL/EN), errors as one sentence + Retry. In the
    /// guide: one action at a time on the panel and the ghost, voice per action (next line prefetched), perception
    /// events mapped to correct / wrong / unsure (§4.7-4.9) with their sounds, Back / Next / Repeat / Where am I,
    /// completion (§4.11). Logs <c>coach</c> {stage}, <c>step</c> {step, action, by}, <c>error</c> {where, message}.
    /// </summary>
    public sealed class CoachFlow : ICoach
    {
        CoachContext _c;
        int _stepIndex;
        TaskCompletionSource<bool> _done, _retry;
        CancellationToken _ct;
        string _job;
        float _startedAt;
        readonly Dictionary<int, Texture> _crops = new Dictionary<int, Texture>();
        int _cardNo;
        CancellationTokenSource _attempt;

        /// <summary>The job being guided (null before identify).</summary>
        public string JobId => _job;
        public AssemblyGuide Guide => _c?.Guide;
        const int Cards = 5;

        public CoachStage Stage { get; private set; }
        public int Step => _c?.Guide != null && _stepIndex < _c.Guide.Steps.Count ? _c.Guide.Steps[_stepIndex].N : 0;
        public int Action { get; private set; }
        public event Action<CoachStage> StageChanged;

        string L(string en, string el) => _c.Lang == "el" ? el : en;
        StepPanel Panel => _c.Panel as StepPanel;

        void Card(CoachStage s, int card, string titleEn, string titleEl, string bodyEn = null, string bodyEl = null, float? progress = null, bool say = true, params PanelButton[] buttons)
        {
            Stage = s;
            _cardNo = card;
            AgLog.Ev("coach", new { stage = s.ToString(), card });
            Panel?.SetCardLabel(card > 0 ? L($"{card} of {Cards}", $"{card} από {Cards}") : "", StatusKind.Info);
            _c.Panel?.SetButtons(buttons, null);
            _c.Panel?.ShowMessage(L(titleEn, titleEl), bodyEn != null ? L(bodyEn, bodyEl) : null, progress);
            if (say) _ = _c.Voice?.SayAsync(L(titleEn, titleEl));
            StageChanged?.Invoke(s);
        }

        void Fail(string where, string message, string en, string el)
        {
            AgLog.Error("coach." + where, message);
            Stage = CoachStage.Failed;
            AgLog.Ev("coach", new { stage = "Failed", where });
            Panel?.SetCardLabel(L("Problem", "Πρόβλημα"), StatusKind.Error);
            _c.Panel?.SetButtons(new[] { PanelButton.Retry }, PanelButton.Retry);
            _c.Panel?.ShowMessage(L(en, el));
            Panel?.SetCardLabel(L("Problem", "Πρόβλημα"), StatusKind.Error);
            UiSounds.Play(_c.Sfx, Cue.Warning);
            _ = _c.Voice?.SayAsync(L(en, el), true);
            StageChanged?.Invoke(Stage);
        }

        public async Task RunAsync(CoachContext ctx, CancellationToken ct)
        {
            _c = ctx;
            _ct = ct;
            _startedAt = Time.realtimeSinceStartup;
            while (!ct.IsCancellationRequested)
            {
                _attempt?.Dispose();
                _attempt = CancellationTokenSource.CreateLinkedTokenSource(ct);
                try
                {
                    await RunOnce(_attempt.Token);
                    return;
                }
                catch (HandledFailure) { }
                catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
                catch (OperationCanceledException) { _retry = null; AgLog.Ev("coach", new { stage = "Cancelled" }); continue; }   // Cancel on a card: start over
                catch (HttpError e) when (e.Status == 0 || e.Status >= 500)
                {
                    Fail(Stage.ToString(), e.Message, "No answer from the server. Retry.", "Δεν απαντά ο server. Ξανά.");
                }
                catch (HttpError e) when (e.Status == 403)
                {
                    Fail(Stage.ToString(), e.Message, "The demo key was refused.", "Το κλειδί δεν έγινε δεκτό.");
                }
                catch (Exception e)
                {
                    Fail(Stage.ToString(), e.Message, "Something went wrong. Retry.", "Κάτι πήγε στραβά. Ξανά.");
                }
                _retry = new TaskCompletionSource<bool>();
                using (ct.Register(() => _retry.TrySetCanceled())) { try { await _retry.Task; } catch (OperationCanceledException) { return; } }
                Unsubscribe();
            }
        }

        async Task RunOnce(CancellationToken ct)
        {
            string job = _c.JobId;
            if (string.IsNullOrEmpty(job))
            {
                Card(CoachStage.Look, 1, "Look at the box or the furniture", "Κοίτα το κουτί ή το έπιπλο",
                    "Keep it about a metre away.", "Κράτα το περίπου ένα μέτρο μακριά.");
                var jpeg = await NextJpeg(ct);
                Card(CoachStage.Identify, 2, "Identifying…", "Αναγνωρίζω…", "Reading the label.", "Διαβάζω την ετικέτα.", null, false, PanelButton.Cancel);
                var id = await _c.Api.IdentifyAsync(jpeg, _c.Lang, ct);
                job = id.Match?.JobId;
                bool ready = id.Match?.Ready ?? false;
                string product = id.Match?.Product ?? id.Seen?.Name;
                if (job == null && string.IsNullOrEmpty(id.Query))
                {
                    Fail("identify", "no match", "I can't tell which product this is. Show the box label.", "Δεν καταλαβαίνω ποιο προϊόν είναι. Δείξε την ετικέτα του κουτιού.");
                    throw new HandledFailure();
                }
                if (!string.IsNullOrEmpty(product))
                {
                    Card(CoachStage.Identify, 2, $"Found: {product}", $"Βρήκα: {product}", id.Message, id.Message);
                    await Task.Delay(2000, ct);
                }
                if (job == null || !ready)
                {
                    if (job == null) { var open = await _c.Api.CatalogOpenAsync(id.Query, ct); job = open.JobId; ready = open.Ready; }
                    while (!ready)
                    {
                        var st = await _c.Api.JobStatusAsync(job, ct);
                        var (en, el, n) = StageText(st.Stage);
                        Card(CoachStage.BuildGuide, 3, "Making the steps from the guide", "Φτιάχνω τα βήματα από τον οδηγό",
                            $"{en} · {n} of 4", $"{el} · {n} από 4", st.Progress, _cardNo != 3, PanelButton.Cancel);
                        if (st.Failed) { Fail("build", st.Error?.ToString() ?? "error", "The guide could not be built.", "Ο οδηγός δεν φτιάχτηκε."); throw new HandledFailure(); }
                        ready = st.Done;
                        if (!ready) await Task.Delay(2000, ct);
                    }
                }
            }
            _job = job;
            Card(CoachStage.BuildGuide, 3, "Loading the guide…", "Φορτώνω τον οδηγό…", null, null, null, false);
            if (_c.Guide == null || _c.Guide.JobId != job)
            {
                _c.Guide = await _c.Jobs.LoadGuideAsync(job, _c.GuideParent, ct);
                _c.Ghost?.Bind(_c.Guide, _c.AssemblyAnchor, _c.Lang);
            }

            Card(CoachStage.Locate, 4, "Finding your step…", "Βρίσκω σε ποιο βήμα είσαι…");
            int startStep = _c.Guide.Steps.Count > 0 ? _c.Guide.Steps[0].N : 1;
            try
            {
                var loc = await _c.Api.LocateAsync(job, await NextJpeg(ct, 50), _c.Lang, ct);
                if (_c.Guide.StepByNumber(loc.NextStep) != null) startStep = loc.NextStep;
                Card(CoachStage.Locate, 4, $"You're at step {startStep} of {_c.Guide.Steps.Count}.", $"Είσαι στο βήμα {startStep} από {_c.Guide.Steps.Count}.", loc.Message, loc.Message);
                await Task.Delay(1200, ct);
            }
            catch (OperationCanceledException) { throw; }
            catch (Exception e) { AgLog.Error("coach.locate", e.Message); }
            _stepIndex = Math.Max(0, _c.Guide.Steps.FindIndex(s => s.N == startStep));
            Action = 0;

            Card(CoachStage.Register, 5, "Hold still for a moment", "Μείνε ακίνητος για λίγο",
                "Look at the marker on the drawer front.", "Κοίτα το σημάδι στην πρόσοψη.");
            float t0 = Time.realtimeSinceStartup;
            while (_c.Tracker != null && !_c.Tracker.Latest.Tracked && Time.realtimeSinceStartup - t0 < _c.RegisterTimeoutS) await Task.Delay(100, ct);
            if (_c.Tracker != null && _c.Tracker.Latest.Tracked)
            {
                Card(CoachStage.Register, 5, "Locked on.", "Κλείδωσε.", null, null, null, false);
                UiSounds.Play(_c.Sfx, Cue.Success);
                await Task.Delay(700, ct);
            }

            Stage = CoachStage.Guide;
            AgLog.Ev("coach", new { stage = "Guide" });
            StageChanged?.Invoke(Stage);
            if (_c.Socket != null)
            {
                try
                {
                    await _c.Socket.ConnectAsync(job);
                    _c.Socket.EventReceived += OnEvent;
                    _c.Socket.ConnectionChanged += OnConnection;
                    _c.Socket.Start(Step, 0);
                }
                catch (Exception e) { AgLog.Error("coach.socket", e.Message); }
            }
            ShowCurrent("auto", true);
            _done = new TaskCompletionSource<bool>();
            using (ct.Register(() => _done.TrySetCanceled())) await _done.Task;
        }

        static (string en, string el, int n) StageText(string stage) => stage switch
        {
            "ingest" => ("Reading the guide", "Διαβάζω τον οδηγό", 1),
            "extract_plan" or "await_approval" => ("Finding the steps", "Βρίσκω τα βήματα", 2),
            "parts" or "place" => ("Shaping the parts", "Φτιάχνω τα κομμάτια", 3),
            _ => ("Preparing the 3D guide", "Ετοιμάζω τον τρισδιάστατο οδηγό", 4),
        };

        void Unsubscribe()
        {
            if (_c?.Socket != null) { _c.Socket.EventReceived -= OnEvent; _c.Socket.ConnectionChanged -= OnConnection; }
        }

        async Task<byte[]> NextJpeg(CancellationToken ct, int tries = 600)
        {
            for (int i = 0; i < tries; i++)
            {
                if (_c.Camera != null && _c.Camera.TryGetLatest(out var f))
                {
                    var jpeg = f.Jpeg ?? FrameJpeg.Encode(f);
                    if (jpeg != null) return jpeg;
                }
                await Task.Delay(100, ct);
            }
            throw new TimeoutException("no camera frame");
        }

        // ------------------------------------------------------------------ perception

        void OnConnection(bool open)
        {
            if (Stage != CoachStage.Guide) return;
            _c.Panel?.SetStatusPill(open ? null : L("Offline · reconnecting", "Χωρίς σύνδεση · ξανασυνδέομαι"), StatusKind.Unsure);
        }

        void OnEvent(PerceptionEvent ev)
        {
            if (Stage != CoachStage.Guide && Stage != CoachStage.Done) return;
            string say = ev.SayText?.Get(_c.Lang);
            switch (ev.Event)
            {
                case "step_complete":
                    _c.Ghost?.ShowFeedback(GuideFeedback.Correct);
                    UiSounds.Play(_c.Sfx, Cue.Step);
                    Badge(say ?? L("Step done", "Το βήμα έγινε"), StatusKind.Ok);
                    if (say != null && ev.Speak) _ = _c.Voice?.SayAsync(say);
                    AdvanceLater(650, wholeStep: true);
                    return;
                case "right_part":
                case "right_hardware":
                    UiSounds.Play(_c.Sfx, Cue.Success, 0.7f);
                    Badge(L("Right part", "Σωστό κομμάτι"), StatusKind.Ok);
                    break;
                case "wrong_part":
                case "wrong_hardware":
                case "out_of_order":
                case "flip_needed":
                    _c.Ghost?.ShowFeedback(GuideFeedback.Wrong, ev.ObservedId);
                    UiSounds.Play(_c.Sfx, Cue.Warning);
                    Badge(say ?? L("Not this one", "Όχι αυτό"), StatusKind.Error, L("Wrong", "Λάθος"));
                    break;
                case "unsure_show_part":
                    _c.Ghost?.ShowFeedback(GuideFeedback.Unsure);
                    UiSounds.Play(_c.Sfx, Cue.Unsure);
                    Badge(say ?? L("Not sure", "Δεν είμαι σίγουρος"), StatusKind.Unsure, L("Not sure", "Δεν είμαι σίγουρος"));
                    break;
                case "vision_paused":
                    _c.Panel?.SetStatusPill(L("Vision paused", "Η όραση σε παύση"), StatusKind.Unsure);
                    break;
                case "vision_resumed":
                    _c.Panel?.SetStatusPill(null);
                    break;
                case "more_light":
                case "realign_camera":
                    _c.Panel?.SetStatusPill(say ?? ev.Event, StatusKind.Unsure);
                    break;
            }
            if (ev.Speak && say != null) _ = _c.Voice?.SayAsync(say, ev.Event.StartsWith("wrong") || ev.Event == "out_of_order" || ev.Event == "flip_needed");
        }

        StepView _view;
        void Badge(string caption, StatusKind kind, string word = null)
        {
            if (_c.Guide == null) return;
            _view.Badge = word != null ? $"{word} · {caption}" : caption;
            _view.BadgeKind = kind;
            _view.BadgeWarning = kind == StatusKind.Error || kind == StatusKind.Unsure;
            _c.Panel?.Show(_view);
        }

        async void AdvanceLater(int ms, bool wholeStep)
        {
            try { await Task.Delay(ms, _ct); } catch (OperationCanceledException) { return; }
            Advance(+1, "auto", wholeStep);
        }

        // ------------------------------------------------------------------ guide

        void ShowCurrent(string by, bool stepStart = false, bool speak = true)
        {
            var g = _c.Guide;
            if (g == null || g.Steps.Count == 0) return;
            var s = g.Steps[_stepIndex];
            Action = Mathf.Clamp(Action, 0, Math.Max(0, s.Actions.Count - 1));
            var a = s.Actions.Count > 0 ? s.Actions[Action] : null;
            _c.Ghost?.ShowAction(s.N, Action);
            UiSounds.Play(_c.Sfx, Cue.Place, 0.6f);
            _view = new StepView
            {
                Step = s.N, StepCount = g.Steps.Count, StepIndex = _stepIndex, Action = Action, ActionCount = s.Actions.Count,
                Title = a?.Text?.Get(_c.Lang) ?? s.Title,
                Detail = (s.Actions.Count > 1 ? L($"Move {Action + 1} of {s.Actions.Count}", $"Κίνηση {Action + 1} από {s.Actions.Count}") : null),
                Page = s.Page,
                Chips = Chips(g, s, a),
                Crop = _crops.TryGetValue(s.N, out var crop) ? crop : null,
                IsLast = _stepIndex == g.Steps.Count - 1 && Action >= s.Actions.Count - 1,
            };
            if (a?.PartId != null && g.Parts.TryGetValue(a.PartId, out var pick))
            {
                _view.PickName = Cap(pick.Names?.Get(_c.Lang) ?? pick.Id);
                _view.PickCode = pick.IsHardware ? pick.Id : null;
                _view.PickCount = a.CountTotal > 1 ? L($"{a.CountIndex} of {a.CountTotal}", $"{a.CountIndex} από {a.CountTotal}") : null;
                _view.PickModel = a.Node != null && g.Nodes.TryGetValue(a.Node, out var pn) ? pn.Instance : null;
            }
            _c.Panel?.SetStatusPill(null);
            _c.Panel?.Show(_view);
            if (!_crops.ContainsKey(s.N) && _job != null && !_job.StartsWith("local:")) LoadCrop(s);
            string line = a?.Text?.Get(_c.Lang) ?? s.Title;
            if (stepStart || Action == 0)
                line = (s.Page.HasValue ? L($"Step {s.N}, page {s.Page}. ", $"Βήμα {s.N}, σελίδα {s.Page}. ") : L($"Step {s.N}. ", $"Βήμα {s.N}. ")) + line;
            if (speak) _ = _c.Voice?.SayAsync(line, by == "button");
            // prefetch the next action's line
            var next = Action + 1 < s.Actions.Count ? s.Actions[Action + 1] : (_stepIndex + 1 < g.Steps.Count && g.Steps[_stepIndex + 1].Actions.Count > 0 ? g.Steps[_stepIndex + 1].Actions[0] : null);
            if (next?.Text != null)
            {
                var ns = Action + 1 < s.Actions.Count ? s : g.Steps[_stepIndex + 1];
                string nl = next.Text.Get(_c.Lang);
                if (ns != s) nl = (ns.Page.HasValue ? L($"Step {ns.N}, page {ns.Page}. ", $"Βήμα {ns.N}, σελίδα {ns.Page}. ") : L($"Step {ns.N}. ", $"Βήμα {ns.N}. ")) + nl;
                _c.Voice?.Prefetch(nl);
            }
            AgLog.Ev("step", new { step = s.N, action = Action, by });
        }

        List<(string, string, bool)> Chips(AssemblyGuide g, GuideStep s, GuideAction a)
        {
            var list = new List<(string, string, bool)>();
            if (a?.PartId != null && g.Parts.TryGetValue(a.PartId, out var p))
            {
                string name = Cap(p.Names?.Get(_c.Lang) ?? p.Id);
                string count = a.CountTotal > 1 ? L($" {a.CountIndex} of {a.CountTotal}", $" {a.CountIndex} από {a.CountTotal}") : "";
                list.Add((name + count, p.IsHardware ? p.Id : null, true));
            }
            foreach (var (code, qty) in s.Hardware)
            {
                if (a?.PartId == code) continue;
                string name = g.Parts.TryGetValue(code, out var hp) ? Cap(hp.Names?.Get(_c.Lang) ?? code) : code;
                list.Add(($"{name} ×{qty}", code, false));
            }
            return list;
        }

        static string Cap(string s) => string.IsNullOrEmpty(s) ? s : char.ToUpper(s[0]) + s.Substring(1);

        async void LoadCrop(GuideStep s)
        {
            _crops[s.N] = null;
            try
            {
                // the manifest's crop, else the server's conventional path /files/{job}/crops/step-NN.png
                string rel = !string.IsNullOrEmpty(s.CropPath) ? s.CropPath : $"crops/step-{s.N:00}.png";
                var tex = await _c.Jobs.FetchTextureAsync(_job, rel, _ct);
                _crops[s.N] = tex;
                if (Stage == CoachStage.Guide && Step == s.N) { _view.Crop = tex; _c.Panel?.Show(_view); }
            }
            catch (Exception e) { AgLog.Error("coach.crop", e.Message); }
        }

        void Advance(int dir, string by, bool wholeStep = false)
        {
            var g = _c?.Guide;
            if (g == null || Stage != CoachStage.Guide) return;
            var s = g.Steps[_stepIndex];
            bool lastAction = _stepIndex == g.Steps.Count - 1 && (wholeStep || Action >= s.Actions.Count - 1);
            if (dir > 0 && lastAction) { Complete(by); return; }
            int prevStep = _stepIndex;
            if (wholeStep) { _stepIndex = Mathf.Clamp(_stepIndex + dir, 0, g.Steps.Count - 1); Action = 0; }
            else if (dir > 0 && Action + 1 < s.Actions.Count) Action++;
            else if (dir < 0 && Action > 0) Action--;
            else
            {
                _stepIndex = Mathf.Clamp(_stepIndex + dir, 0, g.Steps.Count - 1);
                Action = dir > 0 ? 0 : Math.Max(0, g.Steps[_stepIndex].Actions.Count - 1);
            }
            if (_stepIndex != prevStep) _c.Socket?.SetStep(Step, Action); else _c.Socket?.SetAction(Action);
            ShowCurrent(by, _stepIndex != prevStep);
        }

        void Complete(string by)
        {
            Stage = CoachStage.Done;
            AgLog.Ev("coach", new { stage = "Done", by });
            _c.Ghost?.ShowComplete();
            UiSounds.Play(_c.Sfx, Cue.Complete);
            int mins = Mathf.Max(1, Mathf.RoundToInt((Time.realtimeSinceStartup - _startedAt) / 60f));
            var g = _c.Guide;
            Panel?.SetCardLabel(L("Done", "Τέλος"), StatusKind.Ok);
            _c.Panel?.SetButtons(new[] { PanelButton.Next }, PanelButton.Next);
            _c.Panel?.ShowMessage(L("Your drawer is done", "Το συρτάρι είναι έτοιμο"),
                L($"{g.Steps.Count} steps · {g.ActionCount} moves · {mins} min", $"{g.Steps.Count} βήματα · {g.ActionCount} κινήσεις · {mins} λεπτά"));
            Panel?.SetCardLabel(L("Done", "Τέλος"), StatusKind.Ok);
            _ = _c.Voice?.SayAsync(L("Your drawer is done.", "Το συρτάρι είναι έτοιμο."));
            _c.Socket?.Stop();
            StageChanged?.Invoke(Stage);
        }

        public void Next()
        {
            if (Stage == CoachStage.Done) { _done?.TrySetResult(true); return; }
            Advance(+1, "button");
        }
        public void Back() => Advance(-1, "button");
        public void Repeat() { if (Stage == CoachStage.Guide) ShowCurrent("button"); }
        public void Rescan() { _c?.Tracker?.Reset(); }
        public void Retry() => _retry?.TrySetResult(true);

        public async void WhereAmI()
        {
            if (_c?.Guide == null || Stage != CoachStage.Guide || _job == null) return;
            Badge(L("Checking where you are…", "Ελέγχω πού είσαι…"), StatusKind.Info);
            try
            {
                var loc = await _c.Api.LocateAsync(_job, await NextJpeg(_ct, 30), _c.Lang, _ct);
                int idx = _c.Guide.Steps.FindIndex(s => s.N == loc.NextStep);
                if (idx >= 0 && idx != _stepIndex)
                {
                    _stepIndex = idx;
                    Action = Mathf.Clamp(loc.Action, 0, Math.Max(0, _c.Guide.Steps[idx].Actions.Count - 1));
                    _c.Socket?.SetStep(Step, Action);
                }
                ShowCurrent("where_am_i", true);
                if (!string.IsNullOrEmpty(loc.Message)) Badge(loc.Message, StatusKind.Info);
            }
            catch (OperationCanceledException) { }
            catch (Exception e)
            {
                AgLog.Error("coach.where_am_i", e.Message);
                Badge(L("No answer from the server.", "Δεν απαντά ο server."), StatusKind.Error, L("Error", "Σφάλμα"));
            }
        }

        /// <summary>Cancel on the identify / build cards: aborts the request in flight (UnityWebRequest.Abort) and starts over.</summary>
        public void Cancel()
        {
            if (Stage == CoachStage.Look || Stage == CoachStage.Identify || Stage == CoachStage.BuildGuide || Stage == CoachStage.Locate)
            {
                AgLog.Ev("coach", new { stage = "Cancel", from = Stage.ToString() });
                _attempt?.Cancel();
            }
            else _retry?.TrySetResult(true);
        }

        /// <summary>Jump to a step (index into Guide.Steps) and action; <paramref name="by"/> goes to the AGQ <c>step</c> event.</summary>
        public void GoTo(int stepIndex, int action, string by, bool speak = true)
        {
            var g = _c?.Guide;
            if (g == null || Stage != CoachStage.Guide || stepIndex < 0 || stepIndex >= g.Steps.Count) return;
            int prev = _stepIndex;
            _stepIndex = stepIndex;
            Action = Mathf.Clamp(action, 0, Math.Max(0, g.Steps[stepIndex].Actions.Count - 1));
            if (_stepIndex != prev) _c.Socket?.SetStep(Step, Action); else _c.Socket?.SetAction(Action);
            ShowCurrent(by, _stepIndex != prev, speak);
        }

        /// <summary>
        /// A reply of POST /api/voice: move to its target (logged <c>step</c> by "voice"), show the transcript and the
        /// answer as the caption, and return the line to speak (the server's own answer), or null.
        /// </summary>
        public string ApplyVoice(VoiceReply r)
        {
            var plan = VoiceReplyMap.Map(r, _c?.Guide);
            if (Stage != CoachStage.Guide) return plan.Say;
            if (plan.StepIndex >= 0) GoTo(plan.StepIndex, plan.Action, "voice", speak: false);
            if (plan.Say != null || !string.IsNullOrEmpty(r?.Transcript))
            {
                string heard = string.IsNullOrEmpty(r?.Transcript) ? "" : $"«{r.Transcript.Trim()}» ";
                _view.Detail = heard + (plan.Say ?? "");
                _c.Panel?.Show(_view);
            }
            if (plan.Highlight != null && _c.Guide.Parts.TryGetValue(plan.Highlight, out var hp))
                Badge(Cap(hp.Names?.Get(_c.Lang) ?? hp.Id) + (hp.IsHardware ? " · " + hp.Id : ""), StatusKind.Info);
            return plan.Intent == VoiceIntent.None ? null : plan.Say;
        }

        sealed class HandledFailure : Exception { }
    }
}
