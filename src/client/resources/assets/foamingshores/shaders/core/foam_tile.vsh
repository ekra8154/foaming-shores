#version 330
#extension GL_ARB_separate_shader_objects : require

// Places 16x16 block tiles of a region in a page of the foam atlas: six vertices (two triangles)
// per tile, no vertex buffer. The tile table's first texel is (page size, slot size in pixels,
// region size in texels, -), then one texel per tile: (slot x, y in pixels, the tile's corner in
// region texels x, z). texCoord is the region texel position over the region's size, so the bake
// shader reads it as it reads a full-screen pass; a coarser slot spreads the tile's 256 texels
// over fewer pixels, each pixel's middle landing half a texel off a texel's edge.

uniform sampler2D TileSampler;

layout(location = 0) out vec2 texCoord;

const ivec2 CORNERS[6] = ivec2[](ivec2(0, 0), ivec2(1, 0), ivec2(0, 1), ivec2(0, 1), ivec2(1, 0), ivec2(1, 1));

void main() {
    int tile = gl_VertexIndex / 6;
    vec4 head = texelFetch(TileSampler, ivec2(0, 0), 0);
    vec4 slot = texelFetch(TileSampler, ivec2(tile + 1, 0), 0);
    vec2 corner = vec2(CORNERS[gl_VertexIndex % 6]);
    vec2 pixel = slot.xy + corner * head.y;
    gl_Position = vec4(pixel / head.x * 2.0 - 1.0, 0.0, 1.0);
    float texelsPerPixel = 256.0 / head.y;
    vec2 regionTexel = slot.zw + corner * 256.0 + (texelsPerPixel > 1.5 ? 0.5 : 0.0);
    texCoord = regionTexel / head.z;
}
