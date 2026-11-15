// Unlit translucent colour for guidance marks (path dots, arrowheads, turning arrow, rings, badges, leader lines).
// _Shape on a quad's UV: 0 none (whole mesh), 1 disc with soft halo, 2 ring, 3 octagon, 4 diamond.
// _SweepGain adds a bright band at uv.x == _SweepPos (turning-arrow sweep, 60 deg wide). Premultiplied alpha.
Shader "AG/Flat"
{
    Properties
    {
        _Color ("Colour", Color) = (0.62, 0.81, 1, 1)
        _Alpha ("Master alpha", Range(0, 1)) = 1
        _Shape ("Shape", Float) = 0
        _Halo ("Halo alpha (disc)", Float) = 0
        _RingWidth ("Ring width (uv)", Float) = 0.08
        _SweepPos ("Sweep position (uv.x)", Float) = -1
        _SweepWidth ("Sweep width (uv.x)", Float) = 0.2
        _SweepGain ("Sweep gain", Float) = 0
        _MainTex ("Line art (crop)", 2D) = "white" {}
        _LineArt ("Line art: dark lines -> white on glass (0/1)", Float) = 0
        _Image ("Image: texture colour (camera preview) (0/1)", Float) = 0
        [Enum(Off, 0, On, 1)] _ZWrite ("ZWrite", Float) = 0
        [Enum(UnityEngine.Rendering.CompareFunction)] _ZTest ("ZTest", Float) = 4
    }
    SubShader
    {
        Tags { "RenderPipeline" = "UniversalPipeline" "Queue" = "Transparent+5" "RenderType" = "Transparent" "IgnoreProjector" = "True" }
        Pass
        {
            Name "Flat"
            Tags { "LightMode" = "UniversalForward" }
            Blend One OneMinusSrcAlpha
            ZWrite [_ZWrite]
            ZTest [_ZTest]
            Cull Off
            HLSLPROGRAM
            #pragma vertex Vert
            #pragma fragment Frag
            #pragma multi_compile_instancing
            #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Core.hlsl"
            CBUFFER_START(UnityPerMaterial)
                half4 _Color;
                half _Alpha;
                half _Shape;
                half _Halo;
                half _RingWidth;
                half _SweepPos;
                half _SweepWidth;
                half _SweepGain;
                half _ZWrite;
                half _ZTest;
                half _LineArt;
                half _Image;
                float4 _MainTex_ST;
            CBUFFER_END
            TEXTURE2D(_MainTex);
            SAMPLER(sampler_MainTex);
            struct Attributes { float4 positionOS : POSITION; float2 uv : TEXCOORD0; half4 color : COLOR; UNITY_VERTEX_INPUT_INSTANCE_ID };
            struct Varyings { float4 positionCS : SV_POSITION; float2 uv : TEXCOORD0; half4 color : COLOR; UNITY_VERTEX_INPUT_INSTANCE_ID UNITY_VERTEX_OUTPUT_STEREO };
            Varyings Vert(Attributes v)
            {
                Varyings o = (Varyings)0;
                UNITY_SETUP_INSTANCE_ID(v);
                UNITY_TRANSFER_INSTANCE_ID(v, o);
                UNITY_INITIALIZE_VERTEX_OUTPUT_STEREO(o);
                o.positionCS = TransformObjectToHClip(v.positionOS.xyz);
                o.uv = v.uv;
                o.color = v.color;
                return o;
            }
            half4 Frag(Varyings i) : SV_Target
            {
                UNITY_SETUP_STEREO_EYE_INDEX_POST_VERTEX(i);
                half a = _Color.a * _Alpha * i.color.a;
                if (_LineArt > 0.5)
                {
                    half4 tex = SAMPLE_TEXTURE2D(_MainTex, sampler_MainTex, i.uv);
                    half lum = dot(tex.rgb, half3(0.299, 0.587, 0.114));
                    a *= saturate((1 - lum) * 1.6) * tex.a;
                }
                float2 p = i.uv - 0.5;
                float aa = max(fwidth(p.x), 1e-4) * 1.5;
                if (_Shape > 0.5)
                {
                    float d;
                    if (_Shape < 1.5) d = length(p) - 0.5;                                     // disc
                    else if (_Shape < 2.5) d = abs(length(p) - (0.5 - _RingWidth)) - _RingWidth; // ring
                    else if (_Shape < 3.5) { float2 q = abs(p); d = max(max(q.x, q.y), (q.x + q.y) * 0.7071) - 0.5; } // octagon
                    else { float2 q = abs(p); d = (q.x + q.y) - 0.5; }                          // diamond
                    half body = 1 - smoothstep(-aa, aa, d);
                    half halo = _Halo * saturate(1 - (length(p) - 0.2) / 0.3);
                    if (_Shape < 1.5)
                    {
                        // with a halo the core disc is 0.44 of the quad (the rest is halo); without, the full quad
                        half rr = _Halo > 0 ? 0.22 : 0.5;
                        half core = 1 - smoothstep(-aa, aa, length(p) - rr);
                        a = a * core + halo * _Alpha * (1 - core);
                    }
                    else a *= body;
                }
                if (_SweepGain > 0)
                {
                    float s = abs(i.uv.x - _SweepPos);
                    a = saturate(a + _SweepGain * saturate(1 - s / max(_SweepWidth * 0.5, 1e-3)));
                }
                half3 rgb = _Color.rgb * i.color.rgb;
                if (_Image > 0.5) rgb *= SAMPLE_TEXTURE2D(_MainTex, sampler_MainTex, i.uv).rgb;
                return half4(rgb * a, a);
            }
            ENDHLSL
        }
    }
}
