// Ghost of the next part (XR_NATIVE_SPEC §4.1): azure fill (alpha ~0.14, breathing set from C#), emissive
// fresnel rim pow(1 - N.V, 2.5) * 0.65, unlit, translucent. Pass 1 primes depth so the ghost's own back faces do
// not show through (depth-correct); pass 2 draws colour with ZWrite off. _Dash > 0 cuts world-space stripes
// (tracking lost / wrong-part outline). Single-pass instanced stereo.
Shader "AG/Ghost"
{
    Properties
    {
        _Color ("Fill (rgb, a = fill alpha)", Color) = (0.62, 0.81, 1, 0.14)
        _RimColor ("Rim", Color) = (0.62, 0.81, 1, 1)
        _RimStrength ("Rim strength", Float) = 0.65
        _RimPower ("Rim power", Float) = 2.5
        _Alpha ("Master alpha", Range(0, 1)) = 1
        _Dash ("Dash on (0/1)", Float) = 0
        _DashPeriod ("Dash period (m)", Float) = 0.013
        _DepthPrime ("Write depth (0/1)", Float) = 1
    }
    SubShader
    {
        Tags { "RenderPipeline" = "UniversalPipeline" "Queue" = "Transparent" "RenderType" = "Transparent" "IgnoreProjector" = "True" }

        HLSLINCLUDE
        #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Core.hlsl"
        CBUFFER_START(UnityPerMaterial)
            half4 _Color;
            half4 _RimColor;
            half _RimStrength;
            half _RimPower;
            half _Alpha;
            half _Dash;
            half _DashPeriod;
            half _DepthPrime;
        CBUFFER_END

        struct Attributes
        {
            float4 positionOS : POSITION;
            float3 normalOS : NORMAL;
            UNITY_VERTEX_INPUT_INSTANCE_ID
        };
        struct Varyings
        {
            float4 positionCS : SV_POSITION;
            float3 positionWS : TEXCOORD0;
            float3 normalWS : TEXCOORD1;
            UNITY_VERTEX_INPUT_INSTANCE_ID
            UNITY_VERTEX_OUTPUT_STEREO
        };
        Varyings Vert(Attributes v)
        {
            Varyings o = (Varyings)0;
            UNITY_SETUP_INSTANCE_ID(v);
            UNITY_TRANSFER_INSTANCE_ID(v, o);
            UNITY_INITIALIZE_VERTEX_OUTPUT_STEREO(o);
            o.positionWS = TransformObjectToWorld(v.positionOS.xyz);
            o.positionCS = TransformWorldToHClip(o.positionWS);
            o.normalWS = TransformObjectToWorldNormal(v.normalOS);
            return o;
        }
        half DashMask(float3 p)
        {
            if (_Dash < 0.5) return 1;
            float s = frac(dot(p, float3(0.577, 0.577, 0.577)) / max(_DashPeriod, 1e-4));
            return s < 0.6 ? 1 : 0;
        }
        ENDHLSL

        Pass
        {
            Name "GhostDepth"
            Tags { "LightMode" = "SRPDefaultUnlit" }
            ZWrite On
            ColorMask 0
            Cull Back
            HLSLPROGRAM
            #pragma vertex Vert
            #pragma fragment FragDepth
            #pragma multi_compile_instancing
            half4 FragDepth(Varyings i) : SV_Target
            {
                UNITY_SETUP_STEREO_EYE_INDEX_POST_VERTEX(i);
                clip(_DepthPrime - 0.5);
                clip(_Alpha - 0.05);
                return 0;
            }
            ENDHLSL
        }

        Pass
        {
            Name "GhostColor"
            Tags { "LightMode" = "UniversalForward" }
            Blend One OneMinusSrcAlpha
            ZWrite Off
            ZTest LEqual
            Cull Back
            HLSLPROGRAM
            #pragma vertex Vert
            #pragma fragment Frag
            #pragma multi_compile_instancing
            half4 Frag(Varyings i) : SV_Target
            {
                UNITY_SETUP_STEREO_EYE_INDEX_POST_VERTEX(i);
                float3 n = normalize(i.normalWS);
                float3 v = normalize(GetWorldSpaceViewDir(i.positionWS));
                half rim = pow(1 - saturate(abs(dot(n, v))), _RimPower) * _RimStrength;
                half mask = DashMask(i.positionWS);
                half fillA = _Color.a * _Alpha * mask;
                half rimA = saturate(rim * _RimColor.a * _Alpha * mask);
                half3 rgb = _Color.rgb * fillA + _RimColor.rgb * rimA;
                return half4(rgb, saturate(fillA + rimA * 0.5));
            }
            ENDHLSL
        }
    }
}
