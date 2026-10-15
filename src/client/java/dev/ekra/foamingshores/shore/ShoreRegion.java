package dev.ekra.foamingshores.shore;

import java.util.ArrayDeque;

/**
 * The fields the waves are drawn from, for one square region. Built off-thread from a
 * {@link ShoreSnapshot}. Field values are in texels (16 per block) so the bake shader can compare
 * them with texel positions directly; they are sampled at {@link #RES} points per block.
 *
 * <ul>
 * <li>{@code arrival}: where along a wave's path each point lies; the wave front is a contour of it.
 *     Equals the distance to the shore along open coast, but is measured through water from open
 *     water, so narrow straits fill from their mouths. Negative inland.</li>
 * <li>{@code shoreDist}: plain signed distance to the waterline (for the wet line and shading).</li>
 * <li>{@code exposure}: 0..1, how much open water lies nearby; coves get smaller waves.</li>
 * <li>{@code level}: per block, the height of its top relative to the water surface in blocks
 *     (+1 wall and above, -1 dip, -4 drop and below, clamped), 0 for beach-level land and water.</li>
 * </ul>
 */
public final class ShoreRegion {
	public static final int RES = 4;
	public static final float TEXELS_PER_SAMPLE = 16f / RES;
	/** Points unreachable by water get this arrival value. */
	public static final float UNREACHABLE = -1000f;

	public final int originX, originZ, size, waterY;
	public final int samples; // size * RES
	public final float[] arrival, shoreDist, exposure;
	public final byte[] level;
	/** Y of each column's topmost solid-or-fluid block (leaves ignored), unclamped. */
	public final int[] topY;
	/** Whether each block column's surface is water at the region's water height (sea or pool). */
	public final boolean[] water;
	/** Whether each block column is water that belongs to a body big enough to have waves. */
	public final boolean[] sea;
	/**
	 * Dips: connected land one block below beach level, where water that runs in cannot drain back
	 * out and recedes as a puddle instead. {@code dipId} per block (-1 for none); per sample,
	 * {@code dipEntry} is the arrival value where water enters that dip (its highest arrival, on the
	 * rim facing the sea): the puddle recedes back toward it.
	 */
	public final short[] dipId;
	public final float[] dipEntry;

	private ShoreRegion(ShoreSnapshot snap, int waterY) {
		this.originX = snap.originX;
		this.originZ = snap.originZ;
		this.size = snap.size;
		this.waterY = waterY;
		this.samples = size * RES;
		int n = samples * samples;
		this.arrival = new float[n];
		this.shoreDist = new float[n];
		this.exposure = new float[n];
		this.level = new byte[size * size];
		this.topY = snap.topY.clone();
		this.water = new boolean[size * size];
		this.sea = new boolean[size * size];
		this.dipId = new short[size * size];
		this.dipEntry = new float[samples * samples];
	}

	public record Settings(int minBodyBlocks, float openWaterBlocks, float downhill, int smoothingTexels) {
	}

	public static ShoreRegion build(ShoreSnapshot snap, Settings settings) {
		int size = snap.size;
		int waterY = snap.waterY;
		ShoreRegion r = new ShoreRegion(snap, waterY);

		boolean[] surfaceWater = new boolean[size * size];
		for (int i = 0; i < size * size; i++) {
			surfaceWater[i] = snap.water[i] && snap.topY[i] == waterY;
			r.water[i] = surfaceWater[i];
			int lv = surfaceWater[i] ? 0 : snap.topY[i] - waterY;
			r.level[i] = (byte) Math.max(-4, Math.min(1, lv));
		}
		markBodies(r, surfaceWater, settings.minBodyBlocks());
		buildFields(r, settings);
		markDips(r);
		return r;
	}

	/** Water touching the region's edge, or at least minBody blocks of it, gets waves. */
	private static void markBodies(ShoreRegion r, boolean[] surfaceWater, int minBody) {
		int size = r.size;
		boolean[] seen = new boolean[size * size];
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		java.util.List<Integer> comp = new java.util.ArrayList<>();
		for (int s = 0; s < size * size; s++) {
			if (!surfaceWater[s] || seen[s]) continue;
			comp.clear();
			queue.add(s);
			seen[s] = true;
			boolean edge = false;
			while (!queue.isEmpty()) {
				int i = queue.poll();
				comp.add(i);
				int x = i % size, z = i / size;
				if (x == 0 || z == 0 || x == size - 1 || z == size - 1) edge = true;
				if (x > 0) visit(i - 1, surfaceWater, seen, queue);
				if (x < size - 1) visit(i + 1, surfaceWater, seen, queue);
				if (z > 0) visit(i - size, surfaceWater, seen, queue);
				if (z < size - 1) visit(i + size, surfaceWater, seen, queue);
			}
			if (edge || comp.size() >= minBody) for (int i : comp) r.sea[i] = true;
		}
	}

	private static void markDips(ShoreRegion r) {
		int size = r.size, S = r.samples;
		java.util.Arrays.fill(r.dipId, (short) -1);
		boolean[] dip = new boolean[size * size];
		for (int i = 0; i < size * size; i++) dip[i] = !r.water[i] && r.level[i] == -1;
		java.util.List<java.util.List<Integer>> comps = new java.util.ArrayList<>();
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		for (int s = 0; s < size * size; s++) {
			if (!dip[s] || r.dipId[s] >= 0 || comps.size() >= Short.MAX_VALUE) continue;
			short id = (short) comps.size();
			java.util.List<Integer> comp = new java.util.ArrayList<>();
			comps.add(comp);
			r.dipId[s] = id;
			queue.add(s);
			while (!queue.isEmpty()) {
				int i = queue.poll();
				comp.add(i);
				int x = i % size, z = i / size;
				int[] nb = {x > 0 ? i - 1 : -1, x < size - 1 ? i + 1 : -1, z > 0 ? i - size : -1, z < size - 1 ? i + size : -1};
				for (int j : nb) if (j >= 0 && dip[j] && r.dipId[j] < 0) { r.dipId[j] = id; queue.add(j); }
			}
		}
		// Each dip's entry: the highest arrival among its reachable samples.
		java.util.Arrays.fill(r.dipEntry, UNREACHABLE);
		float[] entry = new float[comps.size()];
		java.util.Arrays.fill(entry, UNREACHABLE);
		for (int p = 0; p < S * S; p++) {
			int id = r.dipId[((p / S) / RES) * size + (p % S) / RES];
			if (id >= 0 && r.arrival[p] > UNREACHABLE) entry[id] = Math.max(entry[id], r.arrival[p]);
		}
		for (int p = 0; p < S * S; p++) {
			int id = r.dipId[((p / S) / RES) * size + (p % S) / RES];
			if (id >= 0) r.dipEntry[p] = entry[id];
		}
	}

	private static void visit(int j, boolean[] water, boolean[] seen, ArrayDeque<Integer> queue) {
		if (water[j] && !seen[j]) { seen[j] = true; queue.add(j); }
	}

	private static void buildFields(ShoreRegion r, Settings settings) {
		int S = r.samples, n = S * S, size = r.size;
		float tps = TEXELS_PER_SAMPLE;
		boolean[] seaS = new boolean[n], landS = new boolean[n];
		byte[] lvl = new byte[n];
		for (int sz = 0; sz < S; sz++) for (int sx = 0; sx < S; sx++) {
			int bi = (sz / RES) * size + sx / RES, p = sz * S + sx;
			seaS[p] = r.sea[bi];
			landS[p] = !r.sea[bi];
			lvl[p] = r.level[bi];
		}
		// Signed distance to the waterline, in texels.
		float[] toSea = Fields.distanceTo(seaS, S, S), toLand = Fields.distanceTo(landS, S, S);
		float[] raw = new float[n];
		for (int p = 0; p < n; p++) {
			float v = seaS[p] ? toLand[p] * tps - tps / 2 : -(toSea[p] * tps - tps / 2);
			raw[p] = Math.max(-200, Math.min(200, v));
		}
		int blurR = Math.max(0, Math.round(settings.smoothingTexels() / 2f / tps));
		float[] shore = blurR > 0 ? Fields.boxBlur(Fields.boxBlur(raw, S, S, blurR), S, S, blurR) : raw;
		System.arraycopy(shore, 0, r.shoreDist, 0, n);

		// Arrival: waves start only in open water and travel through water from there. Water never
		// steps up a level, never enters a wall or a drop of two or more, and runs further downhill.
		float open = settings.openWaterBlocks() * 16f;
		double downCost = 1.0 / settings.downhill();
		double[] cost = new double[n];
		float widest = 0;
		for (int p = 0; p < n; p++) if (seaS[p]) widest = Math.max(widest, raw[p]);
		float sourceAt = Math.min(open, widest * 0.9f);
		for (int p = 0; p < n; p++) cost[p] = seaS[p] && raw[p] >= sourceAt ? -raw[p] : Fields.FAR;
		Fields.dijkstra(cost, S, S, (p, q, diag) -> {
			if (seaS[q] && !seaS[p]) return -1;
			int lp = lvl[p], lq = lvl[q];
			if (lq > lp || lq > 0 || lq < -1) return -1;
			double step = (diag ? Math.sqrt(2) : 1) * tps;
			return lq < 0 ? step * downCost : step;
		});
		// Smooth only over points water can reach, so walls and drops stay sharp.
		float[] a = new float[n], m = new float[n];
		for (int p = 0; p < n; p++) if (cost[p] < Fields.FAR) { a[p] = (float) Math.max(-200, Math.min(200, -cost[p])); m[p] = 1; }
		if (blurR > 0) {
			float[] A = Fields.boxBlur(Fields.boxBlur(a, S, S, blurR), S, S, blurR);
			float[] M = Fields.boxBlur(Fields.boxBlur(m, S, S, blurR), S, S, blurR);
			for (int p = 0; p < n; p++) r.arrival[p] = m[p] > 0 && M[p] > 1e-4f ? A[p] / M[p] : UNREACHABLE;
		} else {
			for (int p = 0; p < n; p++) r.arrival[p] = m[p] > 0 ? a[p] : UNREACHABLE;
		}

		// Exposure: the largest sea distance within 4 blocks; narrow inlets score low.
		float[] clipped = new float[n];
		for (int p = 0; p < n; p++) clipped[p] = Math.max(0, Math.min(64, raw[p]));
		float[] mx = Fields.maxFilter(clipped, S, S, 4 * RES);
		for (int p = 0; p < n; p++) mx[p] = Math.min(1, mx[p] / 40f);
		System.arraycopy(Fields.boxBlur(mx, S, S, Math.max(1, RES / 2)), 0, r.exposure, 0, n);
	}
}
