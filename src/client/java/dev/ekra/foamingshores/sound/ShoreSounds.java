package dev.ekra.foamingshores.sound;

import dev.ekra.foamingshores.FoamSettings;
import dev.ekra.foamingshores.shore.ShoreRegion;
import dev.ekra.foamingshores.wave.WaveMath;
import dev.ekra.foamingshores.wave.WaveSchedule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a region's shore sounds come from, and what they are doing at a given moment: the port of
 * the playground's computeSound. Every land block touching open water is a source at that shared
 * edge; wall faces slap when a front reaches their foot; drop faces pour; dips fill and soak. All
 * of it is worked out from the same closed-form wave times as the picture, so it can be evaluated
 * for any moment, on any thread: the sound is computed for the time it will actually be heard.
 * Immutable once built.
 */
public final class ShoreSounds {
	/** A point that makes sound: world position (blocks), the world texel it samples, arrival and exposure there. */
	record Point(double x, double y, double z, int wx, int wz, float dp, float exposure) {
	}

	/** A wall face, drop face or dip: where it is heard from and the texels whose waves drive it. */
	record Group(double x, double y, double z, Point[] texels) {
	}

	private final Point[] shore;
	private final Group[] walls, drops, dips;

	private ShoreSounds(Point[] shore, Group[] walls, Group[] drops, Group[] dips) {
		this.shore = shore;
		this.walls = walls;
		this.drops = drops;
		this.dips = dips;
	}

	/**
	 * The sound sources of a region. {@code wallFaces} and {@code dropFaces} are the face table's
	 * rows: edge texel (region texels), step along the edge, and direction out of the face.
	 */
	public static ShoreSounds build(ShoreRegion r, List<int[]> wallFaces, List<int[]> dropFaces) {
		List<Point> shore = new ArrayList<>();
		for (int bz = 0; bz < r.size; bz++) {
			for (int bx = 0; bx < r.size; bx++) {
				int bi = bz * r.size + bx;
				if (r.water[bi] || r.level[bi] > 0) continue;
				for (int dir = 0; dir < 4; dir++) {
					int dx = dir == 2 ? -1 : dir == 3 ? 1 : 0, dz = dir == 0 ? -1 : dir == 1 ? 1 : 0;
					int nx = bx + dx, nz = bz + dz;
					if (nx < 0 || nz < 0 || nx >= r.size || nz >= r.size) continue;
					int ni = nz * r.size + nx;
					if (!r.water[ni] || !r.sea[ni]) continue;
					// The waterline texel: the middle of the shared edge, on the land side.
					int tx = bx * 16 + (dx < 0 ? 0 : dx > 0 ? 15 : 8), tz = bz * 16 + (dz < 0 ? 0 : dz > 0 ? 15 : 8);
					Point p = point(r, tx, tz, r.originX + bx + 0.5 + dx * 0.5, r.waterY + 1, r.originZ + bz + 0.5 + dz * 0.5);
					if (p != null) shore.add(p);
				}
			}
		}
		return new ShoreSounds(shore.toArray(Point[]::new), faces(r, wallFaces, true), faces(r, dropFaces, false), dips(r));
	}

	private static Point point(ShoreRegion r, int tx, int tz, double x, double y, double z) {
		float[] f = WaveMath.fieldsAt(r, tx, tz);
		if (f == null) return null; // a wall straight out of the sea: no beach for a wave to wash
		return new Point(x, y, z, r.originX * 16 + tx, r.originZ * 16 + tz, f[0], f[1]);
	}

	/** Face rows are (edge x, edge z, step x, step z, out x, out z, top y); a wall's texels are at its foot. */
	private static Group[] faces(ShoreRegion r, List<int[]> rows, boolean wall) {
		List<Group> out = new ArrayList<>();
		for (int[] row : rows) {
			List<Point> texels = new ArrayList<>();
			for (int u = 0; u < 16; u++) {
				int tx = row[0] + row[2] * u, tz = row[1] + row[3] * u;
				Point p = point(r, tx, tz, 0, 0, 0);
				if (p != null) texels.add(p);
			}
			if (texels.isEmpty()) continue;
			double cx = r.originX + (row[0] + row[2] * 7.5 + 0.5) / 16.0 + row[4] * (wall ? 1 / 32.0 : 0.5 / 16);
			double cz = r.originZ + (row[1] + row[3] * 7.5 + 0.5) / 16.0 + row[5] * (wall ? 1 / 32.0 : 0.5 / 16);
			out.add(new Group(cx, row[6] / 16.0, cz, texels.toArray(Point[]::new)));
		}
		return out.toArray(Group[]::new);
	}

	/** One group per dip, heard from its middle, driven by the middle texel of each of its blocks. */
	private static Group[] dips(ShoreRegion r) {
		Map<Integer, List<Point>> byDip = new HashMap<>();
		for (int bz = 0; bz < r.size; bz++) {
			for (int bx = 0; bx < r.size; bx++) {
				int id = r.dipId[bz * r.size + bx];
				if (id < 0) continue;
				Point p = point(r, bx * 16 + 8, bz * 16 + 8, r.originX + bx + 0.5, r.waterY, r.originZ + bz + 0.5);
				if (p != null) byDip.computeIfAbsent(id, k -> new ArrayList<>()).add(p);
			}
		}
		List<Group> out = new ArrayList<>();
		for (List<Point> pts : byDip.values()) {
			double x = 0, z = 0;
			for (Point p : pts) {
				x += p.x();
				z += p.z();
			}
			out.add(new Group(x / pts.size(), pts.get(0).y(), z / pts.size(), pts.toArray(Point[]::new)));
		}
		return out.toArray(Group[]::new);
	}

	// ---- What it sounds like now ------------------------------------------------------------------

	/** Where the listener is and which way is their right (unit vector). */
	public record Ear(double x, double y, double z, double rx, double ry, double rz) {
	}

	/** Per-stream state: when each wall last slapped, so a ragged front makes one slap, not a stutter. */
	static final class State {
		final Map<Integer, Double> slapCool = new HashMap<>();
		ShoreSounds owner;
	}

	/** One control step's worth: per layer {left, right, brightness}, and the events that start in it. */
	record Control(double[][] layers, List<SurfSynth.Event> events) {
	}

	private static final class Gains {
		double l, r, near;
	}

	/**
	 * Gains in each ear for a source, fading linearly to nothing at {@code range} blocks like vanilla
	 * sounds; false when out of earshot.
	 */
	private static boolean ear(Ear e, double x, double y, double z, double range, Gains out) {
		double dx = x - e.x(), dy = y - e.y(), dz = z - e.z(), dist = Math.sqrt(dx * dx + dy * dy + dz * dz), att = 1 - dist / range;
		if (att <= 0) return false;
		double side = (dx * e.rx() + dy * e.ry() + dz * e.rz()) / Math.max(1.5, dist);
		double pan = Math.max(-1, Math.min(1, side)) * 0.85, a = (pan + 1) * Math.PI / 4;
		out.l = Math.cos(a) * att;
		out.r = Math.sin(a) * att;
		out.near = att;
		return true;
	}

	/** One wave's phases (seconds) and its run-up length (texels) before shelter. */
	private record Phases(WaveSchedule.Wave w, double tR0, double Tu, double tB0, double tEnd, double Tb, float R) {
		static Phases of(WaveSchedule.Wave w, FoamSettings s) {
			double Ta = s.approach / s.speed, Tu = 2 * s.runup * w.amp() / s.speed;
			double tR0 = w.t0() + Ta, tB0 = tR0 + Tu + s.stall;
			return new Phases(w, tR0, Tu, tB0, tB0 + s.backwash, s.backwash, s.runup * 16 * w.amp());
		}
	}

	/** A wave's history at a point (as in the bake shader); {@code reached} false if it never gets there. */
	private static final class Times {
		boolean reached, laid;
		double tCross, tLeave;
		float Re, jv;
	}

	private static float jv(WaveSchedule.Wave w, int wx, int wz, FoamSettings s, byte[] noise) {
		return ((noise[((((wz + w.oy()) & 255) * 256) + ((wx + w.ox()) & 255)) * 4] & 255) / 255f - 0.5f) * 2 * s.jag;
	}

	private static void times(Phases ph, Point p, double e, FoamSettings s, byte[] noise, Times t) {
		t.jv = jv(ph.w(), p.wx(), p.wz(), s, noise);
		t.Re = (float) (ph.R() * e);
		double TuE = ph.Tu() * Math.min(1, e);
		double uR = (t.jv - p.dp()) / t.Re;
		t.reached = uR <= 1;
		if (!t.reached) return;
		t.tCross = uR <= 0 ? ph.tR0() : ph.tR0() + TuE * (1 - Math.sqrt(1 - uR));
		double uL = (t.jv - p.dp() + s.lipWidth + 3) / t.Re;
		t.laid = uL > 0;
		t.tLeave = uL <= 1 ? ph.tR0() + TuE * (1 - Math.sqrt(1 - Math.max(0, uL))) : ph.tB0();
	}

	/**
	 * The control step ending at {@code T} ({@code dt} seconds long). Sources sum by power, as
	 * unrelated noises do, so a long beach is louder than one block of it but not twenty times louder.
	 */
	Control compute(State st, double T, double dt, List<WaveSchedule.Wave> waves, FoamSettings s, byte[] noise, Ear ear) {
		if (st.owner != this) {
			st.owner = this;
			st.slapCool.clear();
		}
		double range = s.hearing, t0 = T - dt;
		double[][] pw = new double[SurfSynth.LAYERS][2];
		List<SurfSynth.Event> events = new ArrayList<>();
		double washB = 0, washW = 0, cL = 0, cR = 0, cT = 0, cW = 0, cB = 0;
		List<Phases> act = new ArrayList<>(waves.size());
		for (WaveSchedule.Wave w : waves) if (w.t0() <= T) act.add(Phases.of(w, s));
		Gains far = new Gains(), near = new Gains(), g = new Gains();
		Times t = new Times();

		for (Point p : shore) {
			if (!ear(ear, p.x(), p.y(), p.z(), range * 3, far)) continue;
			boolean heard = ear(ear, p.x(), p.y(), p.z(), range, near);
			double e = 1 + (p.exposure() - 1) * s.shelter;
			add(pw, SurfSynth.BED, far, 0.12 * e); // the sea is never quite silent
			for (Phases ph : act) {
				double amp = ph.w().amp();
				// The distant roar swells as a wave comes in and dies away after it breaks.
				double swell = T < ph.tR0() ? Math.pow(Math.max(0, (T - ph.w().t0()) / (ph.tR0() - ph.w().t0())), 2) : Math.exp(-(T - ph.tR0()) / 1.8);
				add(pw, SurfSynth.BED, far, 0.35 * amp * e * swell);
				if (!heard) continue;
				// It breaks as its front reaches the waterline here; the raggedness spreads that along the coast.
				double tc = ph.tR0() - 0.15 - jv(ph.w(), p.wx(), p.wz(), s, noise) / (s.speed * 16);
				if (tc > t0 && tc <= T) {
					double a = amp * e * Math.min(1, Math.max(0, 1 + p.dp() / 16)); // well up an inlet it only washes in
					if (a > 0) {
						cL += sq(near.l * a);
						cR += sq(near.r * a);
						cT += (tc - t0) * a;
						cW += a;
						cB += amp * (0.5 + 0.5 * near.near) * a;
					}
				}
				if (T < ph.tR0()) continue;
				times(ph, p, e, s, noise, t);
				if (!t.reached) continue;
				double TuE = ph.Tu() * Math.min(1, e);
				if (T >= t.tCross && T < ph.tR0() + TuE) {
					// Running up: as loud and bright as it is fast, slowing to a stop.
					double v = 1 - (T - ph.tR0()) / TuE, a = amp * e * v;
					add(pw, SurfSynth.WASH, near, a);
					washB += v * (0.5 + 0.5 * near.near) * a;
					washW += a;
				}
				if (T >= ph.tB0() && T < ph.tEnd()) {
					// Draining back: a darker rush, and the foam it leaves fizzing.
					double a = amp * e * Math.sin(Math.PI * (T - ph.tB0()) / ph.Tb());
					add(pw, SurfSynth.WASH, near, 0.45 * a);
					washB += 0.1 * 0.45 * a;
					washW += 0.45 * a;
					add(pw, SurfSynth.FIZZ, near, 0.8 * a);
				}
				// Fresh foam crackles briefly as the lip lays it, then goes quiet.
				if (t.laid && T >= t.tLeave && T < t.tLeave + 3) add(pw, SurfSynth.FIZZ, near, 0.3 * amp * e * Math.pow(0.5, (T - t.tLeave) / 0.6));
			}
		}
		if (cW > 0) {
			double k = 0.25 * s.sndCrash;
			events.add(new SurfSynth.Event(true, cT / cW, Math.min(1, Math.sqrt(cL + cR)), cB / cW, Math.sqrt(cL) * k, Math.sqrt(cR) * k));
		}

		// Walls: a slap the moment a front reaches the foot.
		for (int i = 0; i < walls.length; i++) {
			Group wall = walls[i];
			if (!ear(ear, wall.x(), wall.y(), wall.z(), range, g)) continue;
			Double last = st.slapCool.get(i);
			if (last != null && last > T - 0.5 && last <= T) continue;
			int count = 0;
			double energy = 0, when = Double.MAX_VALUE;
			for (Point p : wall.texels()) {
				double e = 1 + (p.exposure() - 1) * s.shelter;
				for (Phases ph : act) {
					if (T < ph.tR0()) continue;
					times(ph, p, e, s, noise, t);
					if (!t.reached || t.tCross <= t0 || t.tCross > T) continue;
					count++;
					energy = Math.max(energy, Math.sqrt(Math.min(1, Math.max(0, (p.dp() + t.Re) / t.Re))));
					when = Math.min(when, t.tCross);
				}
			}
			if (count == 0) continue;
			st.slapCool.put(i, T);
			double a = 0.75 * energy * Math.min(1, Math.sqrt(count / 6.0)) * s.sndSlap;
			events.add(new SurfSynth.Event(false, Math.max(0, when - t0), a, 1, g.l * a, g.r * a));
		}

		// Drops: water pours over for as long as a wave pushes past the edge.
		for (Group drop : drops) {
			if (!ear(ear, drop.x(), drop.y(), drop.z(), range, g)) continue;
			int pouring = 0;
			for (Point p : drop.texels()) {
				double e = 1 + (p.exposure() - 1) * s.shelter;
				for (Phases ph : act) {
					if (T < ph.tR0() || T > ph.tB0()) continue;
					times(ph, p, e, s, noise, t);
					if (t.reached && T >= t.tCross) {
						pouring++;
						break;
					}
				}
			}
			if (pouring > 0) add(pw, SurfSynth.TRICKLE, g, 0.9 * Math.sqrt(pouring / 16.0));
		}

		// Dips: water runs in while a wave covers part of one, then soaks away.
		for (Group dip : dips) {
			if (!ear(ear, dip.x(), dip.y(), dip.z(), range, g)) continue;
			boolean pour = false;
			int wet = 0;
			for (Point p : dip.texels()) {
				double e = 1 + (p.exposure() - 1) * s.shelter;
				boolean pointWet = false;
				for (Phases ph : act) {
					if (T < ph.tR0()) continue;
					// The puddle reaches a little past the wave and soaks away over about the soak time.
					double past = (jv(ph.w(), p.wx(), p.wz(), s, noise) - ph.R() * e) - p.dp();
					if (past > s.puddleSpread) continue;
					if (T >= ph.tB0() && T < ph.tB0() + s.soak * 0.65) pointWet = true;
					if (!pour && T < ph.tB0()) {
						times(ph, p, e, s, noise, t);
						pour = t.reached && T >= t.tCross;
					}
				}
				if (pointWet) wet++;
			}
			double size = Math.min(1, Math.sqrt(dip.texels().length) / 3);
			if (pour) add(pw, SurfSynth.TRICKLE, g, 0.5 * size);
			if (wet > 0) add(pw, SurfSynth.SOAK, g, Math.sqrt(wet / (double) dip.texels().length) * size);
		}

		double[] norm = {0.25, 0.5, 0.1625, 1, 1};
		double[] mix = {s.sndBed, s.sndWash, s.sndFizz, s.sndTrickle, s.sndTrickle};
		double[][] layers = new double[SurfSynth.LAYERS][3];
		for (int k = 0; k < SurfSynth.LAYERS; k++) {
			double m = norm[k] * mix[k];
			layers[k][0] = Math.sqrt(pw[k][0]) * m;
			layers[k][1] = Math.sqrt(pw[k][1]) * m;
		}
		layers[SurfSynth.WASH][2] = washW > 0 ? washB / washW : 0;
		return new Control(layers, events);
	}

	private static double sq(double v) {
		return v * v;
	}

	private static void add(double[][] pw, int layer, Gains g, double a) {
		pw[layer][0] += sq(g.l * a);
		pw[layer][1] += sq(g.r * a);
	}
}
