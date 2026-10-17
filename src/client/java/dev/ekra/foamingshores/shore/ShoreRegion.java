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
 * <li>{@code waterline}: where each point's path from open water came ashore (the arrival at that
 *     waterline; at sea, the point's own arrival). A front sweeps through all the water at the
 *     wave's speed, round islands and up inlets, and a beach's run-up is timed and measured from its
 *     own waterline. Blurred over land so run-up edges stay round.</li>
 * <li>{@code far}: the arrival field blurred over many blocks at sea: fronts far out follow it, so
 *     they run nearly straight and bend to the real coast as they come in.</li>
 * <li>{@code cross}, {@code crossWaterline}: the second front where two meet (behind an island, over
 *     a narrow spit): the arrival field keeps only the earlier, so their meeting line is a seam, and
 *     this continues each front past it (and where it came ashore) so the two cross instead of
 *     merging. UNREACHABLE where there is none.</li>
 * <li>{@code isle}: per point, land too small to shape the waves: fronts ignore it and the foam
 *     line runs over it; its shore only gets a wash.</li>
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
	/**
	 * Per column, the water block whose surface it shows ({@link ShoreSnapshot#NO_SURFACE} for none:
	 * the body's layer, a stream's top over land, a lower pool's top) and that surface's corner
	 * heights above it as vanilla draws them (north-west, south-west, south-east, north-east).
	 */
	public final int[] surfY;
	public final float[] corners;
	/**
	 * Water lower than the body's (a pit's pool, the foot of a waterfall): a drain. Fronts never
	 * reach it; whatever reaches its edge pours in and is gone.
	 */
	public final boolean[] low;
	/** Per column: water at the body's layer in a river biome, and how deep it is (1 to 4 blocks). */
	public final boolean[] river;
	/** Per column: still water (at the body's layer, neither river nor ocean or beach: lakes, ponds, side pockets). */
	public final boolean[] still;
	public final byte[] depth;
	/**
	 * Rivers (null when the region has none; see {@link Rivers}): per field sample the current
	 * (texels per second), how much river it is (fading into the sea at a mouth) and how shallow;
	 * per region texel the rocks' wake, foam pillow and foam trails (RGBA, 0..255).
	 */
	public float[] riverFx, riverFy, riverBlend, riverShallow;
	public byte[] riverDecor;
	/**
	 * Still water's fetch (see {@link Rivers}): per field sample, for 16 wind directions (the
	 * direction the wind blows toward, i * 22.5 degrees from +x toward +z), how much open water lies
	 * upwind, as 0..255 of {@link #FETCH_MAX} texels; null when the region has no inland water.
	 */
	public byte[] fetch;
	public static final int FETCH_DIRS = 16;
	public static final float FETCH_MAX = 24 * 16;
	/** Whether each column's water at the body's layer lies in an ocean or beach biome. */
	public final boolean[] ocean;
	/**
	 * Whether each block column is water that belongs to a body with waves: one with ocean (or
	 * beach) water in it, big enough or reaching beyond the region. Lakes, ponds and rivers on their
	 * own stay calm; a river or lake joined to the sea gets waves coming in from it, fading.
	 */
	public final boolean[] sea;
	/**
	 * Dips: connected land one block below beach level, where water that runs in cannot drain back
	 * out and recedes as a puddle instead. {@code dipId} per block (-1 for none); per sample,
	 * {@code dipEntry} is the arrival value where water enters that dip (its highest arrival, on the
	 * rim facing the sea): the puddle recedes back toward it.
	 */
	public final short[] dipId;
	public final float[] dipEntry;
	public final float[] waterline, far, cross, crossWaterline;
	public final boolean[] isle;

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
		this.corners = snap.corners.clone();
		this.surfY = snap.surfY.clone();
		this.low = new boolean[size * size];
		this.river = new boolean[size * size];
		this.ocean = new boolean[size * size];
		this.still = new boolean[size * size];
		this.depth = snap.depth.clone();
		this.sea = new boolean[size * size];
		this.dipId = new short[size * size];
		this.dipEntry = new float[samples * samples];
		this.waterline = new float[n];
		this.far = new float[n];
		this.cross = new float[n];
		this.crossWaterline = new float[n];
		this.isle = new boolean[n];
	}

	/**
	 * What the fields depend on. {@code runupReachTexels}: how far up a beach a wave can run (the
	 * strip where crossing fronts matter on land); {@code wrapAngle}: how head-on (degrees) two
	 * fronts must meet to cross; {@code minIsland}: land bodies smaller than this (blocks) do not
	 * shape the waves.
	 */
	public record Settings(int minBodyBlocks, float openWaterBlocks, float downhill, int smoothingTexels,
		float farSmoothBlocks, float wrapAngle, int minIsland, float runupReachTexels,
		float riverSpeed, float riverRock, float riverWake, float riverTrail, float backwater) {
	}

	public static ShoreRegion build(ShoreSnapshot snap, Settings settings) {
		return build(snap, settings, null);
	}

	/** {@code memory} keeps rivers running the way they ran before (null: worked out afresh). */
	public static ShoreRegion build(ShoreSnapshot snap, Settings settings, RiverMemory memory) {
		int size = snap.size;
		int waterY = snap.waterY;
		ShoreRegion r = new ShoreRegion(snap, waterY);

		boolean[] surfaceWater = new boolean[size * size];
		for (int i = 0; i < size * size; i++) {
			surfaceWater[i] = snap.water[i] && snap.topY[i] == waterY;
			r.water[i] = surfaceWater[i];
			int lv = surfaceWater[i] ? 0 : snap.topY[i] - waterY;
			r.low[i] = snap.water[i] && snap.topY[i] < waterY;
			r.river[i] = surfaceWater[i] && snap.river[i];
			r.ocean[i] = surfaceWater[i] && snap.ocean[i];
			r.still[i] = surfaceWater[i] && !r.river[i] && !r.ocean[i];
			// A lower pool is never stepped into, however shallow the step: as deep as a drop.
			r.level[i] = (byte) (r.low[i] ? -4 : Math.max(-4, Math.min(1, lv)));
		}
		markBodies(r, surfaceWater, settings.minBodyBlocks());
		buildFields(r, settings);
		markDips(r);
		Rivers.build(r, settings, memory);
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
			boolean ocean = false;
			for (int i : comp) if (r.ocean[i]) { ocean = true; break; }
			if (ocean && (edge || comp.size() >= minBody)) for (int i : comp) r.sea[i] = true;
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
		float[] raw = signed(seaS, toSea, toLand, n, tps);
		int blurR = Math.max(0, Math.round(settings.smoothingTexels() / 2f / tps));
		float[] shore = blurR > 0 ? Fields.boxBlur(Fields.boxBlur(raw, S, S, blurR), S, S, blurR) : raw;
		System.arraycopy(shore, 0, r.shoreDist, 0, n);

		// Small pieces of land (a lone block, a rock) do not shape the waves: the fronts' distance to
		// the coast ignores them, so a wave passes them as a line instead of ringing each one. They
		// still block the water. Land touching the region's edge may be bigger than it looks: kept.
		int[] isleSize = smallIslands(r, settings.minIsland());
		boolean[] seaW = new boolean[n], landW = new boolean[n];
		for (int p = 0; p < n; p++) {
			int bi = ((p / S) / RES) * size + (p % S) / RES;
			r.isle[p] = isleSize[bi] > 0;
			seaW[p] = seaS[p] || r.isle[p];
			landW[p] = !seaW[p];
		}
		float[] rawW = signed(seaW, Fields.distanceTo(seaW, S, S), Fields.distanceTo(landW, S, S), n, tps);

		// Arrival: waves start only in open water and travel through water from there. Water never
		// steps up a level, never enters a wall or a drop of two or more, and runs further downhill.
		// Open water behind a narrower gap (a cove) is not where waves start either: only water whose
		// whole way out is open water, and no wider than its way out, so a wave never appears from
		// nowhere as a ring inside a cove before the real one comes through the mouth.
		float open = settings.openWaterBlocks() * 16f;
		double downCost = 1.0 / settings.downhill();
		double[] cost = new double[n];
		float widest = 0;
		for (int p = 0; p < n; p++) if (seaS[p]) widest = Math.max(widest, rawW[p]);
		float sourceAt = Math.min(open, widest * 0.9f);
		float[] way = Fields.openWay(rawW, seaS, S, S);
		// Only in ocean water: waves come in from the sea and fade travelling up a river or into a lake.
		for (int p = 0; p < n; p++) cost[p] = seaS[p] && r.ocean[((p / S) / RES) * size + (p % S) / RES] && rawW[p] >= sourceAt && way[p] >= sourceAt
			&& rawW[p] <= way[p] + tps ? -rawW[p] : Fields.FAR;
		int[] pred = new int[n], order = new int[n];
		int settled = Fields.dijkstra(cost, S, S, (p, q, diag) -> {
			if (seaS[q] && !seaS[p]) return -1;
			int lp = lvl[p], lq = lvl[q];
			if (lq > lp || lq > 0 || lq < -1) return -1;
			double step = (diag ? Math.sqrt(2) : 1) * tps;
			return lq < 0 ? step * downCost : step;
		}, pred, order, null);
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
		float[] arr = r.arrival;

		// Which way the front travels at each point: the step from the previous point on its path,
		// smoothed along the path.
		float[] dirX = new float[n], dirY = new float[n];
		for (int i = 0; i < settled; i++) {
			int q = order[i], pp = pred[q];
			if (pp < 0) continue;
			float vx = (q % S) - (pp % S), vy = (q / S) - (pp / S), l0 = (float) Math.hypot(vx, vy);
			vx /= l0;
			vy /= l0;
			if (dirX[pp] != 0 || dirY[pp] != 0) {
				vx = 0.85f * dirX[pp] + 0.15f * vx;
				vy = 0.85f * dirY[pp] + 0.15f * vy;
			}
			float l = (float) Math.hypot(vx, vy);
			if (l == 0) l = 1;
			dirX[q] = vx / l;
			dirY[q] = vy / l;
		}

		// Waterline: at sea a point's own arrival; on land the arrival where its path came ashore.
		float[] dW = r.waterline;
		java.util.Arrays.fill(dW, UNREACHABLE);
		for (int i = 0; i < settled; i++) {
			int q = order[i];
			if (arr[q] <= UNREACHABLE) continue;
			if (seaS[q]) { dW[q] = arr[q]; continue; }
			int pp = pred[q];
			dW[q] = pp >= 0 && arr[pp] > UNREACHABLE ? (seaS[pp] ? arr[pp] : dW[pp]) : arr[q];
		}
		{
			// Over land it comes from the blocky shoreline, so as it is the run-up would trace block
			// edges: blur it over land, and leave an ordinary beach (a waterline within a few texels of
			// where waves normally break) measured exactly as before.
			int rr = Math.max(1, blurR);
			float[] a3 = new float[n], m3 = new float[n];
			for (int p = 0; p < n; p++) if (!seaS[p] && dW[p] > UNREACHABLE) { a3[p] = dW[p]; m3[p] = 1; }
			float[] A3 = Fields.boxBlur(Fields.boxBlur(a3, S, S, rr), S, S, rr), M3 = Fields.boxBlur(Fields.boxBlur(m3, S, S, rr), S, S, rr);
			for (int p = 0; p < n; p++) {
				if (seaS[p] || dW[p] <= UNREACHABLE) continue;
				dW[p] = Math.min(0, (M3[p] > 1e-4f ? A3[p] / M3[p] : dW[p]) + 8);
			}
		}

		// Far field: the arrival blurred over farSmooth blocks, over land too (its signed distance)
		// so the blur stays centred on the coast instead of being pulled out to sea.
		{
			int rr = Math.max(1, Math.round(settings.farSmoothBlocks() * 16f / 2f / tps));
			float[] a2 = new float[n];
			for (int p = 0; p < n; p++) a2[p] = arr[p] > UNREACHABLE ? arr[p] : Math.max(-200, r.shoreDist[p]);
			float[] A2 = Fields.boxBlur(Fields.boxBlur(a2, S, S, rr), S, S, rr);
			for (int p = 0; p < n; p++) r.far[p] = seaS[p] && arr[p] > UNREACHABLE ? A2[p] : arr[p];
		}

		crossing(r, settings, seaS, lvl, dirX, dirY);

		// Exposure: the largest sea distance within 4 blocks; narrow inlets score low. A small
		// island's own shore gets only a wash, smaller the smaller it is.
		float[] clipped = new float[n];
		for (int p = 0; p < n; p++) clipped[p] = Math.max(0, Math.min(64, rawW[p]));
		float[] mx = Fields.maxFilter(clipped, S, S, 4 * RES);
		for (int p = 0; p < n; p++) mx[p] = Math.min(1, mx[p] / 40f);
		System.arraycopy(Fields.boxBlur(mx, S, S, Math.max(1, RES / 2)), 0, r.exposure, 0, n);
		for (int p = 0; p < n; p++) {
			int k = isleSize[((p / S) / RES) * size + (p % S) / RES];
			if (k > 0) r.exposure[p] = Math.min(r.exposure[p], 0.25f + 0.5f * k / settings.minIsland());
		}
	}

	private static float[] signed(boolean[] sea, float[] toSea, float[] toLand, int n, float tps) {
		float[] out = new float[n];
		for (int p = 0; p < n; p++) {
			float v = sea[p] ? toLand[p] * tps - tps / 2 : -(toSea[p] * tps - tps / 2);
			out[p] = Math.max(-200, Math.min(200, v));
		}
		return out;
	}

	/** Per block, the size of its land body if smaller than {@code min} blocks and clear of the region's edge, else 0. */
	private static int[] smallIslands(ShoreRegion r, int min) {
		int size = r.size;
		int[] out = new int[size * size];
		boolean[] seen = new boolean[size * size];
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		java.util.List<Integer> comp = new java.util.ArrayList<>();
		for (int s0 = 0; s0 < size * size; s0++) {
			if (seen[s0] || r.water[s0]) continue;
			comp.clear();
			queue.add(s0);
			seen[s0] = true;
			boolean edge = false;
			while (!queue.isEmpty() && comp.size() <= min) {
				int i = queue.poll();
				comp.add(i);
				int x = i % size, z = i / size;
				if (x == 0 || z == 0 || x == size - 1 || z == size - 1) edge = true;
				int[] nb = {x > 0 ? i - 1 : -1, x < size - 1 ? i + 1 : -1, z > 0 ? i - size : -1, z < size - 1 ? i + size : -1};
				for (int j : nb) if (j >= 0 && !seen[j] && !r.water[j]) { seen[j] = true; queue.add(j); }
			}
			// A big body stops counting early; flood the rest of it so it is not mistaken for islands.
			boolean big = !queue.isEmpty();
			while (!queue.isEmpty()) {
				int i = queue.poll();
				int x = i % size, z = i / size;
				int[] nb = {x > 0 ? i - 1 : -1, x < size - 1 ? i + 1 : -1, z > 0 ? i - size : -1, z < size - 1 ? i + size : -1};
				for (int j : nb) if (j >= 0 && !seen[j] && !r.water[j]) { seen[j] = true; queue.add(j); }
			}
			if (!big && !edge && comp.size() < min) for (int i : comp) out[i] = comp.size();
		}
		return out;
	}

	/**
	 * The second front. Seams are neighbouring points whose fronts travel in clearly different
	 * directions (more than wrapAngle apart: converging, as where two fronts meet). From the seam each
	 * front carries on into the other's side, as if the other were not there (Huygens' construction),
	 * never back the way the water came, with the first front's rules for where water may go; on land
	 * only within the strip a wave can run up. Each side of a seam starts the other side's front, with
	 * the other side's waterline.
	 */
	private static void crossing(ShoreRegion r, Settings settings, boolean[] seaS, byte[] lvl, float[] dirX, float[] dirY) {
		int S = r.samples, n = S * S;
		float tps = TEXELS_PER_SAMPLE;
		float[] arr = r.arrival, dW = r.waterline;
		float reach = -settings.runupReachTexels();
		double cos = Math.cos(Math.toRadians(settings.wrapAngle()));
		boolean[] seam = new boolean[n];
		float[] partW = new float[n];
		// The second front's own travel direction: it starts as the other side's and carries on.
		float[] d2x = new float[n], d2y = new float[n];
		for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
			int p = y * S + x;
			if (arr[p] <= UNREACHABLE || (dirX[p] == 0 && dirY[p] == 0) || (!seaS[p] && arr[p] - dW[p] < reach)) continue;
			int[] qs = {x + 1 < S ? p + 1 : -1, y + 1 < S ? p + S : -1, x + 1 < S && y + 1 < S ? p + S + 1 : -1};
			for (int q : qs) {
				if (q < 0 || arr[q] <= UNREACHABLE || (dirX[q] == 0 && dirY[q] == 0) || seaS[p] != seaS[q]) continue;
				if (!seaS[q] && arr[q] - dW[q] < reach) continue;
				if (dirX[p] * dirX[q] + dirY[p] * dirY[q] < cos) {
					seam[p] = true;
					seam[q] = true;
					partW[p] = dW[q];
					partW[q] = dW[p];
					d2x[p] = dirX[q];
					d2y[p] = dirY[q];
					d2x[q] = dirX[p];
					d2y[q] = dirY[p];
				}
			}
		}
		double[] cost = new double[n];
		for (int p = 0; p < n; p++) cost[p] = seam[p] ? -arr[p] : Fields.FAR;
		int[] pred = new int[n], order = new int[n];
		double maxLag = 12 * 16;
		int settled = Fields.dijkstra(cost, S, S, (p, q, diag) -> {
			if (arr[q] <= UNREACHABLE || (seaS[q] && !seaS[p])) return -1;
			int lp = lvl[p], lq = lvl[q];
			if (lq > lp || lq > 0 || lq < -1) return -1;
			if (!seaS[q] && arr[q] - dW[q] < reach) return -1;
			int dx = (q % S) - (p % S), dy = (q / S) - (p / S);
			double len = diag ? Math.sqrt(2) : 1;
			// Never back the way this second front came (it may well run against the first front).
			if ((dx * d2x[p] + dy * d2y[p]) / len < -0.3) return -1;
			return len * tps;
		}, pred, order, (p, q, nc) -> {
			if (nc + arr[q] >= maxLag) return false;
			float vx = (q % S) - (p % S), vy = (q / S) - (p / S), l0 = (float) Math.hypot(vx, vy);
			float x = 0.85f * d2x[p] + 0.15f * vx / l0, y = 0.85f * d2y[p] + 0.15f * vy / l0, l = (float) Math.hypot(x, y);
			d2x[q] = l > 0 ? x / l : 0;
			d2y[q] = l > 0 ? y / l : 0;
			return true;
		});
		java.util.Arrays.fill(r.cross, UNREACHABLE);
		java.util.Arrays.fill(r.crossWaterline, UNREACHABLE);
		for (int i = 0; i < settled; i++) {
			int q = order[i], pp = pred[q];
			float v = (float) -cost[q];
			if (pp < 0) r.crossWaterline[q] = seaS[q] ? arr[q] : partW[q];
			else r.crossWaterline[q] = seaS[q] ? v : seaS[pp] ? (float) -cost[pp] : r.crossWaterline[pp];
			if (!seam[q]) r.cross[q] = Math.max(-200, Math.min(200, v));
		}
	}
}
