#version 400
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

// Ghost of the next part: translucent body with a fresnel rim (docs/design/XR_NATIVE_SPEC.md owns the look).
// Material attributes (SceneMaterial.custom, see guide/GhostMaterial.kt):
//   albedoFactor.rgb  tint (linear), albedoFactor.a  body opacity at normal incidence
//   matParams.x       rim strength, matParams.y  rim exponent, matParams.z  overall opacity (fade in/out)
#include <data/shaders/metaSpatialSdkFragmentBase.glsl>
#include <data/shaders/Uniforms.glsl>

void main() {
  vec3 n = normalize(vertexOut.worldNormal);
  // eyeCenter0 is the left (or mono) eye; good enough for a rim term in both eyes.
  vec3 v = normalize(g_ViewUniform.eyeCenter0.xyz - vertexOut.worldPosition);
  float ndv = clamp(abs(dot(n, v)), 0.0, 1.0);
  float rim = g_MaterialUniform.matParams.x * pow(1.0 - ndv, max(g_MaterialUniform.matParams.y, 0.5));
  vec3 tint = g_MaterialUniform.albedoFactor.rgb;
  float body = g_MaterialUniform.albedoFactor.a;
  float a = clamp(body + rim, 0.0, 1.0) * g_MaterialUniform.matParams.z;
  outColor = vec4(tint * (0.55 + 0.9 * rim), a);
}
