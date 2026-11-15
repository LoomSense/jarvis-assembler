using System;
using System.Collections;
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
            string steps = Environment.GetEnvironmentVariable("AG_SNAP_STEPS") ?? "2:2";
            string lang = Environment.GetEnvironmentVariable("AG_SNAP_LANG") ?? "el";
            Directory.CreateDirectory(outDir);
            int code = 0;
            try { Setup(lang); } catch (Exception e) { Debug.LogError("AG snapshot setup: " + e); EditorApplication.Exit(1); yield break; }
            var task = new ResourcesJobRepository().LoadGuideAsync("local:malm-drawer", _anchor, CancellationToken.None);
            while (!task.IsCompleted) yield return null;
            if (task.IsFaulted) { Debug.LogError("AG snapshot load: " + task.Exception); EditorApplication.Exit(1); yield break; }
            var guide = task.Result;
            _ghost.Bind(guide, _anchor, lang);
            foreach (var spec in steps.Split(','))
            {
                var p = spec.Split(':');
                int step = int.Parse(p[0]), action = p.Length > 1 ? int.Parse(p[1]) : 0;
                var s = guide.StepByNumber(step);
                if (s == null) continue;
                _ghost.ShowAction(step, action);
                var a = s.Actions[Mathf.Clamp(action, 0, s.Actions.Count - 1)];
                string name = a.PartId != null && guide.Parts.TryGetValue(a.PartId, out var pi) ? pi.Names.Get(lang) : null;
                _panel.Show(new StepView
                {
                    Step = s.N, StepCount = guide.Steps.Count, StepIndex = guide.Steps.IndexOf(s), Action = action, ActionCount = s.Actions.Count,
                    Title = a.Text.Get(lang), Page = s.Page,
                    Detail = lang == "el" ? $"Κίνηση {action + 1} από {s.Actions.Count}" : $"Move {action + 1} of {s.Actions.Count}",
                    Chips = name == null ? null : new System.Collections.Generic.List<(string, string, bool)>
                        { (char.ToUpper(name[0]) + name.Substring(1) + (a.CountTotal > 1 ? (lang == "el" ? $" {a.CountIndex} από {a.CountTotal}" : $" {a.CountIndex} of {a.CountTotal}") : ""), a.Code, true) },
                });
                for (int i = 0; i < 90; i++)
                {
                    if (_ghost.TryGetWorldBounds(out var wb)) _panel.PlaceBeside(wb, _cam.transform.position);
                    yield return null;
                }
                yield return new WaitForSeconds(1.0f);
                var file = Path.Combine(outDir, $"unity-editor-step{step}-{action}.png");
                Capture(file);
                Debug.Log("AG snapshot: " + file);
            }
            EditorApplication.Exit(code);
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
            _cam.fieldOfView = 52;
            _cam.nearClipPlane = 0.03f;
            // a seated user looking down at a table 0.55 m ahead
            camGo.transform.position = new Vector3(-0.05f, 1.28f, -0.80f);
            camGo.transform.LookAt(new Vector3(0.20f, 0.88f, 0.05f));
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
