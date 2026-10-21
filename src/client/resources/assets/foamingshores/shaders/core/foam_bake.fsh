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
    vec4 WindA;    // wind pattern A: the way the wind blows (x, z, unit), its strength, its direction as 0..16 of the fetch's directions
    vec4 Still;    // wind ripples, time between ripples in a full wind (s), ripples full size over (texels), lapping run-up (texels)
    vec4 RainW;    // rain (0..1), rain rings, region origin in world texels (x, z)
    vec4 WindB;    // wind pattern B, as WindA
    vec4 Calm;     // pattern A's weight (0..1), glints, ruffled patches, rises
    vec4 Calm2;    // edge rings, sparkles on flow lines, water recolouring (1 on), edge lines (1..4)
    vec4 Calm3;    // bug rings, -, -, -
    vec4 Breeze;   // breeziness (0 steady wind, 1 wind only in breezes), how often, size, strength
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

// How much a world texel shows of wind pattern A rather than B (see FoamRenderer.pass): patches
// fixed in the world go over to pattern A as its weight rises and back as it falls, through a
// narrow band where the two blend. All B at weight 0 and all A at 1, so neither is seen taking up
// its new wind.
float patternA(vec2 wp) {
    return smoothstep(-0.07, 0.07, Calm.x * 1.14 - 0.07 - vnoise(ivec2(0), wp / 40.0, 131));
}

// Gusts at a world texel: patches of rougher water drifting downwind (cat's paws), slower in a
// lighter wind, slowly changing shape as they go. 0..1.
float gustAt(vec2 wp, vec4 wind) {
    float T = Origin.z, v = 1.5 + 6.0 * min(1.0, wind.z);
    vec2 g = wp - wind.xy * v * T;
    return vnoise(ivec2(0), g / 48.0, 77) * 0.75 + vnoise(ivec2(0), g / 20.0 + vec2(T / 17.0, -T / 23.0), 83) * 0.25;
}

// Breezes at a world texel: now and then a breeze comes down on still water, a ragged patch longer
// along the wind than across it that slides downwind, swells and dies away. Each 32x32 block cell
// (of a lattice drifting with the wind, faster than the gusts: about a block a second in a moderate wind) has two chances per period at one; the
// period, the chance, and each breeze's size, strength, length and life are all hashed, so some
// stretches of water get breezes often and some seldom. How hard the wind blows here, as a share of
// the wind (0 for none, about 1.6 at most).
float breezeAt(vec2 wp, vec4 wind) {
    float T = Origin.z, v = 8.0 + 24.0 * min(1.0, wind.z);
    vec2 g = wp - wind.xy * v * T, along = wind.xy, across = vec2(-wind.y, wind.x);
    ivec2 c0 = ivec2(floor(g / 512.0));
    float best = 0.0;
    for (int n = 0; n < 18; n++) {
        ivec2 c = c0 + ivec2(n % 3 - 1, (n / 3) % 3 - 1);
        int slot = 211 + 20 * (n / 9);
        float per = 18.0 + 18.0 * hash3(c.x, c.y, slot);
        float off = hash3(c.x, c.y, slot + 1) * per;
        int k = int(floor((T + off) / per));
        float age = T + off - float(k) * per;
        int kh = k & 1023;
        float life = (6.0 + 10.0 * hash3(c.x, c.y, slot + 2 + kh)) * (0.7 + 0.3 * Breeze.z);
        if (age > life) continue;
        float chance = Breeze.y * (0.3 + 0.9 * hash3(c.x, c.y, slot + 3 + kh)) * (0.5 + 0.5 * min(1.0, wind.z * 2.0));
        if (hash3(c.x, c.y, slot + 4 + kh) > chance) continue;
        float rad = Breeze.z * (48.0 + 144.0 * pow(hash3(c.x, c.y, slot + 5 + kh), 1.5));
        float stretch = 1.3 + 0.9 * hash3(c.x, c.y, slot + 6 + kh);
        rad = min(rad, 480.0 / stretch);
        vec2 at = (vec2(c) + vec2(hash3(c.x, c.y, slot + 7 + kh), hash3(c.x, c.y, slot + 8 + kh))) * 512.0;
        vec2 d = g - at;
        float e = length(vec2(dot(d, along) / stretch, dot(d, across))) / rad;
        if (e > 1.3) continue;
        e += (vnoise(ivec2(0), g / 28.0 + vec2(T / 7.0, -T / 9.0) + vec2(c) * 3.7, 213) - 0.5) * 0.6;
        float inside = 1.0 - smoothstep(0.45, 1.0, e);
        float a = age / life, swell = smoothstep(0.0, 0.3, a) * (1.0 - smoothstep(0.55, 1.0, a));
        float strength = Breeze.w * (0.8 + 0.8 * hash3(c.x, c.y, slot + 9 + kh));
        best = max(best, inside * swell * strength);
    }
    return best;
}

// Still water's fetch at a region texel for a wind, as a share of a full fetch (Still.z): blended
// between the field samples around the texel, so it never steps at a sample's edge.
float fetchShare(vec2 texel, vec4 wind) {
    vec2 q = (texel + 0.5) / (16.0 / Region.y) - 0.5, f = fract(q);
    ivec2 s0 = ivec2(floor(q)), top = ivec2(int(Region.z) - 1);
    int i0 = int(floor(wind.w)) % 16, i1 = (i0 + 1) % 16;
    float acc = 0.0;
    for (int c = 0; c < 4; c++) {
        ivec2 o = ivec2(c & 1, c >> 1), smp = clamp(s0 + o, ivec2(0), top);
        float a = texelFetch(FetchSampler, ivec2(smp.x * 4 + i0 / 4, smp.y), 0)[i0 % 4];
        float b = texelFetch(FetchSampler, ivec2(smp.x * 4 + i1 / 4, smp.y), 0)[i1 % 4];
        acc += mix(a, b, fract(wind.w)) * (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
    }
    return min(1.0, acc * 384.0 / Still.z);
}

// How ruffled still water is at a world texel: 0 glassy, about 1 under a fresh breeze, a little
// more in a strong one. Everything the wind does to still water (its darker tone, the ripple marks,
// white tips, the lapping, the calm life giving way) goes by this one value, and it is smooth in
// space and time: the wind of both patterns by how much each shows (`wA`), each with its gusts and
// the open water upwind, times the breezes. The breezes are the same whichever pattern a texel
// shows: each pattern's (on its own drifting lattice) are faded by its weight in time, so they
// cross over as the patterns take turns. `capped` leaves out any breeze stronger than the wind
// itself: white tips go by it, so a breeze adds none the wind would not have.
float roughAt(vec2 wp, vec2 texel, float wA, out float capped) {
    float r = 0.0;
    for (int p = 0; p < 2; p++) {
        float wt = p == 0 ? wA : 1.0 - wA;
        vec4 w = p == 0 ? WindA : WindB;
        if (wt <= 0.0 || w.z <= 0.0) continue;
        r += wt * w.z * (0.6 + 0.8 * gustAt(wp, w)) * (0.35 + 0.65 * fetchShare(texel, w));
    }
    r *= 1.6;
    float bz = 1.0;
    if (Breeze.x > 0.0 && r > 0.0) {
        float a = Calm.x > 0.001 ? breezeAt(wp, WindA) : 0.0, b = Calm.x < 0.999 ? breezeAt(wp, WindB) : 0.0;
        bz = mix(1.0, mix(b, a, Calm.x), Breeze.x);
    }
    capped = r * min(bz, 1.0);
    return r * bz;
}

// Glints on calm water at a world texel: the sky caught on the surface, a pixel that twinkles (a
// little cross at its brightest) and is gone. Each 6x6 texel cell has at most one per 3.2 s, at a
// hashed spot and moment. 0 for none, 1 lighter, 2 white.
int glintAt(vec2 wp) {
    if (Calm.y <= 0.0) return 0;
    ivec2 g = ivec2(floor(wp)), c = ivec2(floor(vec2(g) / 6.0));
    float T = Origin.z, off = hash3(c.x, c.y, 111) * 3.2;
    int k = int(floor((T + off) / 3.2));
    float age = T + off - float(k) * 3.2;
    if (age >= 0.8 || hash3(c.x, c.y, 113 + (k & 1023)) >= Calm.y * 0.15) return 0;
    ivec2 at = c * 6 + 1 + ivec2(floor(vec2(hash3(c.x, c.y, 117 + (k & 1023)), hash3(c.x, c.y, 119 + (k & 1023))) * 4.0));
    float e = sin(age / 0.8 * 3.14159);
    int dd = abs(g.x - at.x) + abs(g.y - at.y);
    if (dd == 0 && e > 0.7) return 2;
    return (dd == 0 && e > 0.15) || (dd == 1 && e > 0.8) ? 1 : 0;
}

// Edge rings on calm water: faint lines (Calm2.w of them) hugging every edge (the shore, a rock, an island), the
// small disturbances bouncing off it. They drift in and out slowly, each stretch of shore at its own
// pace, each outer one a little behind the one inside it, and break here and there; now and then a fainter ring
// leaves the edge and fades as it spreads. `bank` is the distance to the nearest land in texels,
// `on` how strongly they show. 0 for none, 1 a fading ring, 2 an outer line, 3 the inner line.
int edgeRingAt(vec2 wp, float bank, float on, float bay) {
    if (on <= 0.0 || bank > max(9.5, 1.7 + 3.3 * Calm2.w)) return 0;
    float T = Origin.z, sway = vnoise(ivec2(0), wp / 64.0, 141) * 6.283;
    float r1 = 1.5 + 3.0 * (0.5 + 0.5 * sin(T * 0.9 + sway));
    float gap = vnoise(ivec2(0), wp / 10.0 + vec2(T / 13.0, -T / 17.0), 143);
    if (abs(bank - r1) < 0.5 && gap < 0.3 + 0.6 * on) return 3;
    // How many lines this stretch has: it drifts between one and the most, each outer line coming
    // in as scattered pieces that join up, and breaking up again as it goes.
    float have = 1.0 + (Calm2.w - 1.0) * smoothstep(0.2, 0.8, vnoise(ivec2(0), wp / 40.0 + vec2(T / 23.0, -T / 29.0), 153));
    float r = r1;
    for (int i = 1; i < 4; i++) {
        float there = clamp(have - float(i), 0.0, 1.0);
        if (there <= 0.0) break;
        r += 2.5 + 0.8 * (0.5 + 0.5 * sin(T * 0.9 + sway - 1.2 * float(i)));
        float g = vnoise(ivec2(0), wp / 10.0 + vec2(-T, T) / (13.0 + 2.0 * float(i)), 149 + 2 * i);
        if (abs(bank - r) < 0.5 && g < (0.3 + 0.55 * on - 0.05 * float(i)) * there) return 2;
    }
    float ph = fract(T / 3.5 + vnoise(ivec2(0), wp / 32.0, 145) * 0.6);
    float amp = smoothstep(0.45, 0.75, vnoise(ivec2(0), wp / 20.0 + vec2(T * 0.07, -T * 0.05), 147));
    if (abs(bank - (1.0 + ph * 8.0)) < 0.5 && amp * (1.0 - ph) * on > bay * 0.9) return 1;
    return 0;
}

// Bug rings at a world texel: now and then a bug touches calm still water and a ring or two
// spreads, slow and small. Each 32x32 texel cell has at most one per 6 s, kept inside its cell,
// and only where its middle is calm still water. How strong the ring is here (0 for none).
float bugRingAt(vec2 wp, vec2 texel) {
    if (Calm3.x <= 0.0) return 0.0;
    ivec2 c = ivec2(floor(wp / 32.0));
    float T = Origin.z, off = hash3(c.x, c.y, 161) * 6.0;
    int k = int(floor((T + off) / 6.0));
    float age = T + off - float(k) * 6.0;
    if (age > 1.8 || hash3(c.x, c.y, 163 + (k & 1023)) > Calm3.x * 0.12) return 0.0;
    vec2 at = vec2(c) * 32.0 + 9.0 + vec2(hash3(c.x, c.y, 165 + (k & 1023)), hash3(c.x, c.y, 167 + (k & 1023))) * 14.0;
    ivec2 mid = ivec2(floor(texel + at - wp));
    if (any(lessThan(mid, ivec2(0))) || any(greaterThanEqual(mid, ivec2(Region.x)))) return 0.0;
    if (texelFetch(RiverDecorSampler, mid, 0).a * 255.0 < 128.0) return 0.0;
    float rad = 1.0 + age * 4.0, fade = 1.0 - age / 1.8, dd = length(floor(wp) + 0.5 - at);
    if (abs(dd - rad) < 0.6) return fade;
    return rad > 4.0 && abs(dd - rad + 3.0) < 0.6 ? fade * 0.6 : 0.0;
}

// Rises at a world texel: now and then a fish touches calm still water. A dimple flashes
// where it broke the surface and a ripple spreads from it: a leading ring with two more behind it,
// further apart the further they have gone (the longer waves outrun the short ones), each crest
// with a darker trough inside it, all weakening as they widen until they break up and are gone.
// Each 64x64 texel cell has at most one per 7 s, starting where calm still water is, and spreads
// over into the cells around it. How strong the crest is here, or minus the trough's (0 for none).
float riseAt(vec2 wp, vec2 texel) {
    if (Calm.w <= 0.0) return 0.0;
    ivec2 c0 = ivec2(floor(wp / 64.0));
    float T = Origin.z, best = 0.0;
    for (int n = 0; n < 9; n++) {
        ivec2 c = c0 + ivec2(n % 3 - 1, n / 3 - 1);
        float off = hash3(c.x, c.y, 121) * 7.0;
        int k = int(floor((T + off) / 7.0));
        float age = T + off - float(k) * 7.0;
        if (age > 4.0 || hash3(c.x, c.y, 123 + (k & 1023)) > Calm.w * 0.4) continue;
        vec2 at = vec2(c) * 64.0 + 4.0 + vec2(hash3(c.x, c.y, 125 + (k & 1023)), hash3(c.x, c.y, 127 + (k & 1023))) * 56.0;
        float dd = length(floor(wp) + 0.5 - at), lead = 1.0 + age * 6.0;
        if (dd > lead + 1.0) continue;
        ivec2 mid = ivec2(floor(texel + at - wp));
        if (any(lessThan(mid, ivec2(0))) || any(greaterThanEqual(mid, ivec2(Region.x)))) continue;
        if (texelFetch(RiverDecorSampler, mid, 0).a * 255.0 < 128.0) continue;
        if (age < 0.35 && dd < 1.0) return 1.0 - age / 0.35;
        float space = 2.0 + age * 1.5, fade = pow(1.0 - age / 4.0, 1.2) / (1.0 + lead / 24.0);
        for (int i = 0; i < 3; i++) {
            float r = lead - float(i) * space;
            if (r < 0.8) break;
            float a = fade * (1.0 - 0.3 * float(i));
            if (abs(dd - r) < 0.6) { best = max(best, a); break; }
            if (abs(dd - r + space * 0.5) < 0.6 && best <= 0.0) { best = min(best, -a * 0.7); break; }
        }
    }
    return best;
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

// Wind ripples: a simulated wave surface. Six wave trains spread about the wind, each at its own
// angle, wavelength and height (the longer ones faster, as on deep water), add up into short
// crests of every shape; and the whole field is warped by slow noise that drifts and changes, so
// the crests bend, break and wander instead of running straight. They come in groups drifting
// downwind at half the crests' speed. Their size and speed go by the pattern's wind alone, the
// same everywhere, so crests never stretch where the wind is stronger: a light wind makes short
// ripples that creep, a full one long ripples that run, and the roughness only decides how much of
// the surface shows.
const vec4 TRAINS[6] = vec4[](vec4(-0.62, 1.12, 0.13, 0.7), vec4(-0.31, 0.86, 0.57, 0.9), vec4(-0.08, 1.0, 0.31, 1.0),
    vec4(0.17, 0.74, 0.79, 0.85), vec4(0.39, 1.27, 0.46, 0.75), vec4(0.71, 0.93, 0.05, 0.6));
float rippleLength(vec4 wind) { return (6.0 + 10.0 * min(1.0, wind.z)) * 1.6; }
float ripplePeriod(vec4 wind) { return Still.y / (0.3 + 0.7 * min(1.0, wind.z)); }

// The surface at a world texel: (height, about -1..1 with crests near 1; group envelope, 0..1).
vec2 rippleAt(vec2 wp, vec4 wind) {
    float lam0 = rippleLength(wind), T0 = ripplePeriod(wind), T = Origin.z, ang = atan(wind.y, wind.x);
    vec2 w = wp / (lam0 * 2.7);
    vec2 q = wp + (vec2(vnoise(ivec2(0), w + vec2(T / 9.0, T / 13.0), 231),
        vnoise(ivec2(0), w + vec2(17.3 - T / 11.0, T / 7.0), 233)) - 0.5) * lam0 * 0.9;
    float h = 0.0;
    for (int k = 0; k < 6; k++) {
        float a = ang + TRAINS[k].x, lam = lam0 * TRAINS[k].y;
        // the fraction only: cos of a large angle loses precision on some GPUs
        h += TRAINS[k].w * cos(fract(dot(q, vec2(cos(a), sin(a))) / lam - T / (T0 * sqrt(TRAINS[k].y)) + TRAINS[k].z) * 6.2832);
    }
    float cg = 0.5 * lam0 / T0; // group speed: half the crests'
    return vec2(h / 2.64, vnoise(ivec2(0), (wp - wind.xy * cg * T) / (lam0 * 3.0), 79));
}

// Ripple marks at a world texel for a wind pattern, at roughness `rough` (`capped` for white
// tips): (how lit a crest is, 0..1; how shaded the water just behind a crest, 0..1; 1 for a white
// tip). Each train's crests are drawn as sharp thin lines over the same warped field as the
// surface, so they bend and wander, and each train shows only in its own drifting patches, so
// its lines break into pieces of every length and in each stretch of water one or two trains
// lead: the crests run every which way about the wind, branching where two meet. A crest shows
// where it stands above a line that sinks as the water roughens (more in a group), so a rising
// wind brings up the strongest crests first, then more, longer and broader ones; their ends taper.
vec3 marksAt(vec2 wp, vec4 wind, float rough, float capped) {
    float lam0 = rippleLength(wind), T0 = ripplePeriod(wind), T = Origin.z, ang = atan(wind.y, wind.x);
    vec2 w = wp / (lam0 * 2.7);
    vec2 q = wp + (vec2(vnoise(ivec2(0), w + vec2(T / 9.0, T / 13.0), 231),
        vnoise(ivec2(0), w + vec2(17.3 - T / 11.0, T / 7.0), 233)) - 0.5) * lam0 * 0.9;
    float cg = 0.5 * lam0 / T0; // group speed: half the crests'
    float env = vnoise(ivec2(0), (wp - wind.xy * cg * T) / (lam0 * 3.0), 79);
    float r = rough * (0.45 + 0.9 * env), c = max(0.3, 0.98 - 0.55 * r);
    float top = 0.0, lee = 0.0;
    for (int k = 0; k < 6; k++) {
        float a = ang + TRAINS[k].x * 0.8, lam = lam0 * TRAINS[k].y;
        vec2 dir = vec2(cos(a), sin(a));
        float ph = fract(dot(q, dir) / lam - T / (T0 * sqrt(TRAINS[k].y)) + TRAINS[k].z);
        float shows = smoothstep(0.35, 0.8, vnoise(ivec2(0), (q - dir * cg * T) / (lam0 * 0.9) + vec2(float(k) * 7.1, float(k) * 3.3), 241 + k));
        float crest = pow(0.5 + 0.5 * cos(ph * 6.2832), 14.0), behind = pow(0.5 + 0.5 * cos((ph + 0.14) * 6.2832), 14.0); // upwind of the crest: crests travel downwind
        top = max(top, shows * TRAINS[k].w * crest);
        lee = max(lee, shows * TRAINS[k].w * behind);
    }
    float on = clamp(r * 3.0, 0.0, 1.0);
    float lit = smoothstep(c, c + 0.15, top) * on;
    float deep = smoothstep(c, c + 0.3, lee) * on * (1.0 - lit);
    float cap = capped * (0.45 + 0.9 * env) - 0.9;
    float white = cap > 0.0 && top > 0.85
        && hash3(int(floor(wp.x / 2.0)), int(floor(wp.y / 2.0)), int(floor(T * 3.0))) < cap * 3.0 ? 1.0 : 0.0;
    return vec3(lit, deep, white);
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
        // On a shore the wind blows onto, each ripple laps a little way up as it arrives, the
        // further the rougher the water.
        if (dr < 10.0 && Still.w > 0.0 && Still.x > 0.0) {
            float capped, wA = patternA(wp);
            float reach = Still.x * Still.w * 0.625 * roughAt(wp, texel, wA, capped);
            if (reach > 0.3) {
                vec2 rp = rippleAt(wp, wA > bay ? WindA : WindB);
                float run = reach * clamp((rp.x * (0.55 + 0.9 * rp.y) - 0.1) / 0.8, 0.0, 1.0);
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
    // Water recolouring off: the water keeps vanilla's colour, and only pixels with something on
    // them are drawn (in the plain water's colour, changed as usual).
    bool tint = Calm2.z > 0.5;
    if (tint && stp > 0.0) {
        float a = River.z * stp, k = 1.0 + 0.3 * a;
        col = col * vec3(0.94 * k, 1.1 * k, k) + vec3(12.0, 20.0, 8.0) / 255.0 * a;
        alpha *= 1.0 - 0.4 * a; // clearer: the bed shows through more
    } else if (tint) {
        col *= 1.0 - 0.08 * min(1.0, bank / 32.0);
    }
    // Still water: calm life whatever the wind, then the wind's ripples.
    bool still = decor.a > 0.5 / 255.0;
    float calm = still ? (decor.a * 255.0 - 1.0) / 254.0 : 0.0;
    // How ruffled the still water is here (see roughAt); everything the wind does goes by it.
    float wA = 0.0, capped = 0.0, rough = 0.0;
    if (still) {
        wA = patternA(wp);
        rough = roughAt(wp, texel, wA, capped) * calm;
        capped *= calm;
    }
    if (calm > 0.3) {
        // The wind's tone: rough water shows less sky, so it is a shade darker, with fine flecks of
        // light coming and going where it is roughest. In four dithered steps, so it shades on.
        float tone = Calm.z > 0.0 ? floor(min(1.0, Calm.z * 1.6) * smoothstep(0.3, 1.0, rough) * 4.0 + bay) / 4.0 : 0.0;
        if (tone > 0.0) {
            col *= mix(vec3(1.0), vec3(0.9, 0.9, 0.93), tone);
            ivec2 g = ivec2(floor(wp));
            float fl = hash3(g.x, g.y, 107);
            if (tone > 0.7 && fl < 0.2 && fract(T / 1.4 + fl * 37.0) < 0.25) col = col * 1.16 + 12.0 / 255.0;
        }
        // The calm life gives way as the water roughens.
        int er = edgeRingAt(wp, bank, Calm2.x * calm * (1.0 - 0.6 * min(1.0, rough * Still.x)), bay);
        if (er == 3) col = col * 1.14 + 12.0 / 255.0;
        else if (er == 2) col = col * 1.11 + 10.0 / 255.0;
        else if (er == 1) col = col * 1.08 + 7.0 / 255.0;
        int gl = glintAt(wp);
        if (gl == 2) return vec4(foamBright(), 1.0);
        if (gl == 1) col = col * 1.25 + 22.0 / 255.0;
    }
    // Ripple marks (see marksAt), each pattern's where it shows, blended by how much it shows; crests
    // lit in four dithered steps, troughs darkened in three.
    if (still && Still.x > 0.0 && rough > 0.0) {
        float mark = 0.0, deep = 0.0;
        bool cap = false;
        for (int p = 0; p < 2; p++) {
            float wt = p == 0 ? wA : 1.0 - wA;
            vec4 pw = p == 0 ? WindA : WindB;
            if (wt <= 0.0 || pw.z <= 0.0) continue;
            vec3 m = marksAt(wp, pw, rough * Still.x * 1.25, capped * Still.x * 1.25);
            mark += m.x * wt;
            deep += m.y * wt;
            if (m.z > 0.0 && wt > bay) cap = true;
        }
        if (cap) return vec4(foamBright(), 1.0);
        float q = floor(mark * 4.0 + bay) / 4.0;
        if (q > 0.0) col = col * (1.0 + 0.24 * q) + 20.0 / 255.0 * q;
        else col *= 1.0 - 0.06 * floor(deep * 3.0 + bay) / 3.0;
    }
    float sp = length(rv.xy), v = 0.0, spark = 1.0;
    if (sp > 0.5 && River.x > 0.0) {
        float per = 2.2, ph = fract(T / per), wA = 1.0 - abs(2.0 * ph - 1.0);
        bool useA = wA > (BAYER[((wt.y >> 2) & 3) * 4 + ((wt.x >> 2) & 3)] + 0.5) / 16.0;
        float tt = useA ? ph : fract(ph + 0.5);
        int seed = useA ? 11 : 29;
        vec2 q = vec2(wt) - rv.xy * tt * per, dir = rv.xy / sp;
        int len = max(2, int(floor(River.y * (1.0 - 0.45 * sh) + 0.5)));
        float dens = River.x * (0.01 + 0.018 * sh) + decor.b * 0.1;
        // In still water each flow line dies out as the current spends itself, each at its own
        // point (a hashed share of the current it needs, and a wobble along the water), so they
        // thin out instead of stopping on a line.
        float live = still ? smoothstep(0.08, 0.7, (1.0 - calm) * (0.55 + 0.9 * vnoise(ivec2(0), wp / 20.0, 151))) : 1.0;
        for (int j = 0; j < 24; j++) {
            if (j >= len) break;
            ivec2 sq = ivec2(floor(q + dir * float(j) + 0.5));
            if (hash3(sq.x & 255, sq.y & 255, seed) < dens) {
                if (hash3(sq.x & 255, sq.y & 255, seed + 5) < live) {
                    v = 1.0 - float(j) / float(len);
                    spark = hash3(sq.x & 255, sq.y & 255, seed + 9);
                }
                break;
            }
        }
    }
    if (v > 0.0) {
        // Foam carried down from a rock, as white; and now and then a flow line's head sparkles
        // white as it passes (twinkling over the pixels it crosses). A line's chance is cut where
        // lines are denser (the shallows), so sparkles are as common everywhere.
        bool sparkle = v > 0.8 && spark < Calm2.y * 0.24 * 0.01 / (0.01 + 0.018 * sh) && hash3(wt.x, wt.y, 71) < 0.5;
        if ((decor.b > 0.2 && v > 0.35) || sparkle) return vec4(foamBright(), 1.0);
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
    else if (still) {
        float rise = riseAt(wp, texel);
        if (rise > bay * 0.5) col = col * 1.2 + 18.0 / 255.0;
        else if (-rise > bay * 0.5) col *= vec3(0.9, 0.91, 0.94);
        else if (bugRingAt(wp, texel) > bay * 0.8) col = col * 1.2 + 18.0 / 255.0;
    }
    if (!tint && col == w.rgb) return vec4(0.0);
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
