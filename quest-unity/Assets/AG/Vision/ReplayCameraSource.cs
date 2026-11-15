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
    /// Plays a recorded camera from a folder at 10 fps (looping), so tracking and perception can be tested without
    /// the object. Shared convention with the Android client and tools/quest/:
    /// <code>
    /// replay/
    ///   000000.jpg 000001.jpg ...   frames, sorted by file name (.jpg or .jpeg)
    ///   intrinsics.json             {"fx":..,"fy":..,"cx":..,"cy":..,"width":..,"height":..}  pixels of these JPEGs
    ///   poses.jsonl                 optional, one line per frame, in file order:
    ///                               {"file":"000000.jpg","t":0.0,"pos":[x,y,z],"rot":[x,y,z,w],"frame":"unity"}
    ///                               frame "unity" (left-handed, Y up, camera +Z forward; default) or
    ///                               "openxr" (right-handed, Y up, camera -Z forward) -> converted to Unity.
    ///                               Lines are matched by "file" when present, else by line order.
    /// </code>
    /// Folder: <c>LaunchParams.ReplayFolder</c> = <c>replay_dir</c> or <c>Application.persistentDataPath/replay</c>
    /// (device: /sdcard/Android/data/com.agj.unity/files/replay/). Main thread.
    /// </summary>
    public sealed class ReplayCameraSource : ICameraSource
    {
        public const float Fps = 10f;

        readonly string _dir;
        string[] _files = Array.Empty<string>();
        readonly Dictionary<string, Pose> _posesByFile = new Dictionary<string, Pose>();
        readonly List<Pose> _posesByOrder = new List<Pose>();
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
                Intrinsics = JsonConvert.DeserializeObject<CameraIntrinsics>(File.ReadAllText(Path.Combine(_dir, "intrinsics.json")));
                ReadPoses(Path.Combine(_dir, "poses.jsonl"));
                _tex = new Texture2D(2, 2, TextureFormat.RGBA32, false);
                IsRunning = true;
                _nextAt = 0;
                AgLog.Ev("camera", new { source = Name, dir = _dir, frames = _files.Length, poses = _posesByOrder.Count, Intrinsics.width, Intrinsics.height });
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
            _nextAt = now + 1.0 / Fps;
            var path = _files[_cursor];
            var jpeg = File.ReadAllBytes(path);
            _tex.LoadImage(jpeg, false);
            var f = new CameraFrame
            {
                Index = _index++, TimeS = now, Width = _tex.width, Height = _tex.height, Texture = _tex, Jpeg = jpeg,
                Intrinsics = Intrinsics.width == _tex.width ? Intrinsics : Intrinsics.Scaled(_tex.width, _tex.height),
                Pixels = CpuPixels ? _tex.GetPixels32() : null,
            };
            var name = Path.GetFileName(path);
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
            _cursor = (_cursor + 1) % _files.Length;
            _latest = f;
            _hasLatest = true;
            FrameArrived?.Invoke(f);
        }

        void ReadPoses(string path)
        {
            _posesByFile.Clear();
            _posesByOrder.Clear();
            if (!File.Exists(path)) return;
            foreach (var line in File.ReadAllLines(path))
            {
                if (string.IsNullOrWhiteSpace(line)) continue;
                var o = JObject.Parse(line);
                var pos = o["pos"]?.ToObject<float[]>();
                var rot = o["rot"]?.ToObject<float[]>();
                if (pos == null || rot == null || pos.Length < 3 || rot.Length < 4) continue;
                var pose = new Pose(new Vector3(pos[0], pos[1], pos[2]), new Quaternion(rot[0], rot[1], rot[2], rot[3]));
                if ((string)o["frame"] == "openxr") pose = OpenXrToUnity(pose);
                _posesByOrder.Add(pose);
                var file = (string)o["file"];
                if (!string.IsNullOrEmpty(file)) _posesByFile[file] = pose;
            }
        }

        /// <summary>Right-handed Y-up (-Z forward) -> Unity left-handed Y-up (+Z forward): mirror Z.</summary>
        public static Pose OpenXrToUnity(Pose p) =>
            new Pose(new Vector3(p.position.x, p.position.y, -p.position.z),
                     new Quaternion(-p.rotation.x, -p.rotation.y, p.rotation.z, p.rotation.w));
    }
}
