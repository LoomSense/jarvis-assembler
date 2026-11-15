#version 430
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

// Ghost of the next part (docs/design/XR_NATIVE_SPEC.md §4.1). Our own vertex stage. The SDK's default vertex
// includes metaSpatialSdkMaterialBase.glsl, whose MaterialUniform block (matParams, alphaParams, stereoParams,
// emissiveFactor, albedoFactor, 10 UV transforms) does NOT match a custom material: the SDK packs a custom
// material's vec4 attributes into set 3 binding 0 IN THE ORDER THEY ARE DECLARED (Spatial SDK custom-shader docs).
// Round 1 read albedoFactor from past the end of its 4-vec4 buffer, and the material was OPAQUE (the default
// AlphaMode of SceneMaterial.custom): the first device run showed a solid orange ghost.
// This block must stay in the same order as GhostMaterial.ATTRIBUTES.
#include <data/shaders/common.glsl>
#include <data/shaders/app2vertex.glsl>

layout (std140, set = 3, binding = 0) uniform MaterialUniform {
  vec4 albedoFactor; // rgb tint (linear), a = body opacity at normal incidence
  vec4 matParams;    // x rim strength, y rim exponent, z overall opacity, w unused
  vec4 stereoParams; // unused here
} g_MaterialUniform;

layout (location = 0) out struct {
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

void main() {
  App2VertexUnpacked app = getApp2VertexUnpacked();
  vec4 wPos4 = g_PrimitiveUniform.worldFromObject * vec4(app.position, 1.0f);
  vertexOut.color = vec4(1.0);
  vertexOut.albedoCoord = app.uv;
  vertexOut.roughnessMetallicCoord = app.uv;
  vertexOut.emissiveCoord = app.uv;
  vertexOut.occlusionCoord = app.uv;
  vertexOut.normalCoord = app.uv;
  vertexOut.lighting = app.incomingLighting;
  vertexOut.worldPosition = wPos4.xyz;
  vertexOut.worldNormal = normalize((transpose(g_PrimitiveUniform.objectFromWorld) * vec4(app.normal, 0.0f)).xyz);
#if VERTEX_FORMAT_TWOUV_TANGENT == 1
  vertexOut.tangent = app.tangent;
#endif
  gl_Position = getClipFromWorld() * wPos4;
  postprocessPosition(gl_Position);
}
