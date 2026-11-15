// Dark glass for windows, ornaments, pills and buttons (XR_NATIVE_SPEC §3.1), drawn on one quad:
// superellipse rounded rectangle (exponent 5) as an SDF from UV + size in metres, vertical tint gradient
// rgba(24,27,31) -> rgba(8,9,11), alpha 0.90 centre -> 0.80 in the outer 16 mm, 150 deg sheen (white 7 %),
// rim light 1.5 mm (white 24 % top -> 8 % bottom), 12 mm inner shadow at the bottom (black 22 %), 24 mm outer halo
// (black 14 %), screen-space dither noise (+-1.5 %). The quad is bigger than the panel by _HaloM on each side.
// _Tint (a > 0) replaces the base gradient (primary button: accent-deep). _Fill adds white (hover/press).
Shader "AG/Glass"
{
    Properties
    {
        _SizeM ("Panel size (m) xy, quad size (m) zw", Vector) = (0.52, 0.34, 0.568, 0.388)
        _RadiusM ("Corner radius (m)", Float) = 0.024
        _HaloM ("Halo width (m)", Float) = 0.024
        _AlphaCentre ("Alpha centre", Float) = 0.9
        _AlphaEdge ("Alpha edge", Float) = 0.8
        _EdgeBandM ("Edge band (m)", Float) = 0.016
        _RimTop ("Rim top", Float) = 0.24
        _RimBottom ("Rim bottom", Float) = 0.08
        _RimM ("Rim width (m)", Float) = 0.0015
        _Sheen ("Sheen", Float) = 0.07
        _Shadow ("Inner shadow", Float) = 0.22
        _HaloAlpha ("Halo alpha", Float) = 0.14
        _Tint ("Tint (a > 0 replaces base)", Color) = (0, 0, 0, 0)
        _Fill ("White fill add", Float) = 0
        _Alpha ("Master alpha", Range(0, 1)) = 1
        _Exponent ("Superellipse exponent", Float) = 5
    }
    SubShader
    {
        Tags { "RenderPipeline" = "UniversalPipeline" "Queue" = "Transparent+10" "RenderType" = "Transparent" "IgnoreProjector" = "True" }
        Pass
        {
            Name "Glass"
            Tags { "LightMode" = "UniversalForward" }
            Blend One OneMinusSrcAlpha
            ZWrite Off
            ZTest LEqual
            Cull Off
            HLSLPROGRAM
            #pragma vertex Vert
            #pragma fragment Frag
            #pragma multi_compile_instancing
            #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Core.hlsl"
            CBUFFER_START(UnityPerMaterial)
                float4 _SizeM;
                float _RadiusM;
                float _HaloM;
                half _AlphaCentre;
                half _AlphaEdge;
                float _EdgeBandM;
                half _RimTop;
                half _RimBottom;
                float _RimM;
                half _Sheen;
                half _Shadow;
                half _HaloAlpha;
                half4 _Tint;
                half _Fill;
                half _Alpha;
                half _Exponent;
            CBUFFER_END
            struct Attributes { float4 positionOS : POSITION; float2 uv : TEXCOORD0; UNITY_VERTEX_INPUT_INSTANCE_ID };
            struct Varyings { float4 positionCS : SV_POSITION; float2 uv : TEXCOORD0; UNITY_VERTEX_INPUT_INSTANCE_ID UNITY_VERTEX_OUTPUT_STEREO };
            Varyings Vert(Attributes v)
            {
                Varyings o = (Varyings)0;
                UNITY_SETUP_INSTANCE_ID(v);
                UNITY_TRANSFER_INSTANCE_ID(v, o);
                UNITY_INITIALIZE_VERTEX_OUTPUT_STEREO(o);
                o.positionCS = TransformObjectToHClip(v.positionOS.xyz);
                o.uv = v.uv;
                return o;
            }
            float SdSuper(float2 p, float2 h, float r, float e)
            {
                float2 q = abs(p) - (h - r);
                float2 m = max(q, 0);
                float outer = pow(pow(m.x, e) + pow(m.y, e), 1.0 / e);
                return outer + min(max(q.x, q.y), 0) - r;
            }
            half3 SrgbToLinear(half3 c) { return pow(c, 2.2); }
            half4 Frag(Varyings i) : SV_Target
            {
                UNITY_SETUP_STEREO_EYE_INDEX_POST_VERTEX(i);
                float2 size = _SizeM.xy;
                float2 quad = _SizeM.zw;
                float2 p = (i.uv - 0.5) * quad;                 // metres from the centre
                float2 h = size * 0.5;
                float r = min(_RadiusM, min(h.x, h.y));
                float d = SdSuper(p, h, r, _Exponent);          // < 0 inside
                float aa = max(fwidth(d), 1e-5);
                half inside = 1 - smoothstep(-aa, aa, d);
                half halo = _HaloAlpha * (1 - inside) * exp(-3.0 * pow(max(d, 0) / max(_HaloM, 1e-4), 2));
                float t = saturate((p.y + h.y) / size.y);       // 0 bottom, 1 top
                half3 baseCol = lerp(SrgbToLinear(half3(8, 9, 11) / 255.0), SrgbToLinear(half3(24, 27, 31) / 255.0), t);
                if (_Tint.a > 0) baseCol = lerp(baseCol, _Tint.rgb, _Tint.a);
                float inDist = max(-d, 0);
                half a = lerp(_AlphaEdge, _AlphaCentre, saturate(inDist / max(_EdgeBandM, 1e-4)));
                // inner shadow along the bottom edge
                half shadow = _Shadow * saturate(1 - inDist / 0.012) * saturate(1 - t * 3);
                // sheen at 150 deg from the top-left corner to 45 % of the diagonal
                float2 fromTL = float2(p.x + h.x, h.y - p.y) / size;
                half sheen = _Sheen * saturate(1 - dot(fromTL, float2(0.866, 0.5)) / 0.45);
                half rim = lerp(_RimBottom, _RimTop, t) * (1 - smoothstep(_RimM * 0.6, _RimM, inDist));
                // dither (+-1.5 %), screen space
                float n = frac(52.9829189 * frac(dot(i.positionCS.xy, float2(0.06711056, 0.00583715)))) - 0.5;
                half3 col = baseCol * (1 - shadow) * (1 + n * 0.03);
                half white = saturate(sheen + rim + _Fill);
                half3 pm = col * a + white;                         // premultiplied: white adds light
                half alpha = saturate(a + white * (1 - a));
                pm *= inside * _Alpha;
                alpha *= inside * _Alpha;
                return half4(pm, saturate(alpha + halo * _Alpha));
            }
            ENDHLSL
        }
    }
}
