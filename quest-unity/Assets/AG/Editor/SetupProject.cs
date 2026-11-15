using System;
using System.IO;
using System.Linq;
using System.Reflection;
using AG.App;
using AG.UI;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.Rendering;
using UnityEngine.XR.Management;
using UnityEditor.XR.Management;
using UnityEditor.XR.Management.Metadata;
using Object = UnityEngine.Object;

namespace AG.Editor
{
    /// <summary>
    /// One-shot project setup for Quest 3 (idempotent; safe to run again):
    ///   Unity -batchmode -quit -projectPath quest-unity -buildTarget Android -executeMethod AG.Editor.SetupProject.Run
    /// or menu AG > Setup Project. Player settings, URP asset, XR loader (OpenXR + Meta features), the UI theme and
    /// Assets/AG/Scenes/Main.unity. Meta-specific settings are applied by reflection so this compiles even when a
    /// Meta package version renames something; anything it cannot set is logged as "SETUP-TODO" - then run
    /// Meta > Tools > Project Setup Tool > Fix All in the editor.
    /// </summary>
    public static class SetupProject
    {
        public const string ScenePath = "Assets/AG/Scenes/Main.unity";
        const string SettingsDir = "Assets/AG/Settings";

        [MenuItem("AG/Setup Project")]
        public static void Run()
        {
            TmpEssentials();
            Player();
            Urp();
            Xr();
            Scene();
            AssetDatabase.SaveAssets();
            Debug.Log("AG setup: done");
        }

        static void Todo(string what) => Debug.LogWarning("SETUP-TODO " + what);

        // ---------- Player ----------
        static void Player()
        {
            var android = NamedBuildTarget.Android;
            PlayerSettings.companyName = "AGJ";
            PlayerSettings.productName = "Assembly Guide";
            PlayerSettings.bundleVersion = "0.1.0";
            PlayerSettings.SetApplicationIdentifier(android, "com.agj.unity");
            PlayerSettings.SetScriptingBackend(android, ScriptingImplementation.IL2CPP);
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
            PlayerSettings.Android.minSdkVersion = (AndroidSdkVersions)32;      // Meta: 32 (Horizon OS)
            PlayerSettings.Android.targetSdkVersion = (AndroidSdkVersions)34;   // store requirement since 2026-03
            PlayerSettings.Android.forceInternetPermission = true;
            PlayerSettings.SetUseDefaultGraphicsAPIs(BuildTarget.Android, false);
            PlayerSettings.SetGraphicsAPIs(BuildTarget.Android, new[] { GraphicsDeviceType.Vulkan });
            PlayerSettings.colorSpace = ColorSpace.Linear;
            PlayerSettings.stereoRenderingPath = StereoRenderingPath.Instancing;
            PlayerSettings.defaultInterfaceOrientation = UIOrientation.LandscapeLeft;
            PlayerSettings.SetManagedStrippingLevel(android, ManagedStrippingLevel.Minimal);  // Newtonsoft + reflection
            EditorUserBuildSettings.androidBuildSubtarget = MobileTextureSubtarget.ASTC;
        }

        // ---------- URP ----------
        static void Urp()
        {
            var assetType = FindType("UnityEngine.Rendering.Universal.UniversalRenderPipelineAsset");
            var rendererType = FindType("UnityEngine.Rendering.Universal.UniversalRendererData");
            if (assetType == null || rendererType == null) { Todo("URP package missing"); return; }
            Directory.CreateDirectory(SettingsDir);
            string assetPath = SettingsDir + "/URP-Quest.asset", rendererPath = SettingsDir + "/URP-Quest-Renderer.asset";
            var asset = AssetDatabase.LoadAssetAtPath(assetPath, assetType) as RenderPipelineAsset;
            if (asset == null)
            {
                var data = ScriptableObject.CreateInstance(rendererType);
                AssetDatabase.CreateAsset(data, rendererPath);
                var create = assetType.GetMethod("Create", BindingFlags.Public | BindingFlags.Static, null, new[] { FindType("UnityEngine.Rendering.Universal.ScriptableRendererData") }, null);
                asset = create?.Invoke(null, new object[] { data }) as RenderPipelineAsset;
                if (asset == null) { Todo("could not create the URP asset"); return; }
                AssetDatabase.CreateAsset(asset, assetPath);
            }
            // Quest guidance: MSAA 4x, no HDR, no depth/opaque texture unless an effect needs it
            Set(asset, "msaaSampleCount", 4);
            Set(asset, "supportsHDR", false);
            Set(asset, "supportsCameraDepthTexture", false);
            Set(asset, "supportsCameraOpaqueTexture", false);
            GraphicsSettings.defaultRenderPipeline = asset;
            for (int i = 0; i < QualitySettings.names.Length; i++)
            {
                QualitySettings.SetQualityLevel(i, false);
                QualitySettings.renderPipeline = asset;
            }
            EditorUtility.SetDirty(asset);
        }

        // ---------- XR: OpenXR loader + Meta Quest feature + Meta XR feature ----------
        static void Xr()
        {
            try
            {
                const string assetPath = "Assets/XR/XRGeneralSettingsPerBuildTarget.asset";
                EditorBuildSettings.TryGetConfigObject(XRGeneralSettings.k_SettingsKey, out XRGeneralSettingsPerBuildTarget perTarget);
                if (perTarget == null)
                {
                    perTarget = AssetDatabase.LoadAssetAtPath<XRGeneralSettingsPerBuildTarget>(assetPath);
                    if (perTarget == null)
                    {
                        Directory.CreateDirectory("Assets/XR");
                        perTarget = ScriptableObject.CreateInstance<XRGeneralSettingsPerBuildTarget>();
                        AssetDatabase.CreateAsset(perTarget, assetPath);
                    }
                    EditorBuildSettings.AddConfigObject(XRGeneralSettings.k_SettingsKey, perTarget, true);
                }
                var path = AssetDatabase.GetAssetPath(perTarget);
                var general = perTarget.SettingsForBuildTarget(BuildTargetGroup.Android);
                if (general == null)
                {
                    general = ScriptableObject.CreateInstance<XRGeneralSettings>();
                    general.name = "Android Settings";
                    perTarget.SetSettingsForBuildTarget(BuildTargetGroup.Android, general);
                    AssetDatabase.AddObjectToAsset(general, path);
                }
                if (general.Manager == null)
                {
                    var mgr = ScriptableObject.CreateInstance<XRManagerSettings>();
                    mgr.name = "Android Providers";
                    general.Manager = mgr;
                    AssetDatabase.AddObjectToAsset(mgr, path);
                }
                general.InitManagerOnStart = true;
                if (!XRPackageMetadataStore.AssignLoader(general.Manager, "UnityEngine.XR.OpenXR.OpenXRLoader", BuildTargetGroup.Android))
                    Todo("assign OpenXR loader for Android");
                EditorUtility.SetDirty(perTarget);
                EditorUtility.SetDirty(general);
                EditorUtility.SetDirty(general.Manager);

                var settings = UnityEngine.XR.OpenXR.OpenXRSettings.GetSettingsForBuildTargetGroup(BuildTargetGroup.Android);
                if (settings == null) { Todo("OpenXR settings for Android"); return; }
                UnityEditor.XR.OpenXR.Features.FeatureHelpers.RefreshFeatures(BuildTargetGroup.Android);
                var metaSet = UnityEditor.XR.OpenXR.Features.OpenXRFeatureSetManager.GetFeatureSetWithId(BuildTargetGroup.Android, "com.meta.openxr.featureset.metaxr");
                if (metaSet != null)
                {
                    metaSet.isEnabled = true;
                    UnityEditor.XR.OpenXR.Features.OpenXRFeatureSetManager.SetFeaturesFromEnabledFeatureSets(BuildTargetGroup.Android);
                    Debug.Log("AG setup: Meta XR feature group on");
                }
                else Todo("Meta XR OpenXR feature group not found: Project Settings > XR Plug-in Management > OpenXR > Android");
                Debug.Log("AG setup: OpenXR features for Android: " + settings.GetFeatures().Length);
                foreach (var f in settings.GetFeatures())
                {
                    var name = f.GetType().FullName ?? "";
                    // Meta Quest Support (OpenXR package) + Meta XR feature (Meta Core SDK) + Meta hand tracking shims
                    if (name.EndsWith("MetaQuestFeature") || name.EndsWith("MetaXRFeature") || name.Contains("HandTracking"))
                    {
                        f.enabled = true;
                        Debug.Log("AG setup: OpenXR feature on: " + name);
                    }
                }
                EditorUtility.SetDirty(settings);
            }
            catch (Exception e) { Todo("XR setup failed: " + e.Message + " (set OpenXR + Meta XR feature group by hand)"); }
        }

        // ---------- Scene ----------
        static void Scene()
        {
            var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);

            // camera rig: Meta's OVRCameraRig (passthrough, hands) or a plain camera
            GameObject rig = null;
            var rigGuid = AssetDatabase.FindAssets("OVRCameraRig t:Prefab").FirstOrDefault();
            if (rigGuid != null) rig = (GameObject)PrefabUtility.InstantiatePrefab(AssetDatabase.LoadAssetAtPath<GameObject>(AssetDatabase.GUIDToAssetPath(rigGuid)));
            if (rig == null)
            {
                Todo("OVRCameraRig prefab not found; using a plain camera");
                rig = new GameObject("Main Camera", typeof(Camera), typeof(AudioListener)) { tag = "MainCamera" };
            }
            var cam = rig.GetComponentInChildren<Camera>();
            if (cam != null)
            {
                cam.clearFlags = CameraClearFlags.SolidColor;
                cam.backgroundColor = new Color(0, 0, 0, 0);   // transparent: passthrough shows through
                cam.nearClipPlane = 0.03f;
                if (!cam.CompareTag("MainCamera")) cam.tag = "MainCamera";
            }
            var ovrManager = rig.GetComponent("OVRManager");
            if (ovrManager != null)
            {
                Set(ovrManager, "isInsightPassthroughEnabled", true);
                var passthroughLayerType = FindType("OVRPassthroughLayer");   // defaults to Underlay
                if (passthroughLayerType == null) Todo("OVRPassthroughLayer type not found");
                else if (rig.GetComponent(passthroughLayerType) == null) rig.AddComponent(passthroughLayerType);
            }
            else Todo("OVRManager not on the rig: enable passthrough by hand");
            Todo("OVRManager: tick 'Enabled Passthrough Camera Access', Hand Tracking Support = Controllers And Hands, Scene Support, Anchor Support (Project Setup Tool can do it)");

            // passthrough camera access (MRUK)
            var pcaGo = new GameObject("PassthroughCamera");
            var pcaType = FindType("Meta.XR.PassthroughCameraAccess");
            if (pcaType != null) pcaGo.AddComponent(pcaType);
            else Todo("Meta.XR.PassthroughCameraAccess not found (MRUK v81+)");

            // theme
            Directory.CreateDirectory("Assets/AG/UI");
            var theme = AssetDatabase.LoadAssetAtPath<UiTheme>("Assets/AG/UI/UiTheme.asset");
            if (theme == null) { theme = ScriptableObject.CreateInstance<UiTheme>(); AssetDatabase.CreateAsset(theme, "Assets/AG/UI/UiTheme.asset"); }
            if (theme.FontAsset == null) { theme.FontAsset = InterSdf(); EditorUtility.SetDirty(theme); }

            // app root + anchor
            var anchor = new GameObject("AssemblyAnchor").transform;
            var root = new GameObject("AppRoot").AddComponent<AppRoot>();
            root.PassthroughCamera = pcaGo;
            root.AssemblyAnchor = anchor;
            root.Theme = theme;

            // test cube: 10 cm, 1 m ahead at eye height
            var cube = GameObject.CreatePrimitive(PrimitiveType.Cube);
            cube.name = "TestCube";
            cube.transform.position = new Vector3(0, 1.4f, 1.0f);
            cube.transform.localScale = Vector3.one * 0.1f;

            var light = new GameObject("Light", typeof(Light)).GetComponent<Light>();
            light.type = LightType.Directional;
            light.transform.rotation = Quaternion.Euler(50, -30, 0);

            Directory.CreateDirectory(Path.GetDirectoryName(ScenePath));
            EditorSceneManager.SaveScene(scene, ScenePath);
            EditorBuildSettings.scenes = new[] { new EditorBuildSettingsScene(ScenePath, true) };
        }

        static void TmpEssentials()
        {
            if (Resources.Load("TMP Settings") != null) return;
            const string pkg = "Packages/com.unity.ugui/Package Resources/TMP Essential Resources.unitypackage";
            AssetDatabase.ImportPackage(pkg, false);
            AssetDatabase.Refresh();
            if (Resources.Load("TMP Settings") == null) Todo("TMP Essential Resources not imported yet: run SetupProject again");
        }

        // ---------- Font: Inter (OFL) as a dynamic TextMeshPro SDF asset, Latin + Greek ----------
        const string FontTtf = "Assets/AG/UI/Fonts/Inter-Variable.ttf", FontSdf = "Assets/AG/UI/Fonts/Inter SDF.asset";

        static TMPro.TMP_FontAsset InterSdf()
        {
            var existing = AssetDatabase.LoadAssetAtPath<TMPro.TMP_FontAsset>(FontSdf);
            if (existing != null) return existing;
            if (Resources.Load("TMP Settings") == null) { Todo("font asset skipped until TMP Essential Resources exist"); return null; }
            var font = AssetDatabase.LoadAssetAtPath<Font>(FontTtf);
            if (font == null) { Todo("font missing: " + FontTtf); return null; }
            var fa = TMPro.TMP_FontAsset.CreateFontAsset(font, 90, 9, UnityEngine.TextCore.LowLevel.GlyphRenderMode.SDFAA, 2048, 2048,
                TMPro.AtlasPopulationMode.Dynamic, true);
            if (fa == null) { Todo("TMP font asset creation failed"); return null; }
            fa.name = "Inter SDF";
            AssetDatabase.CreateAsset(fa, FontSdf);
            fa.atlasTextures[0].name = "Inter SDF Atlas";
            AssetDatabase.AddObjectToAsset(fa.atlasTextures[0], fa);
            fa.material.name = "Inter SDF Material";
            AssetDatabase.AddObjectToAsset(fa.material, fa);
            // pre-bake Latin + Greek + Greek Extended so first use does not hitch
            var chars = new System.Text.StringBuilder();
            for (int c = 0x20; c < 0x7F; c++) chars.Append((char)c);
            for (int c = 0x370; c < 0x400; c++) chars.Append((char)c);
            chars.Append("·×–—…«»“”’€°±→←↑↓↻↺✓");
            fa.TryAddCharacters(chars.ToString(), out _);
            EditorUtility.SetDirty(fa);
            return fa;
        }

        // ---------- helpers ----------
        public static Type FindType(string fullName) =>
            AppDomain.CurrentDomain.GetAssemblies().Select(a => a.GetType(fullName, false)).FirstOrDefault(t => t != null);

        static void Set(object target, string member, object value)
        {
            if (target == null) return;
            const BindingFlags F = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance;
            var t = target.GetType();
            try
            {
                var p = t.GetProperty(member, F);
                if (p != null && p.CanWrite) { p.SetValue(target, value); return; }
                var f = t.GetField(member, F) ?? t.GetField("m_" + char.ToUpper(member[0]) + member.Substring(1), F);
                if (f != null) { f.SetValue(target, value); return; }
            }
            catch (Exception e) { Todo($"{t.Name}.{member}: {e.Message}"); return; }
            Todo($"{t.Name}.{member} not found");
        }
    }
}
