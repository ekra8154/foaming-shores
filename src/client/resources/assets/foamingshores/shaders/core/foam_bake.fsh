#version 330
#extension GL_ARB_separate_shader_objects : require

// Bakes one region's shore foam into a texture, 16 texels per block, from the stateless wave model
// (see foaming-shores-playground). Every value here is a function of the fields, the wave list and
// the time: nothing carries over between bakes.

// Per field sample (4 per block): arrival, shore distance (both in texels), exposure, and the
// block's level (-4..1 for land, 8 for a pool, 9 for sea water).
uniform sampler2D FieldSampler;
// 256x256 tiling noise: r = front raggedness, g = lip width, b = foam lace.
uniform sampler2D NoiseSampler;

layout(std140) uniform FoamBake {
    vec4 Region;   // region size in texels, field samples per block, field size in samples, -
    vec4 Origin;   // region origin in world texels (x, z), time in seconds, wave count
    vec4 Shape;    // lip width, raggedness, foam far out at sea, lace density
    vec4 Lace;     // lace half-life under the sheet, on sand, at sea; backwash pull (texels/s)
    vec4 Look;     // drying time, wet darkness, sheet opacity, calmer in coves
    vec4 Motion;   // approach distance (texels), run-up distance (texels), approach speed (texels/s), pause (s)
    vec4 Motion2;  // backwash time (s), foam tint, -, -
    vec4 Water;    // biome water colour
    vec4 Waves[24]; // per wave: (t0, amplitude, front noise offset xy), (lace offset xy, lip noise offset xy)
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const float BAYER[16] = float[](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);
const float UNREACHABLE = -900.0;

float noiseAt(int channel, ivec2 p) {
    return texelFetch(NoiseSampler, ivec2(p.x & 255, p.y & 255), 0)[channel];
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
    w *= vec4(a.x > UNREACHABLE, b.x > UNREACHABLE, c.x > UNREACHABLE, d.x > UNREACHABLE);
    float sum = w.x + w.y + w.z + w.w;
    if (sum <= 0.0) return vec4(-1000.0, mix(mix(a.y, b.y, f.x), mix(c.y, d.y, f.x), f.y), a.z, a.w);
    vec4 v = (a * w.x + b * w.y + c * w.z + d * w.w) / sum;
    // Shore distance never needs the reachability mask.
    v.y = mix(mix(a.y, b.y, f.x), mix(c.y, d.y, f.x), f.y);
    return v;
}

float arrivalAt(vec2 texel) {
    return fieldsAt(texel).x;
}

vec4 over(vec4 src, vec4 dst) {
    float a = src.a + dst.a * (1.0 - src.a);
    if (a <= 0.0) return vec4(0.0);
    return vec4((src.rgb * src.a + dst.rgb * dst.a * (1.0 - src.a)) / a, a);
}

void main() {
    vec2 texel = floor(texCoord * Region.x);
    ivec2 wt = ivec2(Origin.xy) + ivec2(texel);
    float T = Origin.z;
    vec4 fields = fieldsAt(texel);
    float dp = fields.x, sd = fields.y, expo = fields.z;
    float perSample = 16.0 / Region.y;
    float L = texelFetch(FieldSampler, ivec2(floor((texel + 0.5) / perSample)), 0).w;
    bool isWater = L > 7.5;
    float level = isWater ? 0.0 : L;
    float bay = (BAYER[(wt.y & 3) * 4 + (wt.x & 3)] + 0.5) / 16.0;

    float lipW = Shape.x, jag = Shape.y, offshore = Shape.z, thr = 1.0 - Shape.w;
    float approach = Motion.x, runup = Motion.y, speed = Motion.z, stall = Motion.w, backwash = Motion2.x;
    float lo = -(runup + jag + 10.0), hi = approach + jag + lipW * 2.0 + 10.0;
    if (dp < lo || dp > hi) {
        fragColor = vec4(0.0);
        return;
    }
    float e = 1.0 + (expo - 1.0) * Look.w;

    bool cov = false, edge = false, swell = false;
    float covF = 0.0, foam = 0.0, lastUnc = -1e9;
    int lip = 0;
    int waveCount = int(Origin.w);
    for (int i = 0; i < waveCount; i++) {
        vec4 A = Waves[2 * i], B = Waves[2 * i + 1];
        float t0 = A.x, amp = A.y;
        float tau = T - t0;
        if (tau < 0.0) continue;
        float Ta = approach / speed, Tu = 2.0 * runup * amp / speed;
        float tR0 = t0 + Ta, tB0 = tR0 + Tu + stall, tEnd = tB0 + backwash;
        float jv = (noiseAt(0, wt + ivec2(A.zw)) - 0.5) * 2.0 * jag;
        float lw = lipW * (0.6 + 0.8 * noiseAt(1, wt + ivec2(B.zw)));
        float Re = runup * amp * e;

        if (tau < Ta) {
            // Coming in: a foam line sweeping toward the shore, sparse far out.
            float prog = tau / Ta;
            float F = approach * (1.0 - prog) + jv;
            if (dp >= F) {
                if (dp < F + lw) {
                    float dens = (offshore + (1.0 - offshore) * pow(prog, 1.5)) * e;
                    if (bay < dens) lip = max(lip, dp - F < lw * 0.55 ? 2 : 1);
                } else if (dp < F + lw + 5.0 && isWater) swell = true;
            }
            continue;
        }

        // Where the wave is now. A sheltered spot's run-up is shorter and ends proportionally sooner,
        // so the front leaves the shore at the speed it arrived everywhere.
        float TuE = Tu * min(1.0, e);
        if (T < tEnd) {
            float F;
            int ph;
            float lipScale = 1.0;
            if (T < tR0 + Tu) {
                float kk = min(1.0, (tau - Ta) / TuE);
                F = -Re * (1.0 - (1.0 - kk) * (1.0 - kk)) + jv;
                ph = 1;
            } else if (T < tB0) {
                F = -Re + jv;
                ph = 2;
                lipScale = 1.0 - (T - tR0 - Tu) / max(0.001, stall);
            } else {
                float k = (T - tB0) / backwash;
                F = -Re + (Re + 6.0) * k * k + jv * 0.7;
                ph = 3;
            }
            if (dp >= F) {
                cov = true;
                covF = F;
                if (ph < 3) {
                    float lw2 = lw * lipScale;
                    if (dp < F + lw2) lip = max(lip, dp - F < lw2 * 0.55 ? 2 : 1);
                } else if (dp - F < 1.5 && !isWater) edge = true;
            }
        }

        // Its history here, solved in closed form: when the front reached this texel, when the lip
        // stopped laying lace on it, and when the backwash uncovered it again.
        float uR = (jv - dp) / Re;
        if (uR > 1.0) continue;
        float tCross = uR <= 0.0 ? tR0 : tR0 + TuE * (1.0 - sqrt(1.0 - uR));
        if (T < tCross) continue;
        float uL = (jv - dp + lipW + 3.0) / Re;
        float tLeave = uL <= 1.0 ? tR0 + TuE * (1.0 - sqrt(1.0 - max(0.0, uL))) : tB0;
        float k2 = (dp + Re - 0.7 * jv) / (Re + 6.0);
        float tUnc = k2 >= 1.0 ? tEnd : tB0 + backwash * sqrt(max(0.0, k2));
        if (T >= tUnc) lastUnc = max(lastUnc, tUnc);
        if (dp < 10.0 && uL > 0.0) {
            ivec2 s = wt;
            if (Lace.w > 0.0 && abs(level) < 0.5 && T > tB0) {
                // The backwash drags lace seaward: read the pattern from further up the beach.
                vec2 g = vec2(arrivalAt(texel + vec2(1.0, 0.0)) - arrivalAt(texel - vec2(1.0, 0.0)),
                              arrivalAt(texel + vec2(0.0, 1.0)) - arrivalAt(texel - vec2(0.0, 1.0)));
                float gl = length(g);
                if (gl > 0.0 && gl < 100.0) {
                    float gg = 0.6 + 0.8 * clamp(1.0 + dp / max(1.0, Re), 0.0, 1.0);
                    float D = Lace.w * gg * (min(T, tUnc) - tB0);
                    s = wt - ivec2(round(g / gl * D));
                }
            }
            float lv = noiseAt(2, s + ivec2(B.xy));
            if (lv > foam) {
                // Fades slowly while the sheet covers it, faster once it is out in the open.
                float t0d = min(T, tLeave), k;
                if (isWater) k = (T - t0d) * 0.6931 / Lace.z;
                else {
                    float tu = max(t0d, tUnc);
                    k = (min(T, tu) - t0d) * 0.6931 / Lace.x + max(0.0, T - tu) * 0.6931 / Lace.y;
                }
                foam = max(foam, lv * exp(-k));
            }
        }
    }

    vec3 FO = mix(vec3(250.0, 252.0, 250.0) / 255.0, Water.rgb, Motion2.y);
    vec3 FS = mix(vec3(236.0, 242.0, 240.0) / 255.0, Water.rgb, Motion2.y + 0.26) * 0.94;
    vec4 color = vec4(0.0);
    if (!isWater) {
        // Wet sand: a dark film, so it darkens whatever block is underneath.
        float wv = (cov || (abs(level) < 0.5 && sd > -2.5)) ? 1.0 : max(0.0, 1.0 - (T - lastUnc) / Look.x);
        if (wv > 0.0) {
            float q = wv + (bay - 0.5) * 0.34;
            color = over(vec4(0.0, 0.0, 0.0, Look.y * (q > 0.66 ? 1.0 : q > 0.2 ? 0.5 : 0.0)), color);
        }
        if (cov) {
            // The sheet: shallow water, nearly opaque where it leaves the sea, thinning in dithered steps.
            float th = (dp - covF) / max(1.0, -covF);
            float q = th + (bay - 0.5) * 0.3;
            float a = Look.z * (q > 0.6 ? 1.0 : q > 0.25 ? 0.7 : 0.45);
            vec3 shallow = Water.rgb * 0.78 * 0.75 + vec3(150.0, 142.0, 104.0) / 255.0 * 0.22;
            color = over(vec4(shallow, a), color);
        }
        if (edge) color = over(vec4(mix(Water.rgb, vec3(1.0), 0.62), 0.6), color);
    } else if (swell) {
        color = over(vec4(1.0, 1.0, 1.0, 0.08), color);
    }
    float fv = foam + (bay - 0.5) * 0.1;
    if (foam > 0.004 && fv > thr) color = vec4(fv > thr + (1.0 - thr) * 0.45 ? FO : FS, 1.0);
    if (lip > 0) color = vec4(lip == 2 ? FO : FS, 1.0);
    fragColor = color;
}
