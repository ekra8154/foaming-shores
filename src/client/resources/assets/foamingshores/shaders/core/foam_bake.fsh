#version 330
#extension GL_ARB_separate_shader_objects : require

// Bakes shore foam from the stateless wave model (see foaming-shores-playground). Every value is a
// function of the fields, the wave list and the time: nothing carries over between bakes.
//
// Mode 0 bakes block tops: one region texture, 16 texels per block.
// Mode 1 bakes faces: a texture of 16 x 80 tiles, one per wall or drop face, rows on the world's
// height grid (row 0 at the tile's top).

// Per field sample (4 per block): arrival, shore distance (both in texels), exposure, and the
// block's level (-4..1 for land, 8 for a pool, 9 for sea water).
uniform sampler2D FieldSampler;
// Per field sample: where its water came ashore (the arrival at that waterline; at sea its own
// arrival), the far field (arrival blurred over many blocks), the crossing front's arrival and that
// front's waterline (-1000 where there is none).
uniform sampler2D Field2Sampler;
// 256x256 tiling noise: r = front raggedness, g = lip width, b = foam lace.
uniform sampler2D NoiseSampler;
// Per face, two texels: (edge texel x, z, step x, z) in region texels, (out x, z, kind, height).
// Kind 1 = drop, 3 = ledge over the sea, 4 = step down into a dip, 5 = waterfall (the body's own
// water going over into lower water: vanilla draws the water, only foam pours) (edge texel on top
// of each); 2 = wall (edge texel is the one at its foot).
uniform sampler2D FaceSampler;
// Per field sample: (arrival at its dip's entry, 1 if in a dip, 1 if land too small to shape the
// waves, -).
uniform sampler2D DipSampler;
// Vanilla's block atlas: the sheet is drawn with the water textures, animated by vanilla.
uniform sampler2D AtlasSampler;
// In the face pass, the tops just baked: a ledge or step carries foam on its edge over its top row.
uniform sampler2D RegionSampler;
// Per field sample: a river's current (texels per second, x and z), how much river it is (1 in a
// river, fading into the sea at its mouth, 0 elsewhere) and how shallow (1 a block deep, 0.5 two).
uniform sampler2D RiverSampler;
// Per region texel, round rocks in rivers: the wake (r), the foam pillow (g) and foam trails (b);
// and still water (a): 0 for none, else 1..255 for how much the wind's ripples show there.
uniform sampler2D RiverDecorSampler;
// Still water's fetch, per field sample: four texels side by side hold the 16 wind directions
// (texel k, channel c is direction 4k + c), as 0..1 of 384 texels of open water upwind.
uniform sampler2D FetchSampler;

layout(std140) uniform FoamBake {
    vec4 Region;   // region size in texels, field samples per block, field size in samples, -
    vec4 Origin;   // region origin in world texels (x, z) mod 256, time in seconds, wave count
    vec4 Shape;    // lip width, raggedness, foam far out at sea, lace density
    vec4 Lace;     // lace half-life under the sheet, on sand, at sea; backwash pull (texels/s)
    vec4 Look;     // drying time, wet darkness, sheet opacity, calmer in coves
    vec4 Motion;   // approach distance (texels), run-up distance (texels), approach speed (texels/s), pause (s)
    vec4 Motion2;  // backwash time (s), foam tint, puddle soak time (s), puddle spread (texels)
    vec4 Water;    // biome water colour
    vec4 Terrain;  // spill over drops, falling foam reach (texels), splash height (texels), foam piling
    vec4 Mode;     // mode, face count, face texture width, face texture height
    vec4 Sprites;  // atlas texel origin of water_still (x, y) and of water_flow (x, y)
    vec4 Swell;    // straighter fronts at sea, peel (s), peel scale (blocks), waves differ along the coast
    vec4 Swell2;   // travel fade (texels), crossing strength, crossing fades within (texels), -
    vec4 SwellOrigin; // region origin on the swell's noise lattice: whole cells (x, z), fraction (x, z)
    vec4 River;    // flow lines, flow line length (texels), lighter shallows, lapping at the banks
    vec4 Wind;     // the way the wind blows (x, z, unit), its strength, its direction as 0..16 of the fetch's directions
    vec4 Still;    // wind ripples, time between ripples (s), ripples full size over (texels), lapping run-up (texels)
    vec4 RainW;    // rain (0..1), rain rings, region origin in world texels (x, z)
    vec4 Waves[24]; // per wave: (t0, amplitude, front noise offset xy), (lace offset xy, lip noise offset xy)
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const float BAYER[16] = float[](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);
const float UNREACHABLE = -900.0;
const float GRAV = 160.0; // texels/s^2: 9.8 m/s^2 at 16 texels per block
const int TILE_H = 80;

float noiseAt(int channel, ivec2 p) {
    return texelFetch(NoiseSampler, ivec2(p.x & 255, p.y & 255), 0)[channel];
}

float hash3(int x, int y, int s) {
    uint h = uint(x) * 374761393u + uint(y) * 668265263u + uint(s) * 1442695041u;
    h = (h ^ (h >> 13u)) * 1274126177u;
    h ^= h >> 16u;
    return float(h) / 4294967296.0;
}

float bayerAt(ivec2 p) {
    return (BAYER[(p.y & 3) * 4 + (p.x & 3)] + 0.5) / 16.0;
}

// Field values at a region texel, interpolated between samples. Unreachable samples are left out
// of the blend so the texels right next to a wall keep their arrival value.
vec4 fieldsAt(vec2 texel) {
    float perSample = 16.0 / Region.y;
    vec2 s = (texel + 0.5) / perSample - 0.5;
    int n = int(Region.z);
    ivec2 i = clamp(ivec2(floor(s)), ivec2(0), ivec2(n - 2));
    vec2 f = clamp(s - vec2(i), 0.0, 1.0);
    vec4 a = texelFetch(FieldSampler, i, 0);
    vec4 b = texelFetch(FieldSampler, i + ivec2(1, 0), 0);
    vec4 c = texelFetch(FieldSampler, i + ivec2(0, 1), 0);
    vec4 d = texelFetch(FieldSampler, i + ivec2(1, 1), 0);
    vec4 w = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y), (1.0 - f.x) * f.y, f.x * f.y);
    float shore = mix(mix(a.y, b.y, f.x), mix(c.y, d.y, f.x), f.y);
    w *= vec4(a.x > UNREACHABLE, b.x > UNREACHABLE, c.x > UNREACHABLE, d.x > UNREACHABLE);
    float sum = w.x + w.y + w.z + w.w;
    if (sum <= 0.0) return vec4(-1000.0, shore, a.z, a.w);
    vec4 v = (a * w.x + b * w.y + c * w.z + d * w.w) / sum;
    v.y = shore;
    return v;
}

// The second field texture at a region texel, each channel blended only over samples where it is
// defined (waterline and far field where water reaches, the crossing pair where it exists).
vec4 fields2At(vec2 texel) {
    float perSample = 16.0 / Region.y;
    vec2 s = (texel + 0.5) / perSample - 0.5;
    int n = int(Region.z);
    ivec2 i = clamp(ivec2(floor(s)), ivec2(0), ivec2(n - 2));
    vec2 f = clamp(s - vec2(i), 0.0, 1.0);
    vec4 w = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y), (1.0 - f.x) * f.y, f.x * f.y);
    vec4 a = texelFetch(Field2Sampler, i, 0), b = texelFetch(Field2Sampler, i + ivec2(1, 0), 0);
    vec4 c = texelFetch(Field2Sampler, i + ivec2(0, 1), 0), d = texelFetch(Field2Sampler, i + ivec2(1, 1), 0);
    vec4 reach = vec4(texelFetch(FieldSampler, i, 0).x > UNREACHABLE, texelFetch(FieldSampler, i + ivec2(1, 0), 0).x > UNREACHABLE,
                      texelFetch(FieldSampler, i + ivec2(0, 1), 0).x > UNREACHABLE, texelFetch(FieldSampler, i + ivec2(1, 1), 0).x > UNREACHABLE);
    vec4 out4 = vec4(-1000.0);
    vec4 w1 = w * reach;
    float s1 = w1.x + w1.y + w1.z + w1.w;
    if (s1 > 0.0) out4.xy = (a.xy * w1.x + b.xy * w1.y + c.xy * w1.z + d.xy * w1.w) / s1;
    vec4 w2 = w * vec4(a.z > UNREACHABLE && a.w > UNREACHABLE, b.z > UNREACHABLE && b.w > UNREACHABLE,
                       c.z > UNREACHABLE && c.w > UNREACHABLE, d.z > UNREACHABLE && d.w > UNREACHABLE);
    float s2 = w2.x + w2.y + w2.z + w2.w;
    if (s2 > 0.0) out4.zw = (a.zw * w2.x + b.zw * w2.y + c.zw * w2.z + d.zw * w2.w) / s2;
    return out4;
}

// How much of a wave is left after travelling past where it would normally break (a waterline
// below 0: round an island, up an inlet).
float travelLeft(float waterline) {
    return waterline >= 0.0 ? 1.0 : max(0.0, 1.0 + waterline / Swell2.x);
}

// Smooth value noise on an integer lattice: base + floor(p) are the lattice cells (whole numbers
// passed separately so the shader never handles large world coordinates).
float vnoise(ivec2 base, vec2 p, int seed) {
    vec2 fl = floor(p), f = p - fl;
    f = f * f * (3.0 - 2.0 * f);
    ivec2 i = base + ivec2(fl);
    float a = hash3(i.x, i.y, seed), b = hash3(i.x + 1, i.y, seed), c = hash3(i.x, i.y + 1, seed), d = hash3(i.x + 1, i.y + 1, seed);
    return a + (b - a) * f.x + (c - a) * f.y + (a - b - c + d) * f.x * f.y;
}

float levelAt(vec2 texel) {
    float n = Region.x;
    if (texel.x < 0.0 || texel.y < 0.0 || texel.x >= n || texel.y >= n) return 0.0;
    float perSample = 16.0 / Region.y;
    float L = texelFetch(FieldSampler, ivec2(floor((texel + 0.5) / perSample)), 0).w;
    return L > 7.5 ? 0.0 : L;
}

float arrivalAt(vec2 texel) {
    return fieldsAt(texel).x;
}

vec4 over(vec4 src, vec4 dst) {
    float a = src.a + dst.a * (1.0 - src.a);
    if (a <= 0.0) return vec4(0.0);
    return vec4((src.rgb * src.a + dst.rgb * dst.a * (1.0 - src.a)) / a, a);
}

vec3 foamBright() { return mix(vec3(250.0, 252.0, 250.0) / 255.0, Water.rgb, Motion2.y); }
vec3 foamShadow() { return mix(vec3(236.0, 242.0, 240.0) / 255.0, Water.rgb, Motion2.y + 0.26) * 0.94; }
// Vanilla water at a world texel, tinted like vanilla tints it; alpha is the texture's own.
vec4 waterStill(ivec2 wt) {
    vec4 t = texelFetch(AtlasSampler, ivec2(Sprites.xy) + ivec2(wt.x & 15, wt.y & 15), 0);
    return vec4(t.rgb * Water.rgb, t.a);
}

// ---- Rivers -----------------------------------------------------------------------------------------

// The river fields at a region texel, blended between samples.
vec4 riverAt(vec2 texel) {
    float perSample = 16.0 / Region.y;
    vec2 s = (texel + 0.5) / perSample - 0.5;
    int n = int(Region.z);
    ivec2 i = clamp(ivec2(floor(s)), ivec2(0), ivec2(n - 2));
    vec2 f = clamp(s - vec2(i), 0.0, 1.0);
    vec4 a = texelFetch(RiverSampler, i, 0), b = texelFetch(RiverSampler, i + ivec2(1, 0), 0);
    vec4 c = texelFetch(RiverSampler, i + ivec2(0, 1), 0), d = texelFetch(RiverSampler, i + ivec2(1, 1), 0);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// Whether a block (region block coordinates) is water, or with `river` river water. Beyond the
// region counts as water and not river, so its edge makes no false banks.
bool blockIs(ivec2 bk, bool river) {
    int blocks = int(Region.x) / 16;
    if (bk.x < 0 || bk.y < 0 || bk.x >= blocks || bk.y >= blocks) return !river;
    int per = int(Region.y);
    ivec2 smp = bk * per + per / 2;
    bool wet = texelFetch(FieldSampler, smp, 0).w > 7.5;
    return river ? wet && texelFetch(RiverSampler, smp, 0).z > 0.5 : wet;
}

// Texels from a texel's centre to the nearest block beside it that is land (or, with `river`, river
// water); 99 if there is none within a block.
float distanceToBlock(vec2 texel, bool river) {
    ivec2 b = ivec2(floor(texel / 16.0));
    vec2 l = texel - vec2(b) * 16.0 + 0.5;
    float best = 99.0;
    for (int dz = -1; dz <= 1; dz++) {
        for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dz == 0) continue;
            ivec2 nb = b + ivec2(dx, dz);
            if (river ? !blockIs(nb, true) : blockIs(nb, false)) continue;
            float ex = dx < 0 ? l.x : dx > 0 ? 16.0 - l.x : 0.0;
            float ez = dz < 0 ? l.y : dz > 0 ? 16.0 - l.y : 0.0;
            best = min(best, length(vec2(ex, ez)));
        }
    }
    return best;
}

// Still water's fetch at a region texel for the wind now, in texels, between the two nearest of
// the 16 directions.
float fetchAt(vec2 texel) {
    ivec2 smp = ivec2(floor((texel + 0.5) / (16.0 / Region.y)));
    int i0 = int(floor(Wind.w)) % 16, i1 = (i0 + 1) % 16;
    float a = texelFetch(FetchSampler, ivec2(smp.x * 4 + i0 / 4, smp.y), 0)[i0 % 4];
    float b = texelFetch(FetchSampler, ivec2(smp.x * 4 + i1 / 4, smp.y), 0)[i1 % 4];
    return mix(a, b, fract(Wind.w)) * 384.0;
}

// Rain rings at a world texel position: each 5x5 cell has at most one drop per 0.9 s, at a hashed
// spot and moment; its ring spreads for 0.45 s. How strong the ring is here (0 for none).
float rainRingAt(vec2 wp) {
    if (RainW.x <= 0.0 || RainW.y <= 0.0) return 0.0;
    float T = Origin.z, ring = 0.0;
    ivec2 c0 = ivec2(floor(wp / 5.0));
    for (int dz = -1; dz <= 1; dz++) {
        for (int dx = -1; dx <= 1; dx++) {
            ivec2 c = c0 + ivec2(dx, dz);
            float off = hash3(c.x, c.y, 91) * 0.9;
            int k = int(floor((T + off) / 0.9));
            float age = T - (float(k) * 0.9 - off);
            if (age > 0.45 || hash3(c.x, c.y, 97 + (k & 1023)) > RainW.y * 0.55 * RainW.x) continue;
            vec2 drop = vec2(c) * 5.0 + vec2(hash3(c.x, c.y, 101 + (k & 1023)), hash3(c.x, c.y, 103 + (k & 1023))) * 5.0;
            if (abs(length(wp - drop) - age * 9.0) < 0.6) ring = max(ring, 1.0 - age / 0.45);
        }
    }
    return ring;
}

// The wind's ripple phase at a world texel position (0..1, crests near 0), for a fetch fraction.
float ripplePhase(vec2 wp, float fr, ivec2 wt) {
    float lam = 18.0 + 22.0 * fr;
    return fract(dot(wp, Wind.xy) / lam - Origin.z / Still.y + (noiseAt(0, wt) - 0.5) * 0.35);
}

// A river at a texel, all of it under the waves' own foam. Whatever this layer covers hides vanilla's
// water there (the quads are drawn before translucent terrain and write depth), so it draws the
// water itself, with vanilla's own texture and opacity, and every effect is a change to that water:
// shallows clearer, lighter and a little greener, the deep a touch darker toward the middle; flow lines (dots carried by the current, each drawn as a short streak
// whose bright head leads; two copies half a cycle apart, each starting over once a cycle,
// crossfaded by dithering so the restart never shows); the edge lapping in slow pulses; foam piled
// on rocks' upstream faces, their wakes and foam trails. On land beside a river, the wet bank.
vec4 riverLayer(vec2 texel, ivec2 wt, bool isWater, float bay) {
    float T = Origin.z;
    vec2 wp = RainW.zw + texel + 0.5;
    if (!isWater) {
        if (abs(levelAt(texel)) > 0.5) return vec4(0.0);
        float dr = distanceToBlock(texel, true);
        // On a shore the wind blows onto, each ripple laps a little way up as it arrives.
        if (dr < 10.0 && Still.w > 0.0 && Still.x > 0.0 && Wind.z > 0.0) {
            float fr = min(1.0, fetchAt(texel) / Still.z), reach = Still.x * Wind.z * fr * Still.w;
            if (reach > 0.3) {
                float run = reach * max(0.0, sin(ripplePhase(wp, fr, wt) * 6.2832));
                if (dr < run) {
                    if (run - dr < 1.1) return vec4(run > reach * 0.6 ? foamBright() : foamShadow(), 1.0);
                    vec4 sw = waterStill(wt);
                    return vec4(sw.rgb, sw.a * Look.z * 0.8);
                }
                if (dr < reach + 1.0) return vec4(0.0, 0.0, 0.0, Look.y * 0.9);
            }
        }
        return dr < 2.5 && (dr < 1.5 || bay < 0.5) ? vec4(0.0, 0.0, 0.0, Look.y * 0.8) : vec4(0.0);
    }
    vec4 rv = riverAt(texel);
    if (rv.z <= 0.0 || (rv.z < 1.0 && rv.z <= bay)) {
        // Plain water (the sea): only rain rings, drawn as lighter water (this layer hides vanilla's
        // water wherever it draws).
        float ring = rainRingAt(wp);
        if (ring > bay * 0.8) { vec4 sw = waterStill(wt); return vec4(min(sw.rgb * 1.22 + 20.0 / 255.0, vec3(1.0)), sw.a * Look.z); }
        return vec4(0.0);
    }
    vec4 decor = texelFetch(RiverDecorSampler, ivec2(texel), 0);
    float bank = distanceToBlock(texel, false), sh = rv.w;
    float shq = sh + (bay - 0.5) * 0.5, stp = shq > 0.66 ? 1.0 : shq > 0.33 ? 0.5 : 0.0;
    vec4 w = waterStill(wt);
    vec3 col = w.rgb;
    float alpha = w.a * Look.z;
    if (stp > 0.0) {
        float a = River.z * stp, k = 1.0 + 0.3 * a;
        col = col * vec3(0.94 * k, 1.1 * k, k) + vec3(12.0, 20.0, 8.0) / 255.0 * a;
        alpha *= 1.0 - 0.4 * a; // clearer: the bed shows through more
    } else {
        col *= 1.0 - 0.08 * min(1.0, bank / 32.0);
    }
    // Wind ripples on still water: straight crests at right angles to the wind moving downwind,
    // bigger and longer the more open water lies upwind; gusts (patches moving with the wind) carry
    // them, and a strong wind over a long fetch tips them white.
    if (decor.a > 0.5 / 255.0 && Still.x > 0.0 && Wind.z > 0.0) {
        float fr = min(1.0, fetchAt(texel) / Still.z);
        float amp = Still.x * Wind.z * fr * (decor.a * 255.0 - 1.0) / 254.0;
        if (amp > 0.02) {
            float fp = ripplePhase(wp, fr, wt);
            float gust = vnoise(ivec2(0), (wp - Wind.xy * Origin.z * 14.0) / 40.0, 77);
            if (fp < 0.1 + 0.08 * amp && amp * (0.4 + gust) > bay * 0.9) {
                if (amp > 0.65 && fp < 0.035 && hash3(int(floor(wp.x / 2.0)), int(floor(wp.y / 2.0)), int(floor(Origin.z * 3.0))) < (amp - 0.65) * 2.0)
                    return vec4(foamBright(), 1.0);
                col = col * 1.2 + 16.0 / 255.0;
            }
        }
    }
    float sp = length(rv.xy), v = 0.0;
    if (sp > 0.5 && River.x > 0.0) {
        float per = 2.2, ph = fract(T / per), wA = 1.0 - abs(2.0 * ph - 1.0);
        bool useA = wA > (BAYER[((wt.y >> 2) & 3) * 4 + ((wt.x >> 2) & 3)] + 0.5) / 16.0;
        float tt = useA ? ph : fract(ph + 0.5);
        int seed = useA ? 11 : 29;
        vec2 q = vec2(wt) - rv.xy * tt * per, dir = rv.xy / sp;
        int len = max(2, int(floor(River.y * (1.0 - 0.45 * sh) + 0.5)));
        float dens = River.x * (0.01 + 0.018 * sh) + decor.b * 0.1;
        for (int j = 0; j < 24; j++) {
            if (j >= len) break;
            ivec2 sq = ivec2(floor(q + dir * float(j) + 0.5));
            if (hash3(sq.x & 255, sq.y & 255, seed) < dens) { v = 1.0 - float(j) / float(len); break; }
        }
    }
    if (v > 0.0) {
        // Foam carried down from a rock, and sparkle on the shallows, as white.
        if ((decor.b > 0.2 && v > 0.35) || (sh > 0.6 && v > 0.8 && hash3(wt.x, wt.y, 71) < 0.35)) return vec4(foamBright(), 1.0);
        col = v > 0.6 ? col * 1.34 + 30.0 / 255.0 : col * 1.16 + 12.0 / 255.0;
        alpha = max(alpha, w.a * Look.z);
    }
    if (bank < 1.6 && River.w > 0.0) {
        float lap = 0.5 + 0.5 * sin(T * 1.7 + hash3(wt.x >> 2, wt.y >> 2, 5) * 6.283);
        if (lap > 1.0 - River.w * 0.45) col = col * 1.18 + 16.0 / 255.0;
    }
    if (decor.g > 0.0 && decor.g * 1.3 > hash3(wt.x >> 1, wt.y >> 1, int(floor(T * 6.0)))) {
        return vec4(decor.g > 0.5 ? foamBright() : foamShadow(), 1.0);
    }
    if (decor.r > 0.0 && decor.r > 0.25 + 0.35 * bay + 0.15 * sin(T * 5.0 + float(wt.x + wt.y) * 0.4)) {
        col = col * 1.2 + 24.0 / 255.0; // the wake shimmers
    }
    // Rain rings, where the water is calm enough to show them.
    float ring = rainRingAt(wp) * (1.0 - min(1.0, sp / 20.0));
    if (ring > bay * 0.8) col = col * 1.22 + 20.0 / 255.0;
    return vec4(min(col, vec3(1.0)), alpha);
}

// Vanilla flowing water on a face (it already scrolls downward), column u, row down from the top.
vec4 waterFlow(int u, int row) {
    vec4 t = texelFetch(AtlasSampler, ivec2(Sprites.zw) + ivec2(u & 15, row & 15), 0);
    return vec4(t.rgb * Water.rgb, t.a);
}

// One wave as it is at one texel (seconds; distances in texels).
struct Wave {
    float t0, amp, Ta, Tu, tR0, tB0, tEnd;
    float att;  // how much is left after travelling round to get here
    float Fa;   // where the approaching front is now, in arrival units (it keeps going through all water)
    float prog; // 0..1 through the approach
    ivec2 front, lace, lip;
};

// Wave i at a region texel whose water came ashore at arrival `waterline`. Each wave carries two
// smooth fields over the world: how late it reaches each stretch of coast (0..peel seconds) and how
// big it is there, so it breaks along the beach instead of everywhere at once. Its run-up starts
// when the front reaches this texel's own waterline; a waterline further out than the approach
// distance is reached as soon as the wave appears.
Wave waveHere(int i, vec2 texel, float waterline) {
    vec4 A = Waves[2 * i], B = Waves[2 * i + 1];
    Wave w;
    w.front = ivec2(A.zw);
    w.lace = ivec2(B.xy);
    w.lip = ivec2(B.zw);
    vec2 pc = SwellOrigin.zw + texel / (16.0 * max(1.0, Swell.z));
    ivec2 base = ivec2(SwellOrigin.xy);
    float delay = Swell.y * vnoise(base, pc, 7001 + w.front.x * 131 + w.front.y);
    float size = max(0.15, 1.0 + Swell.w * (2.0 * vnoise(base, pc, 7013 + w.lace.x * 131 + w.lace.y) - 1.0));
    w.att = travelLeft(waterline);
    w.t0 = A.x + delay;
    w.amp = A.y * size * w.att;
    w.Ta = Motion.x / Motion.z;
    w.Tu = 2.0 * Motion.y * w.amp / Motion.z;
    w.tR0 = w.t0 + max(0.0, Motion.x - waterline) / Motion.z;
    w.tB0 = w.tR0 + w.Tu + Motion.w;
    w.tEnd = w.tB0 + Motion2.x;
    w.prog = clamp((Origin.z - w.t0) / w.Ta, 0.0, 1.0);
    w.Fa = Motion.x - Motion.z * (Origin.z - w.t0);
    return w;
}

// The same wave scaled down by k (a crossing front fading as it falls behind the first).
Wave weaker(Wave w, float k) {
    w.amp *= k;
    w.Tu *= k;
    w.tB0 = w.tR0 + w.Tu + Motion.w;
    w.tEnd = w.tB0 + Motion2.x;
    return w;
}

// Where a texel's water came ashore: at sea the texel itself.
float waterlineAt(vec2 texel, float dp) {
    float perSample = 16.0 / Region.y;
    float L = texelFetch(FieldSampler, ivec2(floor((texel + 0.5) / perSample)), 0).w;
    return L > 8.5 ? dp : fields2At(texel).x;
}

// Closed-form history of a wave at a texel: when its front reached the texel, when the lip
// stopped laying lace there and when the backwash uncovered it. False if it never gets there.
bool waveTimes(Wave w, ivec2 wt, float dp, float e, out float Re, out float tCross, out float tLeave, out float tUnc, out bool laid) {
    float jag = Shape.y;
    float jv = (noiseAt(0, wt + w.front) - 0.5) * 2.0 * jag;
    Re = Motion.y * w.amp * e;
    float TuE = w.Tu * min(1.0, e);
    tCross = 0.0;
    tLeave = 0.0;
    tUnc = 0.0;
    laid = false;
    if (Re <= 0.0) return false; // nothing left of the wave here
    float uR = (jv - dp) / Re;
    if (uR > 1.0) return false;
    tCross = uR <= 0.0 ? w.tR0 : w.tR0 + TuE * (1.0 - sqrt(1.0 - uR));
    float uL = (jv - dp + Shape.x + 3.0) / Re;
    laid = uL > 0.0;
    tLeave = uL <= 1.0 ? w.tR0 + TuE * (1.0 - sqrt(1.0 - max(0.0, uL))) : w.tB0;
    float k2 = (dp + Re - 0.7 * jv) / (Re + 6.0);
    tUnc = k2 >= 1.0 ? w.tEnd : w.tB0 + Motion2.x * sqrt(max(0.0, k2));
    return true;
}

// A wave's puddle in a dip, where the water cannot drain back out. It stays where the wave left it,
// spreads a few texels further while it settles, and soaks into the ground in place, in dithered
// blotches. tWet: when the puddle's water got here; tDry: when it has soaked away here; past: how
// many texels beyond the wave's own reach this is. False if the puddle never gets here.
bool puddleTimes(Wave w, ivec2 wt, float dp, float e, out float tWet, out float tDry, out float past) {
    float soak = max(0.1, Motion2.z), spread = Motion2.w;
    float jv = (noiseAt(0, wt + w.front) - 0.5) * 2.0 * Shape.y;
    past = (jv - Motion.y * w.amp * e) - dp;
    tWet = w.tB0;
    tDry = w.tB0;
    if (past > spread || dp <= UNREACHABLE) return false;
    // Spreading is quick at first and has settled after 60% of the soak time.
    float sp = past > 0.0 ? past / max(0.001, spread) : 0.0;
    tWet = w.tB0 + 0.6 * soak * sp * sp;
    // Soaking: blotches from smooth noise, broken up by the dither; the thin rim goes a bit sooner.
    float rank = 0.55 * noiseAt(1, wt + w.lace) + 0.3 * bayerAt(wt) + 0.15 * clamp(1.0 - (past + spread) / 24.0, 0.0, 1.0);
    tDry = max(tWet + 0.15 * soak, w.tB0 + soak * (0.3 + 0.7 * rank));
    return true;
}

// ---- Block tops ----------------------------------------------------------------------------------

vec4 bakeTop(vec2 texel) {
    ivec2 wt = ivec2(Origin.xy) + ivec2(texel);
    float T = Origin.z;
    vec4 fields = fieldsAt(texel);
    float dp = fields.x, sd = fields.y, expo = fields.z;
    float perSample = 16.0 / Region.y;
    ivec2 smp = ivec2(floor((texel + 0.5) / perSample));
    float L = texelFetch(FieldSampler, smp, 0).w;
    bool isWater = L > 7.5, isSea = L > 8.5;
    float level = isWater ? 0.0 : L;
    float bay = bayerAt(wt);
    vec4 dipInfo = texelFetch(DipSampler, smp, 0);
    bool isle = dipInfo.z > 0.5;

    // Where this texel's water came ashore, and where it is relative to that: 0 at sea, negative up
    // the beach. A front sweeps through all the water, so the sea anywhere it still has some size
    // left takes part; land only within the run-up's reach of its own waterline.
    vec4 f2 = fields2At(texel);
    float dwl = isSea ? dp : f2.x;
    float xl = dp - dwl;
    float lipW = Shape.x, jag = Shape.y, offshore = Shape.z, thr = 1.0 - Shape.w;
    float approach = Motion.x, runup = Motion.y;
    float lo = -(runup * (1.0 + Swell.w) + jag + 10.0), hi = approach + jag + lipW * 2.0 + 10.0;
    vec4 river = riverLayer(texel, wt, isWater, bay);
    if (dp <= UNREACHABLE || dp > hi || xl < lo || travelLeft(dwl) <= 0.0) return river;
    float e = 1.0 + (expo - 1.0) * Look.w;

    // Out at sea a front follows the far field, nearly straight; it bends to the coast's real shape
    // as it comes in. The crossing front is shifted the same way so the two meet at the seam.
    float dA = dp > 0.0 ? dp + (f2.y - dp) * min(1.0, dp / approach) * Swell.x : dp;
    float d2v = f2.z, dA2 = d2v > UNREACHABLE ? d2v + (dA - dp) : -1000.0;
    float wrapFade = dA2 > UNREACHABLE ? Swell2.y * (1.0 - (dp - d2v) / Swell2.z) : 0.0;
    // The crossing front over land: from its own waterline, fading as it falls behind the first.
    float xl2 = !isSea && d2v > UNREACHABLE && f2.w > UNREACHABLE ? d2v - f2.w : 1e9;
    float fade2 = xl2 < 1e8 ? Swell2.y * (1.0 - (dp - d2v) / Swell2.z) : 0.0;

    // Beach-level texels within 2 of a raised block: foam piles here while a wave pushes.
    bool nearWall = false;
    if (abs(level) < 0.5) {
        for (int k = 1; k <= 2; k++) {
            nearWall = nearWall || levelAt(texel + vec2(k, 0)) > 0.5 || levelAt(texel - vec2(k, 0)) > 0.5
                || levelAt(texel + vec2(0, k)) > 0.5 || levelAt(texel - vec2(0, k)) > 0.5;
        }
    }

    // In a dip, water that ran in cannot drain back uphill over the rim. Its backwash is the puddle
    // (see puddleTimes), which soaks away where it lies.
    bool isDip = !isWater && abs(level + 1.0) < 0.5 && dipInfo.y > 0.5 && dipInfo.x > UNREACHABLE;
    float soak = max(0.1, Motion2.z);

    bool cov = false, edge = false, swell = false, pushing = false, covPuddle = false, washing = false;
    float covF = 0.0, foam = 0.0, lastUnc = -1e9, puddleTh = 0.0, th2 = 0.0;
    int lip = 0;
    int waveCount = int(Origin.w);
    for (int i = 0; i < waveCount; i++) {
        // The second run-up first: waveHere returns a fresh struct, but keep the two apart anyway.
        if (fade2 > 0.0) {
            Wave w2 = waveHere(i, texel, f2.w);
            if (T >= w2.tR0 && T < w2.tEnd) {
                float Re2 = runup * w2.amp * e * fade2, jv2 = (noiseAt(0, wt + w2.front) - 0.5) * 2.0 * jag;
                float TuE2 = w2.Tu * min(1.0, e), F2;
                bool backwash2 = T >= w2.tB0;
                if (!backwash2) {
                    float kk = min(1.0, (T - w2.tR0) / max(0.001, TuE2));
                    F2 = -Re2 * (1.0 - (1.0 - kk) * (1.0 - kk)) + jv2;
                } else {
                    float k = (T - w2.tB0) / Motion2.x;
                    F2 = -Re2 + (Re2 + 6.0) * k * k + jv2 * 0.7;
                }
                if (xl2 >= F2) {
                    th2 = max(th2, (xl2 - F2) / max(1.0, -F2));
                    float lw2 = lipW * (0.6 + 0.8 * noiseAt(1, wt + w2.lip));
                    if (!backwash2 && xl2 < F2 + lw2 && bay < fade2 + 0.2) lip = max(lip, xl2 - F2 < lw2 * 0.55 ? 2 : 1);
                    if (backwash2 && xl2 - F2 < 1.5) edge = true;
                }
            }
            float Rx, tCx, tLx, tUx;
            bool laidx;
            if (waveTimes(w2, wt, xl2, e, Rx, tCx, tLx, tUx, laidx) && T >= tUx) lastUnc = max(lastUnc, tUx);
        }

        Wave w = waveHere(i, texel, dwl);
        if (T < w.t0) continue;
        float jv = (noiseAt(0, wt + w.front) - 0.5) * 2.0 * jag;
        float lw = lipW * (0.6 + 0.8 * noiseAt(1, wt + w.lip));
        float Re = runup * w.amp * e;

        if (isSea || isle) {
            // At sea (and over land too small to matter): the approaching foam line, sweeping on
            // through all the water, sparse far out and fading as it travels.
            float F = w.Fa + jv;
            float dens = (offshore + (1.0 - offshore) * pow(w.prog, 1.5)) * e * w.att;
            if (dA >= F) {
                if (dA < F + lw) {
                    if (bay < dens) lip = max(lip, dA - F < lw * 0.55 ? 2 : 1);
                } else if (dA < F + lw + 5.0 && isWater) swell = true;
            }
            // The second front crossing it: a thinner line, fading as it falls behind.
            if (wrapFade > 0.0 && dA2 >= F && dA2 < F + lw * 0.8 && bay < dens * wrapFade) lip = max(lip, dA2 - F < lw * 0.4 ? 2 : 1);
        }
        if (T < w.tR0) continue;

        // Where the wave is now, on the beach (measured from this texel's own waterline). A
        // sheltered spot's run-up is shorter and ends proportionally sooner, so the front leaves
        // the shore at the speed it arrived everywhere.
        float TuE = w.Tu * min(1.0, e);
        if (!isSea && T < w.tEnd) {
            float F;
            int ph;
            float lipScale = 1.0;
            if (T < w.tR0 + w.Tu) {
                float kk = min(1.0, (T - w.tR0) / max(0.001, TuE));
                F = -Re * (1.0 - (1.0 - kk) * (1.0 - kk)) + jv;
                ph = 1;
            } else if (T < w.tB0) {
                F = -Re + jv;
                ph = 2;
                lipScale = 1.0 - (T - w.tR0 - w.Tu) / max(0.001, Motion.w);
            } else {
                float k = (T - w.tB0) / Motion2.x;
                F = -Re + (Re + 6.0) * k * k + jv * 0.7;
                ph = 3;
            }
            if (xl >= F && !(isDip && ph == 3)) {
                cov = true;
                covF = F;
                if (ph < 3) {
                    pushing = true;
                    float lw2 = lw * lipScale;
                    if (xl < F + lw2) lip = max(lip, xl - F < lw2 * 0.55 ? 2 : 1);
                } else if (xl - F < 1.5 && !isWater) edge = true;
            }
        }

        float ReT, tCross, tLeave, tUnc;
        bool laid;
        bool reached = waveTimes(w, wt, xl, e, ReT, tCross, tLeave, tUnc, laid);
        if (isDip) {
            float tWet, tDry, past;
            if (puddleTimes(w, wt, xl, e, tWet, tDry, past)) {
                tUnc = tDry; // lace laid here fades under the puddle until it has soaked away
                if (T >= tWet && T < tDry) {
                    covPuddle = true;
                    // Thins in dithered steps before it goes; the spread past the reach stays thin.
                    puddleTh = max(puddleTh, clamp((tDry - T) / (0.35 * soak), 0.0, 1.0) * (past > 0.0 ? 0.6 : 1.0));
                    // A pale rim on the spreading edge while it still moves.
                    float spreadNow = Motion2.w * sqrt(clamp((T - w.tB0) / (0.6 * soak), 0.0, 1.0));
                    if (past > 0.0 && spreadNow < Motion2.w && spreadNow - past < 1.5) edge = true;
                }
                if (T >= tDry) lastUnc = max(lastUnc, tDry);
            }
        }
        if (!reached || T < tCross) continue;
        if (T >= tUnc) lastUnc = max(lastUnc, tUnc);
        // The wash up a wall carries the foam line with it (see bakeFace): no pile at the foot
        // until it has nearly come back down.
        bool washUp = nearWall && T < tCross + 0.6;
        washing = washing || washUp;
        float lv = 0.0, tDep = 0.0;
        // At sea only the water right by the shore carries lace.
        if ((isSea ? sd < 10.0 : true) && laid) {
            ivec2 s = wt;
            if (Lace.w > 0.0 && abs(level) < 0.5 && T > w.tB0) {
                // The backwash drags lace seaward: read the pattern from further up the beach.
                vec2 g = vec2(arrivalAt(texel + vec2(1.0, 0.0)) - arrivalAt(texel - vec2(1.0, 0.0)),
                              arrivalAt(texel + vec2(0.0, 1.0)) - arrivalAt(texel - vec2(0.0, 1.0)));
                float gradLen = length(g);
                if (gradLen > 0.0 && gradLen < 100.0) {
                    float gg = 0.6 + 0.8 * clamp(1.0 + xl / max(1.0, ReT), 0.0, 1.0);
                    float D = Lace.w * gg * (min(T, tUnc) - w.tB0);
                    s = wt - ivec2(round(g / gradLen * D));
                }
            }
            if (isDip) {
                // Unfold the step into the dip: its face reads the lace pattern 1..16 texels past the
                // edge, so the floor reads it 15 further downstream (down the arrival field, the way
                // the water came in) and the pattern runs on from the face without a break.
                vec2 dipGrad = vec2(arrivalAt(texel + vec2(1.0, 0.0)) - arrivalAt(texel - vec2(1.0, 0.0)),
                                    arrivalAt(texel + vec2(0.0, 1.0)) - arrivalAt(texel - vec2(0.0, 1.0)));
                float dipGradLen = length(dipGrad);
                if (dipGradLen > 0.0 && dipGradLen < 100.0) s = wt - ivec2(round(dipGrad / dipGradLen * 15.0));
            }
            lv = noiseAt(2, s + w.lace);
            tDep = tLeave;
        }
        // Foam piles at a wall's foot for as long as the wave pushes against it.
        if (nearWall && !washUp && 0.9 * Terrain.w > lv) {
            lv = 0.9 * Terrain.w;
            tDep = w.tB0;
        }
        if (lv > foam) {
            // Fades slowly while the sheet covers it, faster once it is out in the open.
            float t0d = min(T, tDep), k;
            if (isWater) k = (T - t0d) * 0.6931 / Lace.z;
            else {
                float tu = max(t0d, tUnc);
                k = (min(T, tu) - t0d) * 0.6931 / Lace.x + max(0.0, T - tu) * 0.6931 / Lace.y;
            }
            foam = max(foam, lv * exp(-k));
        }
    }

    bool pile = false;
    if (nearWall && pushing && !washing) {
        float pressure = clamp((xl - covF) / 6.0, 0.0, 1.0);
        pile = bay < pressure * Terrain.w;
    }

    vec4 color = vec4(0.0);
    if (!isWater) {
        // Wet sand: a dark film, so it darkens whatever block is underneath.
        float wv = (cov || covPuddle || th2 > 0.0 || (abs(level) < 0.5 && sd > -2.5)) ? 1.0 : max(0.0, 1.0 - (T - lastUnc) / Look.x);
        if (wv > 0.0) {
            float q = wv + (bay - 0.5) * 0.34;
            color = over(vec4(0.0, 0.0, 0.0, Look.y * (q > 0.66 ? 1.0 : q > 0.2 ? 0.5 : 0.0)), color);
        }
        if (cov || covPuddle || th2 > 0.0) {
            // The sheet: shallow water, nearly opaque where it leaves the sea, thinning in dithered
            // steps toward its edge. A puddle thins as it soaks away.
            float th = max(max(cov ? (xl - covF) / max(1.0, -covF) : 0.0, covPuddle ? puddleTh : 0.0), th2);
            float q = th + (bay - 0.5) * 0.3;
            vec4 w = waterStill(wt);
            color = over(vec4(w.rgb, w.a * Look.z * (q > 0.6 ? 1.0 : q > 0.25 ? 0.7 : 0.45)), color);
        }
        if (edge) color = over(vec4(mix(Water.rgb, vec3(1.0), 0.62), 0.6), color);
    } else if (swell) {
        color = over(vec4(1.0, 1.0, 1.0, 0.08), color);
    }
    float fv = foam + (bay - 0.5) * 0.1;
    if (foam > 0.004 && fv > thr) color = vec4(fv > thr + (1.0 - thr) * 0.45 ? foamBright() : foamShadow(), 1.0);
    if (lip > 0 || pile) color = vec4(lip == 2 || pile ? foamBright() : foamShadow(), 1.0);
    return over(color, river);
}

// ---- Faces -----------------------------------------------------------------------------------------

// Seconds for falling water to drop r texels below the edge it left at 4 texels a second.
float fallTime(float r) {
    return (-4.0 + sqrt(16.0 + 2.0 * GRAV * r)) / GRAV;
}

vec4 bakeFace(vec2 pixel) {
    int cols = int(Mode.z) / 16;
    int tx = int(pixel.x) / 16, ty = int(pixel.y) / TILE_H;
    int face = ty * cols + tx;
    if (face >= int(Mode.y)) return vec4(0.0);
    int u = int(pixel.x) - tx * 16, row = int(pixel.y) - ty * TILE_H;
    vec4 d0 = texelFetch(FaceSampler, ivec2((face % 128) * 2, face / 128), 0);
    vec4 d1 = texelFetch(FaceSampler, ivec2((face % 128) * 2 + 1, face / 128), 0);
    int kind = int(d1.z + 0.5), h = int(d1.w + 0.5);
    bool edgeKind = kind == 3 || kind == 4, pourKind = kind == 1 || kind == 5;
    if (row >= h) return vec4(0.0);

    vec2 texel = d0.xy + d0.zw * float(u);
    ivec2 wt = ivec2(Origin.xy) + ivec2(texel);
    float T = Origin.z;
    vec4 fields = fieldsAt(texel);
    float dp = fields.x, e = 1.0 + (fields.z - 1.0) * Look.w;
    float dwE = waterlineAt(texel, dp), xlE = dp - dwE;
    float bay = bayerAt(ivec2(u, row));
    float fromBottom = float(h - 1 - row) / max(1.0, float(h - 1)); // 0 at the bottom, 1 at the top
    float flow = 0.0, age = 1e9, faceFoam = 0.0;
    bool streak = false, faceLip = false;
    int waveCount = int(Origin.w);
    // A ledge or step continues the beach over its edge: row r reads the texel r + 1 past the edge,
    // and its front times blend from the edge texel (top row) to the first texel beyond the face
    // (bottom row: the dip floor, or the sea below a ledge), so the face joins both without a break.
    ivec2 past = wt + ivec2(d1.xy * float(row + 1));
    vec2 lowTexel = texel + d1.xy;
    vec4 lowFields = fieldsAt(lowTexel);
    bool lowOk = lowFields.x > UNREACHABLE;
    ivec2 wtLow = ivec2(Origin.xy) + ivec2(lowTexel);
    float eLow = 1.0 + (lowFields.z - 1.0) * Look.w;
    float dwLow = lowOk ? waterlineAt(lowTexel, lowFields.x) : 0.0, xlLow = lowFields.x - dwLow;
    float rowFrac = float(row + 1) / float(max(1, h));
    // A drop off a dip's floor keeps pouring while the puddle above reaches its edge.
    vec4 dipInfo = texelFetch(DipSampler, ivec2(floor((texel + 0.5) / (16.0 / Region.y))), 0);
    bool edgeDip = kind == 1 && dipInfo.y > 0.5 && dipInfo.x > UNREACHABLE;
    // The crossing front, where there is one (a narrow spit's sides, the lee of an island): it runs
    // over these faces too, weaker the further it has fallen behind, from its own waterline.
    vec4 f2E = fields2At(texel), f2L = lowOk ? fields2At(lowTexel) : vec4(-1000.0);
    float fadeE = f2E.z > UNREACHABLE && f2E.w > UNREACHABLE ? Swell2.y * (1.0 - (dp - f2E.z) / Swell2.z) : 0.0;
    float fadeL = f2L.z > UNREACHABLE && f2L.w > UNREACHABLE ? Swell2.y * (1.0 - (lowFields.x - f2L.z) / Swell2.z) : 0.0;

    for (int j = 0; j < 2 * waveCount; j++) {
        // Each wave twice: its first front, then the crossing front.
        int i = j >> 1;
        bool second = (j & 1) == 1;
        if (second && fadeE <= 0.0 && fadeL <= 0.0) continue;
        float xE = second ? f2E.z - f2E.w : xlE, xLo = second ? f2L.z - f2L.w : xlLow;
        Wave w = weaker(waveHere(i, texel, second ? f2E.w : dwE), second ? max(fadeE, 0.0) : 1.0);
        Wave wL = weaker(waveHere(i, lowTexel, second ? f2L.w : dwLow), second ? max(fadeL, 0.0) : 1.0);
        if (T < (edgeKind ? min(w.tR0, wL.tR0) : w.tR0)) continue;
        float Re, tCross, tLeave, tUnc;
        bool laid;
        bool reached = waveTimes(w, wt, xE, e, Re, tCross, tLeave, tUnc, laid);
        if (edgeKind) {
            // Ledge or step: this row's front times, between the edge's and the low side's.
            float ReL, tCrossL, tLeaveL, tUncL;
            bool laidL;
            bool reachedL = lowOk && waveTimes(wL, wtLow, xLo, eLow, ReL, tCrossL, tLeaveL, tUncL, laidL);
            if (!reached && !reachedL) continue;
            float tC = reached && reachedL ? mix(tCross, tCrossL, rowFrac) : reached ? tCross : tCrossL;
            float tL = reached && reachedL ? mix(tLeave, tLeaveL, rowFrac) : reached ? tLeave : tLeaveL;
            // Water leaves the face when it leaves the edge above (a step) or the sea side (a ledge).
            float tU = reached ? tUnc : tUncL;
            if (T < tC) continue; // the front has not come this far down (or up) the face yet
            if (T < tU) flow = 1.0;
            else age = min(age, T - tU);
            // The lace pattern carries on over the edge, laid and fading on this row's schedule.
            if (laid || laidL) {
                float lv = noiseAt(2, past + w.lace);
                float t0d = min(T, tL), tu = max(t0d, tU);
                float k = (min(T, tu) - t0d) * 0.6931 / Lace.x + max(0.0, T - tu) * 0.6931 / Lace.y;
                faceFoam = max(faceFoam, lv * exp(-k));
            }
            // The foam lip is on this row while the front passes it.
            if (T < w.tB0 && T < tC + 0.6 * max(0.0, tL - tC)) faceLip = true;
            continue;
        }
        if (pourKind) {
            // Drop or waterfall: water pours over from the moment the front reaches the edge until
            // the wave stops pushing, or off a dip until the puddle at the edge has soaked away
            // (thinner, as it is only settling). What is at this row now left the edge fallTime(row) ago. Not every
            // column pours, and each column's streak falls its own distance.
            float pStart = tCross, pEnd = w.tB0, tWetP, tDryP, pastP;
            if (edgeDip && !second && puddleTimes(w, wt, xlE, e, tWetP, tDryP, pastP)) {
                pEnd = tDryP;
                if (!reached) pStart = tWetP;
            } else if (!reached) continue;
            // "Spill over drops" picks the columns that pour a full streak; every other column still
            // dribbles 2 to 4 texels down from the edge, so no column of the face stays bare.
            bool pours = hash3(wt.x, wt.y, 91 + w.front.x) <= min(1.0, Terrain.x * 1.6);
            float reach = pours
                ? max(2.0, Terrain.y * (0.65 + 0.7 * hash3(wt.x, wt.y, 77) + 0.6 * (hash3(wt.x, wt.y, 79 + w.front.y) - 0.5)))
                : 2.0 + 2.0 * hash3(wt.x, wt.y, 81 + w.front.y);
            float r = float(row);
            if (r >= reach) continue;
            float ft = fallTime(r), te = T - ft, fade = 1.0 - r / reach;
            if (te >= pStart && te <= pEnd) {
                flow = max(flow, te > w.tB0 ? fade * 0.75 : fade);
                if (fract(te * 5.0 + hash3(wt.x, wt.y, 83 + w.lace.x)) < 0.3) streak = true;
            }
            if (T >= pStart + ft) age = min(age, max(0.0, T - min(T, pEnd + ft)));
            continue;
        }
        if (!reached || T < tCross) continue;
        if (kind == 2) {
            // Wall: the wash runs up when a front hits the foot, then slides back down.
            float energy = sqrt(clamp((xE + Re) / Re, 0.0, 1.0));
            float Hh = max(1.0, Terrain.z * (0.25 + 0.75 * energy) * 0.6);
            float tau = T - tCross;
            float hNow = tau < 0.15 ? Hh * tau / 0.15 : Hh * max(0.0, 1.0 - (tau - 0.15) / 0.6);
            float up = float(h - 1 - row);
            if (up > Hh) continue;
            // Strictly below the wash: once it has drained (hNow = 0) no row is under water.
            if (up < hNow) flow = 1.0;
            // The foam line rides the top of the wash up the face and back down, as it would run on
            // up the beach; the ground at the foot only piles foam once the wash has come back.
            float lwF = max(1.5, 0.5 * Shape.x * (0.6 + 0.8 * noiseAt(1, wt + w.lip)));
            if (hNow > 0.5 && up < hNow && up >= hNow - lwF) faceLip = true;
            float left = tCross + 0.15 + 0.6 * (1.0 - up / Hh);
            age = min(age, max(0.0, T - left));
            // Lace left where the wash reached, the pattern carrying on from the ground into the face.
            float tLaid = tCross + 0.15 * up / Hh;
            if (T >= tLaid) {
                float lv = noiseAt(2, wt + ivec2(d1.xy * (up + 1.0)) + w.lace);
                float tu = max(tLaid, left);
                float k = (min(T, tu) - tLaid) * 0.6931 / Lace.x + max(0.0, T - tu) * 0.6931 / Lace.y;
                faceFoam = max(faceFoam, lv * exp(-k) * (1.0 - 0.5 * up / Hh));
            }
        }
    }

    if (kind == 3) age = 0.0; // a ledge right at the waterline is always wet
    vec4 color = vec4(0.0);
    // A face dries from the top down.
    float wv = age < 1e8 ? max(0.0, 1.0 - age * (0.6 + 1.2 * fromBottom) / Look.x) : 0.0;
    if (wv > 0.0 && kind != 5) {
        float q = wv + (bay - 0.5) * 0.34;
        color = over(vec4(0.0, 0.0, 0.0, Look.y * (q > 0.66 ? 1.0 : q > 0.2 ? 0.5 : 0.0)), color);
    }
    if (flow > 0.0 && kind != 5) { // a waterfall's water is vanilla's own
        float q = flow + (bay - 0.5) * 0.3;
        vec4 w = waterFlow(u, row);
        color = over(vec4(w.rgb, w.a * Look.z * (q > 0.6 ? 1.0 : q > 0.25 ? 0.6 : 0.0)), color);
    }
    if (streak && flow > bay) color = vec4(foamBright(), 1.0);
    if (kind >= 2) {
        float thr = 1.0 - Shape.w, fv = faceFoam + (bay - 0.5) * 0.1;
        if (faceFoam > 0.004 && fv > thr) color = vec4(fv > thr + (1.0 - thr) * 0.45 ? foamBright() : foamShadow(), 1.0);
        if (faceLip) color = vec4(foamBright(), 1.0);
    }
    if ((edgeKind || kind == 5) && row == 0) {
        vec4 top = texelFetch(RegionSampler, ivec2(texel), 0);
        if (top.a > 0.99) color = top; // foam is opaque; the sheet and wet sand are not
    }
    return color;
}

void main() {
    if (Mode.x < 0.5) fragColor = bakeTop(floor(texCoord * Region.x));
    else fragColor = bakeFace(floor(texCoord * Mode.zw));
}
