package dev.ekra.foamingshores.wave;

import dev.ekra.foamingshores.FoamSettings;
import dev.ekra.foamingshores.shore.ShoreRegion;

/**
 * The CPU side of the stateless wave model, matching foam_bake.fsh exactly where the CPU needs the
 * same answers (splashes at walls, the sound): field values at a texel, one wave as it is at a
 * given spot (its own start, size and timing from that spot's waterline), and when its front
 * reaches a texel and when the backwash uncovers it.
 */
public final class WaveMath {
	private WaveMath() {
	}

	/**
	 * Arrival (texels), exposure and waterline (texels) at a region texel, blended between field
	 * samples water reaches; null if unreachable.
	 */
	public static float[] fieldsAt(ShoreRegion r, float tx, float tz) {
		float per = ShoreRegion.TEXELS_PER_SAMPLE;
		float sx = (tx + 0.5f) / per - 0.5f, sz = (tz + 0.5f) / per - 0.5f;
		int S = r.samples;
		int ix = Math.max(0, Math.min(S - 2, (int) Math.floor(sx))), iz = Math.max(0, Math.min(S - 2, (int) Math.floor(sz)));
		float fx = Math.max(0, Math.min(1, sx - ix)), fz = Math.max(0, Math.min(1, sz - iz));
		float arr = 0, exp = 0, wl = 0, wsum = 0;
		for (int k = 0; k < 4; k++) {
			int ox = k & 1, oz = k >> 1, p = (iz + oz) * S + ix + ox;
			float w = (ox == 1 ? fx : 1 - fx) * (oz == 1 ? fz : 1 - fz);
			if (r.arrival[p] <= -900) continue;
			arr += r.arrival[p] * w;
			exp += r.exposure[p] * w;
			wl += r.waterline[p] * w;
			wsum += w;
		}
		return wsum > 0 ? new float[] {arr / wsum, exp / wsum, wl / wsum} : null;
	}

	/**
	 * The crossing front at a region texel: its arrival (texels), its own waterline, and how strong it
	 * is there (it fades as it falls behind the first front), blended over the samples where it exists
	 * as the bake shader does; null where there is none or it has faded out.
	 */
	public static float[] crossAt(ShoreRegion r, float tx, float tz, FoamSettings s) {
		float[] f = fieldsAt(r, tx, tz);
		if (f == null) return null;
		float per = ShoreRegion.TEXELS_PER_SAMPLE;
		float sx = (tx + 0.5f) / per - 0.5f, sz = (tz + 0.5f) / per - 0.5f;
		int S = r.samples;
		int ix = Math.max(0, Math.min(S - 2, (int) Math.floor(sx))), iz = Math.max(0, Math.min(S - 2, (int) Math.floor(sz)));
		float fx = Math.max(0, Math.min(1, sx - ix)), fz = Math.max(0, Math.min(1, sz - iz));
		float c = 0, cw = 0, wsum = 0;
		for (int k = 0; k < 4; k++) {
			int ox = k & 1, oz = k >> 1, p = (iz + oz) * S + ix + ox;
			float w = (ox == 1 ? fx : 1 - fx) * (oz == 1 ? fz : 1 - fz);
			if (r.cross[p] <= ShoreRegion.UNREACHABLE || r.crossWaterline[p] <= ShoreRegion.UNREACHABLE) continue;
			c += r.cross[p] * w;
			cw += r.crossWaterline[p] * w;
			wsum += w;
		}
		if (wsum <= 0) return null;
		c /= wsum;
		float fade = s.wrap * (1 - (f[0] - c) / (s.wrapLag * 16));
		return fade > 0 ? new float[] {c, cw / wsum, fade} : null;
	}

	/** How much of a wave is left after travelling past where it would normally break. */
	public static float travelLeft(float waterline, FoamSettings s) {
		return waterline >= 0 ? 1 : Math.max(0, 1 + waterline / (s.travelFade * 16));
	}

	/**
	 * One wave as it is at a spot: its start there (late by up to {@code peel} seconds), its size
	 * there (after travelling round, if it had to), and when its run-up starts (when the front
	 * reaches this spot's waterline), pauses and finishes draining.
	 */
	public record Local(double t0, float amp, double Tu, double tR0, double tB0, double tEnd, float att) {
	}

	/** Wave {@code w} at world block position (bx, bz) whose water came ashore at arrival {@code waterline}. */
	public static Local local(WaveSchedule.Wave w, double bx, double bz, float waterline, FoamSettings s) {
		double sc = Math.max(1, s.peelScale), px = bx / sc, pz = bz / sc;
		double delay = s.peel * vnoise(px, pz, 7001 + w.ox() * 131 + w.oy());
		double size = Math.max(0.15, 1 + s.waveVary * (2 * vnoise(px, pz, 7013 + w.lx() * 131 + w.ly()) - 1));
		float att = travelLeft(waterline, s);
		double t0 = w.t0() + delay;
		float amp = (float) (w.amp() * size * att);
		double Tu = 2 * s.runup * amp / s.speed;
		double tR0 = t0 + Math.max(0, s.approach * 16 - waterline) / (s.speed * 16);
		double tB0 = tR0 + Tu + s.stall;
		return new Local(t0, amp, Tu, tR0, tB0, tB0 + s.backwash, att);
	}

	/** When a wave's front reaches a texel, when the lip stops laying lace there and when the backwash uncovers it (seconds). */
	public record Times(double tCross, double tUnc, float Re, double tLeave, boolean laid) {
	}

	/**
	 * Closed-form times for wave {@code w} (as it is here: {@code l}) at a world texel (wx, wz) whose
	 * position from its own waterline is {@code xl} (0 at sea, negative up the beach) and exposure
	 * {@code exposure}; null if the wave never gets there. {@code noise} is the RGBA noise texture (its
	 * red channel is the front's raggedness).
	 */
	public static Times times(WaveSchedule.Wave w, Local l, int wx, int wz, float xl, float exposure, FoamSettings s, byte[] noise) {
		float e = 1 + (exposure - 1) * s.shelter;
		float jv = jv(w, wx, wz, s, noise);
		float Re = s.runup * 16 * l.amp() * e;
		if (Re <= 0) return null;
		double TuE = l.Tu() * Math.min(1, e);
		float uR = (jv - xl) / Re;
		if (uR > 1) return null;
		double tCross = uR <= 0 ? l.tR0() : l.tR0() + TuE * (1 - Math.sqrt(1 - uR));
		double uL = (jv - xl + s.lipWidth + 3) / Re;
		double tLeave = uL <= 1 ? l.tR0() + TuE * (1 - Math.sqrt(1 - Math.max(0, uL))) : l.tB0();
		double k2 = (xl + Re - 0.7 * jv) / (Re + 6);
		double tUnc = k2 >= 1 ? l.tEnd() : l.tB0() + s.backwash * Math.sqrt(Math.max(0, k2));
		return new Times(tCross, tUnc, Re, tLeave, uL > 0);
	}

	/** The front's raggedness at a world texel for wave {@code w} (texels). */
	public static float jv(WaveSchedule.Wave w, int wx, int wz, FoamSettings s, byte[] noise) {
		return ((noise[((((wz + w.oy()) & 255) * 256) + ((wx + w.ox()) & 255)) * 4] & 255) / 255f - 0.5f) * 2 * s.jag;
	}

	/** Smooth value noise on the integer lattice, the same as the bake shader's vnoise. */
	public static double vnoise(double x, double z, int seed) {
		double fx = Math.floor(x), fz = Math.floor(z);
		int ix = (int) fx, iz = (int) fz;
		double tx = x - fx, tz = z - fz;
		tx = tx * tx * (3 - 2 * tx);
		tz = tz * tz * (3 - 2 * tz);
		double a = hash3(ix, iz, seed), b = hash3(ix + 1, iz, seed), c = hash3(ix, iz + 1, seed), d = hash3(ix + 1, iz + 1, seed);
		return a + (b - a) * tx + (c - a) * tz + (a - b - c + d) * tx * tz;
	}

	static double hash3(int x, int y, int s) {
		int h = x * 374761393 + y * 668265263 + s * 1442695041;
		h = (h ^ (h >>> 13)) * 1274126177;
		h ^= h >>> 16;
		return (h & 0xFFFFFFFFL) / 4294967296.0;
	}
}
