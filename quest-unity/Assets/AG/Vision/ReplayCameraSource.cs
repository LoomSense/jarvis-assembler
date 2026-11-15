using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using AG.Core;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace AG.Vision
{
    /// <summary>
    /// Plays a recorded camera from a folder (looping), so tracking and perception can be tested without the object.
    /// Shared convention with the Android client and tools/quest/ (docs/QUEST_TESTING.md "Replay datasets"):
    /// <code>
    /// replay/
    ///   000000.jpg 000001.jpg ...   frames, sorted by file name (.jpg or .jpeg)
    ///   intrinsics.json             {"fx","fy","cx","cy","width","height","fps"}  pixels of these JPEGs, OpenCV
    ///                               convention (pixel centres at integers, top-left origin); fps default 10
    ///   poses.jsonl                 optional, one line per frame. Canonical (tools/quest/replay):
    ///                               {"frame":0,"t":0.0,"file":"000000.jpg","cam_p":[x,y,z],"cam_q":[x,y,z,w],"cam_q_xr":[..]}
    ///                               world right-handed Y up (OpenXR), cam_q with OpenCV camera axes (x right, y down,
    ///                               z forward), cam_q_xr the same camera with OpenXR axes; both converted to Unity
    ///                               (mirror Z). Legacy phase-1 lines {"pos","rot","frame":"unity"|"openxr"} still work.
    ///                               Matched by "file", else by line order.
    /// </code>
    /// Folder: <c>LaunchParams.ReplayFolder</c> = <c>replay_dir</c> or <c>Application.persistentDataPath/replay</c>
    /// (device: /sdcard/Android/data/com.agj.unity/files/replay/). Main thread. Frames carry <see cref="CameraFrame.ReplayFrame"/>.
    /// Tests: <see cref="Loop"/> = false and <see cref="Step"/> to read frame by frame with dataset time.
    /// </summary>
    public sealed class ReplayCameraSource : ICameraSource
    {
        public float Fps { get; private set; } = 10f;
        public bool Loop = true;
        public int FrameCount => _files.Length;

        readonly string _dir;
        string[] _files = Array.Empty<string>();
        readonly Dictionary<string, Pose> _posesByFile = new Dictionary<string, Pose>();
        readonly List<Pose> _posesByOrder = new List<Pose>();
        readonly Dictionary<string, (int frame, double t)> _metaByFile = new Dictionary<string, (int, double)>();
        Color32[] _pixels;
        Texture2D _tex;
        CameraFrame _latest;
        bool _hasLatest;
        double _nextAt;
        int _cursor;
        long _index;

        public ReplayCameraSource(string dir) { _dir = dir; }

        public string Name => "replay";
        public bool IsRunning { get; private set; }
        public CameraIntrinsics Intrinsics { get; private set; }
        public bool CpuPixels { get; set; }
        public event Action<CameraFrame> FrameArrived;

        public Task<bool> StartAsync()
        {
            try
            {
                if (!Directory.Exists(_dir)) throw new DirectoryNotFoundException(_dir);
                _files = Directory.GetFiles(_dir)
                    .Where(f => f.EndsWith(".jpg", StringComparison.OrdinalIgnoreCase) || f.EndsWith(".jpeg", StringComparison.OrdinalIgnoreCase))
                    .OrderBy(f => Path.GetFileName(f), StringComparer.Ordinal).ToArray();
                if (_files.Length == 0) throw new FileNotFoundException("no .jpg frames in " + _dir);
                var intrText = File.ReadAllText(Path.Combine(_dir, "intrinsics.json"));
                Intrinsics = JsonConvert.DeserializeObject<CameraIntrinsics>(intrText);
                Fps = JObject.Parse(intrText).Value<float?>("fps") ?? 10f;
                ReadPoses(Path.Combine(_dir, "poses.jsonl"));
                _tex = new Texture2D(2, 2, TextureFormat.RGBA32, false);
                IsRunning = true;
                _nextAt = 0;
                AgLog.Ev("camera", new { source = Name, dir = _dir, frames = _files.Length, poses = _posesByOrder.Count, fps = Fps,
                    Intrinsics.width, Intrinsics.height, Intrinsics.fx, Intrinsics.fy, Intrinsics.cx, Intrinsics.cy });
            }
            catch (Exception e)
            {
                AgLog.Error("ReplayCameraSource.Start", e.Message);
                IsRunning = false;
            }
            return Task.FromResult(IsRunning);
        }

        public void Stop() { IsRunning = false; }

        public bool TryGetLatest(out CameraFrame frame) { frame = _latest; return _hasLatest; }

        public void Tick()
        {
            if (!IsRunning) return;
            double now = Time.realtimeSinceStartupAsDouble;
            if (now < _nextAt) return;
            _nextAt = now + 1.0 / Mathf.Max(0.5f, Fps);
            Step(now);
        }

        /// <summary>
        /// Loads the next frame now. <paramref name="timeS"/> null = the dataset time (poses.jsonl "t", else index / fps).
        /// Returns false at the end when <see cref="Loop"/> is off.
        /// </summary>
        public bool Step(double? timeS = null)
        {
            if (!IsRunning) return false;
            if (_cursor >= _files.Length) { if (!Loop) return false; _cursor = 0; }
            var path = _files[_cursor];
            var jpeg = File.ReadAllBytes(path);
            _tex.LoadImage(jpeg, false);
            var name = Path.GetFileName(path);
            int frameNo = _metaByFile.TryGetValue(name, out var meta) ? meta.frame : _cursor;
            double t = timeS ?? (_metaByFile.ContainsKey(name) ? meta.t : _cursor / (double)Fps);
            if (CpuPixels)
            {
                int n = _tex.width * _tex.height;
                if (_tex.format == TextureFormat.RGBA32)
                {
                    if (_pixels == null || _pixels.Length != n) _pixels = new Color32[n];
                    _tex.GetPixelData<Color32>(0).CopyTo(_pixels);
                }
                else _pixels = _tex.GetPixels32();   // LoadImage turns JPEGs into RGB24
            }
            var f = new CameraFrame
            {
                Index = _index++, TimeS = t, Width = _tex.width, Height = _tex.height, Texture = _tex, Jpeg = jpeg,
                Intrinsics = Intrinsics.width == _tex.width ? Intrinsics : Intrinsics.Scaled(_tex.width, _tex.height),
                Pixels = CpuPixels ? _pixels : null, ReplayFrame = frameNo,
            };
            if (_posesByFile.TryGetValue(name, out var p))
            {
                f.CameraPose = p;
                f.HasPose = true;
            }
            else if (_posesByFile.Count == 0 && _cursor < _posesByOrder.Count)
            {
                f.CameraPose = _posesByOrder[_cursor];
                f.HasPose = true;
            }
            _cursor++;
            if (Loop && _cursor >= _files.Length) _cursor = 0;
            _latest = f;
            _hasLatest = true;
            FrameArrived?.Invoke(f);
            return true;
        }

        void ReadPoses(string path)
        {
            _posesByFile.Clear();
            _posesByOrder.Clear();
            _metaByFile.Clear();
            if (!File.Exists(path)) return;
            foreach (var line in File.ReadAllLines(path))
            {
                if (string.IsNullOrWhiteSpace(line)) continue;
                var o = JObject.Parse(line);
                if (!TryParsePose(o, out var pose)) continue;
                _posesByOrder.Add(pose);
                var file = (string)o["file"];
                if (string.IsNullOrEmpty(file)) continue;
                _posesByFile[file] = pose;
                var fr = o["frame"];
                int frameNo = fr != null && fr.Type == JTokenType.Integer ? (int)fr : _posesByOrder.Count - 1;
                _metaByFile[file] = (frameNo, o.Value<double?>("t") ?? frameNo / (double)Fps);
            }
        }

        /// <summary>One poses.jsonl line -> camera pose in Unity world (+Z optical axis). See the class comment.</summary>
        public static bool TryParsePose(JObject o, out Pose pose)
        {
            pose = default;
            var camP = o["cam_p"]?.ToObject<float[]>();
            var camQxr = o["cam_q_xr"]?.ToObject<float[]>();
            var camQ = o["cam_q"]?.ToObject<float[]>();
            if (camP != null && camP.Length >= 3 && (camQxr?.Length >= 4 || camQ?.Length >= 4))
            {
                Quaternion qxr = camQxr != null && camQxr.Length >= 4
                    ? new Quaternion(camQxr[0], camQxr[1], camQxr[2], camQxr[3])
                    // OpenCV camera axes -> OpenXR camera axes: rotate 180 deg about the camera's x
                    : new Quaternion(camQ[0], camQ[1], camQ[2], camQ[3]) * new Quaternion(1, 0, 0, 0);
                pose = OpenXrToUnity(new Pose(new Vector3(camP[0], camP[1], camP[2]), qxr));
                return true;
            }
            var pos = o["pos"]?.ToObject<float[]>();
            var rot = o["rot"]?.ToObject<float[]>();
            if (pos == null || rot == null || pos.Length < 3 || rot.Length < 4) return false;
            pose = new Pose(new Vector3(pos[0], pos[1], pos[2]), new Quaternion(rot[0], rot[1], rot[2], rot[3]));
            if (o["frame"]?.Type == JTokenType.String && (string)o["frame"] == "openxr") pose = OpenXrToUnity(pose);
            return true;
        }

        /// <summary>Right-handed Y-up (-Z forward) -> Unity left-handed Y-up (+Z forward): mirror Z.</summary>
        public static Pose OpenXrToUnity(Pose p) =>
            new Pose(new Vector3(p.position.x, p.position.y, -p.position.z),
                     new Quaternion(-p.rotation.x, -p.rotation.y, p.rotation.z, p.rotation.w));
    }
}
