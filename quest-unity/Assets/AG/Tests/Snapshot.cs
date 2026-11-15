using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using AG.Guide;
using AG.UI;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

namespace AG.Tests
{
    /// <summary>
    /// Editor play-mode capture of the guide without a headset (judge evidence for R5/R12):
    ///   Unity -batchmode -projectPath quest-unity -executeMethod AG.Tests.Snapshot.Run -logFile -
    ///   (no -nographics, no -quit; env AG_SNAP_OUT = output folder, AG_SNAP_STEPS = "2:1,2:3,3:0")
    /// Loads the bundled MALM job (local:malm-drawer), shows each step/action with the real GhostRenderer and
    /// StepPanel over a grey "room" with a table, renders a 1600 x 1000 PNG per shot, then exits.
    /// Untracked mode, so placed parts show as faint glass and the step pose is applied.
    /// </summary>
    public static class Snapshot
    {
        public static void Run()
        {
            EditorSettings.enterPlayModeOptionsEnabled = true;
            EditorSettings.enterPlayModeOptions = EnterPlayModeOptions.DisableDomainReload | EnterPlayModeOptions.DisableSceneReload;
            EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);
            EditorApplication.playModeStateChanged += OnPlay;
            EditorApplication.isPlaying = true;
        }

        static void OnPlay(PlayModeStateChange s)
        {
            if (s != PlayModeStateChange.EnteredPlayMode) return;
            EditorApplication.playModeStateChanged -= OnPlay;
            new GameObject("snapshot").AddComponent<SnapshotRunner>();
        }
    }

    public sealed class SnapshotRunner : MonoBehaviour
    {
        IEnumerator Start()
        {
            string outDir = Environment.GetEnvironmentVariable("AG_SNAP_OUT") ?? Path.GetFullPath("Builds/snapshots");
            string steps = Environment.GetEnvironmentVariable("AG_SNAP_STEPS") ?? "";
            string shots = Environment.GetEnvironmentVariable("AG_SNAP_SHOTS") ?? "wrong,lost,turn,complete,parts,pick";
            string lang = Environment.GetEnvironmentVariable("AG_SNAP_LANG") ?? "el";
            _lang = lang;
            Directory.CreateDirectory(outDir);
            try { Setup(lang); } catch (Exception e) { Debug.LogError("AG snapshot setup: " + e); EditorApplication.Exit(1); yield break; }
            var task = new ResourcesJobRepository().LoadGuideAsync("local:malm-drawer", _anchor, CancellationToken.None);
            while (!task.IsCompleted) yield return null;
            if (task.IsFaulted) { Debug.LogError("AG snapshot load: " + task.Exception); EditorApplication.Exit(1); yield break; }
            _guide = task.Result;
            _ghost.Bind(_guide, _anchor, lang);
            _panel.SetCameraPreview(FakeCamera(), 30, new Rect(0.30f, 0.22f, 0.42f, 0.46f));
            foreach (var spec in steps.Split(new[] { ',' }, StringSplitOptions.RemoveEmptyEntries))
            {
                var p = spec.Split(':');
                int step = int.Parse(p[0]), action = p.Length > 1 ? int.Parse(p[1]) : 0;
                if (_guide.StepByNumber(step) == null) continue;
                ShowStep(step, action);
                yield return Settle(90, 1.0f);
                Shot(Path.Combine(outDir, $"unity-editor-step{step}-{action}.png"));
            }
            foreach (var shot in shots.Split(new[] { ',' }, StringSplitOptions.RemoveEmptyEntries))
            {
                _ghost.ShowPickTargets(null);
                _ghost.SetTracking(GhostTracking.Untracked);
                _panel.SetStatusPill(null);
                switch (shot.Trim())
                {
                    case "wrong":
                        // step 6: the right runner (DR) was put on the left side
                        ShowStep(6, 1, L("Wrong", "Λάθος") + " · " + L("That's the right runner. The left side takes the left one. Page 7.", "Αυτός είναι ο δεξής οδηγός. Αριστερά μπαίνει ο αριστερός. Σελίδα 7."), StatusKind.Error);
                        yield return Settle(60, 0.3f);
                        _ghost.ShowFeedback(GuideFeedback.Wrong, "drawer_runner_right");
                        yield return new WaitForSeconds(0.9f);
                        break;
                    case "lost":
                        _ghost.SetTracking(GhostTracking.Tracked);
                        ShowStep(2, 2);
                        yield return Settle(60, 0.5f);
                        _ghost.SetTracking(GhostTracking.Lost);
                        _panel.SetStatusPill(L("Lost the drawer · show it again", "Χάθηκε το συρτάρι · δείξ' το ξανά"), StatusKind.Unsure);
                        yield return new WaitForSeconds(0.8f);
                        break;
                    case "turn":
                        ShowStep(3, 2);
                        yield return Settle(30, 0.2f);
                        ShowStep(4, 0);
                        yield return Settle(90, 1.6f);   // the preview mid-rotation, the window re-placed after the turn
                        break;
                    case "complete":
                        ShowStep(7, 3);
                        yield return Settle(60, 0.4f);
                        _ghost.ShowComplete();
                        _panel.SetCardLabel(L("Done", "Τέλος"), StatusKind.Ok);
                        _panel.SetButtons(new[] { PanelButton.Next }, PanelButton.Next);
                        _panel.ShowMessage(L("Your drawer is done", "Το συρτάρι είναι έτοιμο"),
                            L($"{_guide.Steps.Count} steps · {_guide.ActionCount} moves · 18 min", $"{_guide.Steps.Count} βήματα · {_guide.ActionCount} κινήσεις · 18 λεπτά"));
                        _panel.SetCardLabel(L("Done", "Τέλος"), StatusKind.Ok);
                        for (int i = 0; i < 30; i++) { if (_ghost.TryGetWorldBounds(out var wb)) _panel.PlaceBeside(wb, _cam.transform.position); yield return null; }
                        yield return new WaitForSeconds(0.25f);
                        break;
                    case "parts":
                        ShowStep(1, 2);   // cam bolt 1 of 2: the parts card turntable shows the bolt
                        yield return Settle(90, 1.2f);
                        break;
                    case "pick":
                        ShowStep(2, 1);
                        yield return Settle(30, 0.2f);
                        // fake pile positions on the table, left of the drawer (Vision supplies these from /parts boxes)
                        _ghost.ShowPickTargets(new List<PickTarget>
                        {
                            new PickTarget { WorldPosition = new Vector3(-0.42f, 0.75f, 0.02f), FootprintM = new Vector2(0.10f, 0.16f), PartId = "drawer_side_left" },
                            new PickTarget { WorldPosition = new Vector3(-0.30f, 0.75f, 0.30f), FootprintM = new Vector2(0.05f, 0.05f), PartId = "101345" },
                        });
                        yield return new WaitForSeconds(1.0f);
                        break;
                    default: continue;
                }
                Shot(Path.Combine(outDir, $"unity-{shot.Trim()}.png"));
            }
            EditorApplication.Exit(0);
        }

        string _lang;
        AssemblyGuide _guide;
        string L(string en, string el) => _lang == "el" ? el : en;

        void ShowStep(int step, int action, string badge = null, StatusKind kind = StatusKind.None)
        {
            var guide = _guide;
            var s = guide.StepByNumber(step);
            action = Mathf.Clamp(action, 0, s.Actions.Count - 1);
            _ghost.ShowAction(step, action);
            var a = s.Actions[action];
            PartInfo pi = a.PartId != null && guide.Parts.TryGetValue(a.PartId, out var x) ? x : null;
            string name = pi?.Names.Get(_lang);
            string cap = name == null ? null : char.ToUpper(name[0]) + name.Substring(1);
            string count = a.CountTotal > 1 ? L($"{a.CountIndex} of {a.CountTotal}", $"{a.CountIndex} από {a.CountTotal}") : null;
            _panel.SetButtons(new[] { PanelButton.Back, PanelButton.Repeat, PanelButton.Mic, PanelButton.Next }, PanelButton.Next);
            _panel.Show(new StepView
            {
                Step = s.N, StepCount = guide.Steps.Count, StepIndex = guide.Steps.IndexOf(s), Action = action, ActionCount = s.Actions.Count,
                Title = a.Text.Get(_lang), Page = s.Page,
                Detail = L($"Move {action + 1} of {s.Actions.Count}", $"Κίνηση {action + 1} από {s.Actions.Count}"),
                Chips = cap == null ? null : new List<(string, string, bool)> { (cap + (count != null ? " " + count : ""), a.Code, true) },
                Badge = badge, BadgeKind = kind, BadgeWarning = kind == StatusKind.Error,
                PickName = cap, PickCode = pi != null && pi.IsHardware ? pi.Id : null, PickCount = count,
                PickModel = a.Node != null && guide.Nodes.TryGetValue(a.Node, out var pn) ? pn.Instance : null,
            });
        }

        IEnumerator Settle(int frames, float seconds)
        {
            for (int i = 0; i < frames; i++)
            {
                if (_ghost.TryGetWorldBounds(out var wb)) _panel.PlaceBeside(wb, _cam.transform.position);
                yield return null;
            }
            yield return new WaitForSeconds(seconds);
        }

        void Shot(string file)
        {
            Capture(file);
            Debug.Log("AG snapshot: " + file);
        }

        /// <summary>A stand-in camera frame (table, box, label) for the preview ornament; the app shows the PCA image.</summary>
        static Texture2D FakeCamera()
        {
            var t = new Texture2D(320, 240, TextureFormat.RGBA32, false);
            var px = new Color32[320 * 240];
            for (int y = 0; y < 240; y++)
                for (int x = 0; x < 320; x++)
                {
                    float n = Mathf.PerlinNoise(x * 0.02f, y * 0.25f) * 0.12f;
                    Color c = y < 150 ? new Color(0.42f + n, 0.31f + n, 0.22f + n) : new Color(0.62f, 0.60f, 0.57f);
                    if (x > 100 && x < 228 && y > 56 && y < 160) c = new Color(0.86f, 0.84f, 0.80f);          // drawer front
                    if (x > 150 && x < 178 && y > 100 && y < 112) c = new Color(0.1f, 0.1f, 0.1f);            // marker strip
                    px[y * 320 + x] = c;
                }
            t.SetPixels32(px);
            t.Apply();
            return t;
        }

        Transform _anchor;
        GhostRenderer _ghost;
        StepPanel _panel;
        Camera _cam;

        void Setup(string lang)
        {
            var theme = AssetDatabase.LoadAssetAtPath<UiTheme>("Assets/AG/UI/UiTheme.asset");
            var camGo = new GameObject("Main Camera") { tag = "MainCamera" };
            _cam = camGo.AddComponent<Camera>();
            _cam.clearFlags = CameraClearFlags.SolidColor;
            _cam.backgroundColor = new Color(0.55f, 0.53f, 0.50f);
            _cam.fieldOfView = 70;
            _cam.nearClipPlane = 0.03f;
            // a seated user looking down at a table 0.55 m ahead
            camGo.transform.position = new Vector3(-0.05f, 1.28f, -0.80f);
            camGo.transform.LookAt(new Vector3(0.36f, 0.92f, 0.05f));
            var table = GameObject.CreatePrimitive(PrimitiveType.Cube);
            table.transform.position = new Vector3(0, 0.75f - 0.02f, 0.1f);
            table.transform.localScale = new Vector3(1.4f, 0.04f, 0.8f);
            var tm = new Material(Shader.Find("Universal Render Pipeline/Unlit"));
            tm.SetColor("_BaseColor", new Color(0.36f, 0.27f, 0.20f));
            table.GetComponent<Renderer>().sharedMaterial = tm;
            _anchor = new GameObject("AssemblyAnchor").transform;
            _anchor.position = new Vector3(0, 0.75f, 0.05f);
            _anchor.rotation = Quaternion.Euler(0, 180, 0);
            var g = new GameObject("GhostRenderer");
            g.SetActive(false);
            _ghost = g.AddComponent<GhostRenderer>();
            _ghost.Font = theme != null ? theme.FontAsset : null;
            _ghost.Viewer = camGo.transform;
            g.SetActive(true);
            var pg = new GameObject("StepPanel");
            pg.SetActive(false);
            _panel = pg.AddComponent<StepPanel>();
            _panel.Theme = theme;
            _panel.Lang = lang;
            pg.SetActive(true);
        }

        void Capture(string file)
        {
            var rt = new RenderTexture(1600, 1000, 24) { antiAliasing = 4 };
            _cam.aspect = 1.6f;
            _cam.targetTexture = rt;
            _cam.Render();
            RenderTexture.active = rt;
            var tex = new Texture2D(1600, 1000, TextureFormat.RGB24, false);
            tex.ReadPixels(new Rect(0, 0, 1600, 1000), 0, 0);
            tex.Apply();
            File.WriteAllBytes(file, tex.EncodeToPNG());
            RenderTexture.active = null;
            _cam.targetTexture = null;
            Destroy(rt);
        }
    }
}
