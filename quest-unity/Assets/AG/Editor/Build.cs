using System.IO;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;

namespace AG.Editor
{
    /// <summary>
    /// Batchmode build:
    ///   Unity -quit -batchmode -nographics -projectPath quest-unity -buildTarget Android \
    ///         -executeMethod AG.Editor.Build.Apk -logFile -
    /// Output: quest-unity/Builds/ag.apk. Runs SetupProject first when the scene is missing. Exit code 1 on failure.
    /// </summary>
    public static class Build
    {
        public const string ApkPath = "Builds/ag.apk";

        [MenuItem("AG/Build APK")]
        public static void Apk()
        {
            if (!File.Exists(SetupProject.ScenePath)) SetupProject.Run();
            else
            {
                // player settings are cheap and keep CI deterministic
                var run = typeof(SetupProject).GetMethod("Player", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static);
                run?.Invoke(null, null);
            }
            Directory.CreateDirectory("Builds");
            var report = BuildPipeline.BuildPlayer(new BuildPlayerOptions
            {
                scenes = new[] { SetupProject.ScenePath },
                locationPathName = ApkPath,
                target = BuildTarget.Android,
                targetGroup = BuildTargetGroup.Android,
                options = BuildOptions.None,
            });
            var s = report.summary;
            Debug.Log($"AG build: {s.result} {s.totalSize} bytes, {s.totalErrors} errors, {s.totalTime} -> {ApkPath}");
            if (Application.isBatchMode) EditorApplication.Exit(s.result == BuildResult.Succeeded ? 0 : 1);
        }
    }
}
