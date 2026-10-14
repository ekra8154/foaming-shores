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
// 256x256 tiling noise: r = front raggedness, g = lip width, b = foam lace.
uniform sampler2D NoiseSampler;
// Per face, two texels: (edge texel x, z, step x, z) in region texels, (out x, z, kind, height).
// Kind 1 = drop, 3 = ledge over the sea, 4 = step down into a dip (edge texel on top of each);
// 2 = wall (edge texel is the one at its foot).
uniform sampler2D FaceSampler;
// Per field sample in a dip: (arrival at the dip's entry, 1, -, -); (-, 0, -, -) elsewhere.
uniform sampler2D DipSampler;
// Vanilla's block atlas: the sheet is drawn with the water textures, animated by vanilla.
uniform sampler2D AtlasSampler;
// In the face pass, the tops just baked: a ledge or step carries foam on its edge over its top row.
uniform sampler2D RegionSampler;

layout(std140) uniform FoamBake {
    vec4 Region;   // region size in texels, field samples per block, field size in samples, -
    vec4 Origin;   // region origin in world texels (x, z) mod 256, time in seconds, wave count
    vec4 Shape;    // lip width, raggedness, foam far out at sea, lace density
    vec4 Lace;     // lace half-life under the sheet, on sand, at sea; backwash pull (texels/s)
    vec4 Look;     // drying time, wet darkness, sheet opacity, calmer in coves
    vec4 Motion;   // approach distance (texels), run-up distance (texels), approach speed (texels/s), pause (s)
    vec4 Motion2;  // backwash time (s), foam tint, puddle soak time (s), -
    vec4 Water;    // biome water colour
    vec4 Terrain;  // spill over drops, falling foam reach (texels), splash height (texels), foam piling
    vec4 Mode;     // mode, face count, face texture width, face texture height
    vec4 Sprites;  // atlas texel origin of water_still (x, y) and of water_flow (x, y)
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

// Vanilla flowing water on a face (it already scrolls downward), column u, row down from the top.
vec4 waterFlow(int u, int row) {
    vec4 t = texelFetch(AtlasSampler, ivec2(Sprites.zw) + ivec2(u & 15, row & 15), 0);
    return vec4(t.rgb * Water.rgb, t.a);
}

// One wave's timing (seconds), shared by both modes.
struct Wave {
    float t0, amp, Ta, Tu, tR0, tB0, tEnd;
    ivec2 front, lace, lip;
};

Wave waveAt(int i) {
    vec4 A = Waves[2 * i], B = Waves[2 * i + 1];
    Wave w;
    w.t0 = A.x;
    w.amp = A.y;
    w.Ta = Motion.x / Motion.z;
    w.Tu = 2.0 * Motion.y * w.amp / Motion.z;
    w.tR0 = w.t0 + w.Ta;
    w.tB0 = w.tR0 + w.Tu + Motion.w;
    w.tEnd = w.tB0 + Motion2.x;
    w.front = ivec2(A.zw);
    w.lace = ivec2(B.xy);
    w.lip = ivec2(B.zw);
    return w;
}

// Closed-form history of a wave at a texel: when its front reached the texel, when the lip
// stopped laying lace there and when the backwash uncovered it. False if it never gets there.
bool waveTimes(Wave w, ivec2 wt, float dp, float e, out float Re, out float tCross, out float tLeave, out float tUnc, out bool laid) {
    float jag = Shape.y;
    float jv = (noiseAt(0, wt + w.front) - 0.5) * 2.0 * jag;
    Re = Motion.y * w.amp * e;
    float TuE = w.Tu * min(1.0, e);
    float uR = (jv - dp) / Re;
    tCross = 0.0;
    tLeave = 0.0;
    tUnc = 0.0;
    laid = false;
    if (uR > 1.0) return false;
    tCross = uR <= 0.0 ? w.tR0 : w.tR0 + TuE * (1.0 - sqrt(1.0 - uR));
    float uL = (jv - dp + Shape.x + 3.0) / Re;
    laid = uL > 0.0;
    tLeave = uL <= 1.0 ? w.tR0 + TuE * (1.0 - sqrt(1.0 - max(0.0, uL))) : w.tB0;
    float k2 = (dp + Re - 0.7 * jv) / (Re + 6.0);
    tUnc = k2 >= 1.0 ? w.tEnd : w.tB0 + Motion2.x * sqrt(max(0.0, k2));
    return true;
}

// ---- Block tops ----------------------------------------------------------------------------------

vec4 bakeTop(vec2 texel) {
    ivec2 wt = ivec2(Origin.xy) + ivec2(texel);
    float T = Origin.z;
    vec4 fields = fieldsAt(texel);
    float dp = fields.x, sd = fields.y, expo = fields.z;
    float perSample = 16.0 / Region.y;
    float L = texelFetch(FieldSampler, ivec2(floor((texel + 0.5) / perSample)), 0).w;
    bool isWater = L > 7.5;
    float level = isWater ? 0.0 : L;
    float bay = bayerAt(wt);

    float lipW = Shape.x, jag = Shape.y, offshore = Shape.z, thr = 1.0 - Shape.w;
    float approach = Motion.x, runup = Motion.y;
    float lo = -(runup + jag + 10.0), hi = approach + jag + lipW * 2.0 + 10.0;
    if (dp < lo || dp > hi) return vec4(0.0);
    float e = 1.0 + (expo - 1.0) * Look.w;

    // Beach-level texels within 2 of a raised block: foam piles here while a wave pushes.
    bool nearWall = false;
    if (abs(level) < 0.5) {
        for (int k = 1; k <= 2; k++) {
            nearWall = nearWall || levelAt(texel + vec2(k, 0)) > 0.5 || levelAt(texel - vec2(k, 0)) > 0.5
                || levelAt(texel + vec2(0, k)) > 0.5 || levelAt(texel - vec2(0, k)) > 0.5;
        }
    }

    // In a dip, water that ran in cannot drain back uphill over the rim. Its backwash is the puddle:
    // the water stays where the wave reached and recedes slowly back toward the dip's entry (the
    // rim it came in over), over the soak time, never climbing out.
    vec4 dipInfo = texelFetch(DipSampler, ivec2(floor((texel + 0.5) / perSample)), 0);
    bool isDip = !isWater && abs(level + 1.0) < 0.5 && dipInfo.y > 0.5 && dipInfo.x > UNREACHABLE;
    float dipEntry = dipInfo.x, soak = max(0.1, Motion2.z);

    bool cov = false, edge = false, swell = false, pushing = false, covPuddle = false;
    float covF = 0.0, foam = 0.0, lastUnc = -1e9;
    int lip = 0;
    int waveCount = int(Origin.w);
    for (int i = 0; i < waveCount; i++) {
        Wave w = waveAt(i);
        float tau = T - w.t0;
        if (tau < 0.0) continue;
        float jv = (noiseAt(0, wt + w.front) - 0.5) * 2.0 * jag;
        float lw = lipW * (0.6 + 0.8 * noiseAt(1, wt + w.lip));
        float Re = runup * w.amp * e;

        if (tau < w.Ta) {
            // Coming in: a foam line sweeping toward the shore, sparse far out.
            float prog = tau / w.Ta;
            float F = approach * (1.0 - prog) + jv;
            if (dp >= F) {
                if (dp < F + lw) {
                    float dens = (offshore + (1.0 - offshore) * pow(prog, 1.5)) * e;
                    if (bay < dens) lip = max(lip, dp - F < lw * 0.55 ? 2 : 1);
                } else if (dp < F + lw + 5.0 && isWater) swell = true;
            }
            continue;
        }

        // Where the wave is now. A sheltered spot's run-up is shorter and ends proportionally
        // sooner, so the front leaves the shore at the speed it arrived everywhere.
        float TuE = w.Tu * min(1.0, e);
        if (T < w.tEnd) {
            float F;
            int ph;
            float lipScale = 1.0;
            if (T < w.tR0 + w.Tu) {
                float kk = min(1.0, (tau - w.Ta) / TuE);
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
            if (dp >= F && !(isDip && ph == 3)) {
                cov = true;
                covF = F;
                covPuddle = false;
                if (ph < 3) {
                    pushing = true;
                    float lw2 = lw * lipScale;
                    if (dp < F + lw2) lip = max(lip, dp - F < lw2 * 0.55 ? 2 : 1);
                } else if (dp - F < 1.5 && !isWater) edge = true;
            }
        }

        float ReT, tCross, tLeave, tUnc;
        bool laid;
        if (!waveTimes(w, wt, dp, e, ReT, tCross, tLeave, tUnc, laid) || T < tCross) continue;
        if (isDip) {
            // The puddle's edge rises from the wave's furthest reach to the entry over the soak time.
            float Fmin = -ReT + jv, span = max(1.0, dipEntry - Fmin);
            tUnc = w.tB0 + soak * clamp((dp - Fmin) / span, 0.0, 1.0);
            if (T >= w.tB0 && T < tUnc) {
                cov = true;
                covF = Fmin + span * (T - w.tB0) / soak;
                covPuddle = true;
                if (dp - covF < 1.5) edge = true;
            }
        }
        if (T >= tUnc) lastUnc = max(lastUnc, tUnc);
        float lv = 0.0, tDep = 0.0;
        if (dp < 10.0 && laid) {
            ivec2 s = wt;
            if (Lace.w > 0.0 && abs(level) < 0.5 && T > w.tB0) {
                // The backwash drags lace seaward: read the pattern from further up the beach.
                vec2 g = vec2(arrivalAt(texel + vec2(1.0, 0.0)) - arrivalAt(texel - vec2(1.0, 0.0)),
                              arrivalAt(texel + vec2(0.0, 1.0)) - arrivalAt(texel - vec2(0.0, 1.0)));
                float gradLen = length(g);
                if (gradLen > 0.0 && gradLen < 100.0) {
                    float gg = 0.6 + 0.8 * clamp(1.0 + dp / max(1.0, ReT), 0.0, 1.0);
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
        if (nearWall && 0.9 * Terrain.w > lv) {
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
    if (nearWall && pushing) {
        float pressure = clamp((dp - covF) / 6.0, 0.0, 1.0);
        pile = bay < pressure * Terrain.w;
    }

    vec4 color = vec4(0.0);
    if (!isWater) {
        // Wet sand: a dark film, so it darkens whatever block is underneath.
        float wv = (cov || (abs(level) < 0.5 && sd > -2.5)) ? 1.0 : max(0.0, 1.0 - (T - lastUnc) / Look.x);
        if (wv > 0.0) {
            float q = wv + (bay - 0.5) * 0.34;
            color = over(vec4(0.0, 0.0, 0.0, Look.y * (q > 0.66 ? 1.0 : q > 0.2 ? 0.5 : 0.0)), color);
        }
        if (cov) {
            // The sheet: shallow water, nearly opaque where it leaves the sea, thinning in dithered
            // steps toward its edge. A puddle is deepest toward the entry it is receding to.
            float th = covPuddle ? (dp - covF) / max(1.0, dipEntry - covF) : (dp - covF) / max(1.0, -covF);
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
    return color;
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
    if (row >= h) return vec4(0.0);

    vec2 texel = d0.xy + d0.zw * float(u);
    ivec2 wt = ivec2(Origin.xy) + ivec2(texel);
    float T = Origin.z;
    vec4 fields = fieldsAt(texel);
    float dp = fields.x, e = 1.0 + (fields.z - 1.0) * Look.w;
    float bay = bayerAt(ivec2(u, row));
    float fromBottom = float(h - 1 - row) / max(1.0, float(h - 1)); // 0 at the bottom, 1 at the top
    float flow = 0.0, age = 1e9, faceFoam = 0.0;
    bool streak = false, faceLip = false;
    int waveCount = int(Origin.w);
    // A ledge or step continues the beach over its edge: row r reads the texel r + 1 past the edge.
    ivec2 past = wt + ivec2(d1.xy * float(row + 1));

    for (int i = 0; i < waveCount; i++) {
        Wave w = waveAt(i);
        if (T < w.tR0) continue;
        float Re, tCross, tLeave, tUnc;
        bool laid;
        if (!waveTimes(w, wt, dp, e, Re, tCross, tLeave, tUnc, laid) || T < tCross) continue;
        if (kind == 1) {
            // Drop: water pours over from the moment the front reaches the edge until the wave stops
            // pushing. What is at this row now left the edge fallTime(row) ago. Not every column pours,
            // and each column's streak falls its own distance (some paths always run further).
            if (hash3(wt.x, wt.y, 91 + w.front.x) > min(1.0, Terrain.x * 1.6)) continue;
            float reach = max(2.0, Terrain.y * (0.65 + 0.7 * hash3(wt.x, wt.y, 77) + 0.6 * (hash3(wt.x, wt.y, 79 + w.front.y) - 0.5)));
            float r = float(row);
            if (r >= reach) continue;
            float ft = fallTime(r), te = T - ft, fade = 1.0 - r / reach;
            if (te >= tCross && te <= w.tB0) {
                flow = max(flow, fade);
                if (fract(te * 5.0 + hash3(wt.x, wt.y, 83 + w.lace.x)) < 0.3) streak = true;
            }
            if (T >= tCross + ft) age = min(age, max(0.0, T - min(T, w.tB0 + ft)));
        } else if (kind >= 3) {
            // Ledge or step: water runs down it for as long as the sheet covers the sand at its edge.
            if (T < tUnc) flow = 1.0;
            else age = min(age, T - tUnc);
            // The lace pattern carries on over the edge, laid and fading on the edge's schedule.
            if (laid) {
                float lv = noiseAt(2, past + w.lace);
                float t0d = min(T, tLeave), tu = max(t0d, tUnc);
                float k = (min(T, tu) - t0d) * 0.6931 / Lace.x + max(0.0, T - tu) * 0.6931 / Lace.y;
                faceFoam = max(faceFoam, lv * exp(-k));
            }
            // The front's foam lip sweeps down the face as it pours over the edge.
            float r0 = (T - tCross) * Motion.z;
            if (T < w.tB0 && float(row) <= r0 && float(row) > r0 - Shape.x * 1.4) faceLip = true;
        } else {
            // Wall: the wash runs up when a front hits the foot, then slides back down.
            float energy = sqrt(clamp((dp + Re) / Re, 0.0, 1.0));
            float Hh = max(1.0, Terrain.z * (0.25 + 0.75 * energy) * 0.6);
            float tau = T - tCross;
            float hNow = tau < 0.15 ? Hh * tau / 0.15 : Hh * max(0.0, 1.0 - (tau - 0.15) / 0.6);
            float up = float(h - 1 - row);
            if (up > Hh) continue;
            // Strictly below the wash: once it has drained (hNow = 0) no row is under water.
            if (up < hNow) flow = 1.0;
            float left = tCross + 0.15 + 0.6 * (1.0 - up / Hh);
            age = min(age, max(0.0, T - left));
        }
    }

    if (kind == 3) age = 0.0; // a ledge right at the waterline is always wet
    vec4 color = vec4(0.0);
    // A face dries from the top down.
    float wv = age < 1e8 ? max(0.0, 1.0 - age * (0.6 + 1.2 * fromBottom) / Look.x) : 0.0;
    if (wv > 0.0) {
        float q = wv + (bay - 0.5) * 0.34;
        color = over(vec4(0.0, 0.0, 0.0, Look.y * (q > 0.66 ? 1.0 : q > 0.2 ? 0.5 : 0.0)), color);
    }
    if (flow > 0.0) {
        float q = flow + (bay - 0.5) * 0.3;
        vec4 w = waterFlow(u, row);
        color = over(vec4(w.rgb, w.a * Look.z * (q > 0.6 ? 1.0 : q > 0.25 ? 0.6 : 0.0)), color);
    }
    if (streak && flow > bay) color = vec4(foamBright(), 1.0);
    if (kind >= 3) {
        float thr = 1.0 - Shape.w, fv = faceFoam + (bay - 0.5) * 0.1;
        if (faceFoam > 0.004 && fv > thr) color = vec4(fv > thr + (1.0 - thr) * 0.45 ? foamBright() : foamShadow(), 1.0);
        if (faceLip) color = vec4(foamBright(), 1.0);
    }
    if (kind >= 3 && row == 0) {
        vec4 top = texelFetch(RegionSampler, ivec2(texel), 0);
        if (top.a > 0.99) color = top; // foam is opaque; the sheet and wet sand are not
    }
    return color;
}

void main() {
    if (Mode.x < 0.5) fragColor = bakeTop(floor(texCoord * Region.x));
    else fragColor = bakeFace(floor(texCoord * Mode.zw));
}
