using UnityEngine;

namespace AG.App
{
    /// <summary>
    /// Mixed reality, not VR: every camera clears to transparent black so the passthrough underlay shows behind
    /// the app, and no skybox is ever drawn. Done at runtime for every camera of the rig, because the scene
    /// setup only reached the first camera it found (first device run, 15 Nov: the default skybox covered the room).
    /// </summary>
    static class PassthroughSetup
    {
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Apply()
        {
            RenderSettings.skybox = null;
            RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Flat;
            RenderSettings.ambientLight = Color.white;
            foreach (var cam in Object.FindObjectsByType<Camera>(FindObjectsInactive.Include, FindObjectsSortMode.None))
            {
                cam.clearFlags = CameraClearFlags.SolidColor;
                cam.backgroundColor = new Color(0, 0, 0, 0);
                cam.allowHDR = false;   // an HDR target can drop the alpha the underlay needs
            }
        }
    }
}
