#version 430
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

// Ghost of the next part: translucent azure body with a fresnel rim (docs/design/XR_NATIVE_SPEC.md §4.1).
// The uniform block is ours (see ghost_fresnel.vert): same order as GhostMaterial.ATTRIBUTES.
#include <data/shaders/common.glsl>

layout (std140, set = 3, binding = 0) uniform MaterialUniform {
  vec4 albedoFactor; // rgb tint (linear), a = body opacity at normal incidence
  vec4 matParams;    // x rim strength, y rim exponent, z overall opacity, w unused
  vec4 stereoParams;
} g_MaterialUniform;

layout (location = 0) in struct {
  vec4 color;
  vec2 albedoCoord;
  vec2 roughnessMetallicCoord;
  vec2 emissiveCoord;
  vec2 occlusionCoord;
  vec2 normalCoord;
  vec3 lighting;
  vec3 worldNormal;
  vec3 worldPosition;
#if VERTEX_FORMAT_TWOUV_TANGENT == 1
  vec4 tangent;
#endif
} vertexOut;

layout (location = 0) out vec4 outColor;

void main() {
  vec3 n = normalize(vertexOut.worldNormal);
  vec3 v = normalize(getEyeCenter() - vertexOut.worldPosition);
  float ndv = clamp(abs(dot(n, v)), 0.0, 1.0);
  float rim = g_MaterialUniform.matParams.x * pow(1.0 - ndv, max(g_MaterialUniform.matParams.y, 0.5));
  vec3 tint = g_MaterialUniform.albedoFactor.rgb;
  float body = g_MaterialUniform.albedoFactor.a;
  float a = clamp(body + rim, 0.0, 1.0) * clamp(g_MaterialUniform.matParams.z, 0.0, 1.0);
  outColor = vec4(tint * (0.55 + 0.9 * rim), a);
}
