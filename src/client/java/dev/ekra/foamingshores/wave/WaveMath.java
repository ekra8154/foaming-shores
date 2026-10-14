package dev.ekra.foamingshores.wave;

import dev.ekra.foamingshores.FoamSettings;
import dev.ekra.foamingshores.shore.ShoreRegion;

/**
 * The CPU side of the stateless wave model, matching foam_bake.fsh exactly where the CPU needs the
 * same answers (splashes at walls, water flowing into dips): field values at a texel, and when a
 * wave's front reaches a texel and when the backwash uncovers it.
 */
public final class WaveMath {
	private WaveMath() {
	}

	/** Arrival (texels) and exposure at a region texel, blended between field samples; null if unreachable. */
	public static float[] fieldsAt(ShoreRegion r, float tx, float tz) {
		float per = ShoreRegion.TEXELS_PER_SAMPLE;
		float sx = (tx + 0.5f) / per - 0.5f, sz = (tz + 0.5f) / per - 0.5f;
		int S = r.samples;
		int ix = Math.max(0, Math.min(S - 2, (int) Math.floor(sx))), iz = Math.max(0, Math.min(S - 2, (int) Math.floor(sz)));
		float fx = Math.max(0, Math.min(1, sx - ix)), fz = Math.max(0, Math.min(1, sz - iz));
		float arr = 0, exp = 0, wsum = 0;
		for (int k = 0; k < 4; k++) {
			int ox = k & 1, oz = k >> 1, p = (iz + oz) * S + ix + ox;
			float w = (ox == 1 ? fx : 1 - fx) * (oz == 1 ? fz : 1 - fz);
			if (r.arrival[p] <= -900) continue;
			arr += r.arrival[p] * w;
			exp += r.exposure[p] * w;
			wsum += w;
		}
		return wsum > 0 ? new float[] {arr / wsum, exp / wsum} : null;
	}

	/** When a wave's front reaches a texel and when the backwash uncovers it again (seconds). */
	public record Times(double tCross, double tUnc, float Re) {
	}

	/**
	 * Closed-form times for one wave at a world texel (wx, wz, any integers) with arrival {@code dp}
	 * and exposure {@code exposure}; null if the wave never gets there. {@code noise} is the RGBA
	 * noise texture (its red channel is the front's raggedness).
	 */
	public static Times times(WaveSchedule.Wave w, int wx, int wz, float dp, float exposure, FoamSettings s, byte[] noise) {
		float e = 1 + (exposure - 1) * s.shelter;
		float jv = ((noise[((((wz + w.oy()) & 255) * 256) + ((wx + w.ox()) & 255)) * 4] & 255) / 255f - 0.5f) * 2 * s.jag;
		float runup = s.runup * 16, speed = s.speed * 16, approach = s.approach * 16;
		float Re = runup * w.amp() * e;
		double Ta = approach / speed, Tu = 2 * runup * w.amp() / speed, TuE = Tu * Math.min(1, e);
		double tR0 = w.t0() + Ta, tB0 = tR0 + Tu + s.stall, tEnd = tB0 + s.backwash;
		float uR = (jv - dp) / Re;
		if (uR > 1) return null;
		double tCross = uR <= 0 ? tR0 : tR0 + TuE * (1 - Math.sqrt(1 - uR));
		double k2 = (dp + Re - 0.7 * jv) / (Re + 6);
		double tUnc = k2 >= 1 ? tEnd : tB0 + s.backwash * Math.sqrt(Math.max(0, k2));
		return new Times(tCross, tUnc, Re);
	}
}
