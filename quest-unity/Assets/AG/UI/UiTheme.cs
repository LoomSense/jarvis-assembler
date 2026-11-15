using UnityEngine;

namespace AG.UI
{
    /// <summary>
    /// Look of every panel, label and HUD. One asset (Assets/AG/UI/UiTheme.asset, created by SetupProject).
    /// Values follow docs/design/BRAND.md / tokens.css and docs/design/XR_NATIVE_SPEC.md (visionOS-like glass).
    /// </summary>
    [CreateAssetMenu(menuName = "AG/UI Theme", fileName = "UiTheme")]
    public sealed class UiTheme : ScriptableObject
    {
        [Header("Materials (URP)")]
        [Tooltip("Glass panel material: blurred/translucent background, rim light. Slot for the design director's shader.")]
        public Material GlassMaterial;
        [Tooltip("Ghost part material: translucent with fresnel rim.")]
        public Material GhostMaterial;
        public Material ArrowMaterial;

        [Header("Text (TextMeshPro SDF, must contain Greek U+0370-03FF and U+1F00-1FFF)")]
        public Object FontAsset;   // TMP_FontAsset; typed as Object so this file compiles without TMP

        [Header("Sizes, angular (degrees of visual angle) - converted to metres at the viewing distance")]
        public float TitleDeg = 1.4f;
        public float BodyDeg = 1.0f;
        public float CaptionDeg = 0.75f;
        public float PanelGapM = 0.15f;

        [Header("Colours")]
        public Color Text = new Color(1f, 1f, 1f, 0.96f);
        public Color TextSecondary = new Color(1f, 1f, 1f, 0.62f);
        public Color Accent = new Color(0.04f, 0.52f, 1f);
        public Color Warning = new Color(1f, 0.62f, 0.04f);
        public Color Success = new Color(0.19f, 0.82f, 0.35f);

        /// <summary>Metres of text height that subtend <paramref name="deg"/> at <paramref name="distanceM"/>.</summary>
        public static float DegToMetres(float deg, float distanceM) => 2f * distanceM * Mathf.Tan(deg * 0.5f * Mathf.Deg2Rad);
    }
}
