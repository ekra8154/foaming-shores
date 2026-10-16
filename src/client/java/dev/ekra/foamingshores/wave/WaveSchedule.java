package dev.ekra.foamingshores.wave;

import dev.ekra.foamingshores.FoamSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * When waves arrive, as a pure function of time: time is cut into slots of one wave set each, and
 * a slot's waves are hashed from the slot number. Any moment's recent waves are a lookup, never a
 * simulation, so every client (and a replay) sees the same waves at the same game time.
 */
public final class WaveSchedule {
	private WaveSchedule() {
	}

	/** One wave: when it set off (seconds), how far it runs (0..1 of the run-up) and its noise offsets. */
	public record Wave(double t0, float amp, int ox, int oy, int lx, int ly, int bx, int by) {
	}

	/** Waves that set off at or before {@code time} and may still show (running, or leaving lace or wet sand). */
	public static List<Wave> recent(double time, FoamSettings s, int max) {
		double slot = slotLength(s);
		double keep = s.waveDuration(1 + s.waveVary) + s.peel + s.travelFade / s.speed + Math.min(40, Math.max(s.dryTime, 8 * Math.max(s.laceWet, Math.max(s.laceDry, s.laceSea))));
		long last = (long) Math.floor(time / slot);
		long first = (long) Math.floor((time - keep) / slot) - 1;
		List<Wave> out = new ArrayList<>();
		for (long k = first; k <= last; k++) addSet(k, slot, s, time - keep, time, out);
		// Newest last; keep the most recent `max`.
		return out.size() > max ? out.subList(out.size() - max, out.size()) : out;
	}

	private static double slotLength(FoamSettings s) {
		return Math.max(1, s.setSize) * s.period + s.lull;
	}

	private static void addSet(long k, double slot, FoamSettings s, double from, double to, List<Wave> out) {
		int count = Math.max(1, s.setSize + (int) Math.floor(hash(k, 1) * 3) - 1);
		double t = k * slot + hash(k, 2) * s.lull * 0.3;
		for (int i = 0; i < count; i++) {
			if (i > 0) t += s.period * (0.8 + 0.4 * hash(k, 10 + i));
			if (t > to) break;
			if (t < from) continue;
			// Waves in the middle of a set run furthest.
			float peak = (float) Math.sin(Math.PI * (i + 0.5) / count);
			float amp = 0.62f + 0.38f * peak * (0.8f + 0.2f * (float) hash(k, 30 + i));
			out.add(new Wave(t, amp,
				(int) (hash(k, 40 + i) * 256), (int) (hash(k, 50 + i) * 256),
				(int) (hash(k, 60 + i) * 256), (int) (hash(k, 70 + i) * 256),
				(int) (hash(k, 80 + i) * 256), (int) (hash(k, 90 + i) * 256)));
		}
	}

	/** A well-mixed hash of (slot, salt) in [0, 1). */
	static double hash(long k, int salt) {
		long h = k * 0x9E3779B97F4A7C15L + salt * 0xC2B2AE3D27D4EB4FL;
		h ^= h >>> 33;
		h *= 0xFF51AFD7ED558CCDL;
		h ^= h >>> 33;
		h *= 0xC4CEB9FE1A85EC53L;
		h ^= h >>> 33;
		return (h >>> 11) * 0x1.0p-53;
	}
}
