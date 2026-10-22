package dev.ekra.foamingshores.shore;

import java.util.ArrayDeque;

/**
 * The fields the waves are drawn from, for one square region. Built off-thread from a
 * {@link ShoreSnapshot}. Field values are in texels (16 per block) so the bake shader can compare
 * them with texel positions directly; they are sampled at {@link #res} points per block ({@link #RES}
 * for the finest level). A coarser level covering more ground can be a finer level's parent: the
 * parent decides what needs the wider view (which water is the open sea, where fronts start, which
 * way rivers run) and near its edge the finer level's fields are blended into the parent's, so where
 * the drawing goes over from one to the other nothing jumps.
 *
 * <ul>
 * <li>{@code arrival}: where along a wave's path each point lies; the wave front is a contour of it.
 *     Over the open sea it is the distance to the shore smoothed over farSmooth blocks, so a front
 *     keeps one simple shape all the way in instead of taking on every headland and bay as it
 *     nears them; elsewhere it is measured through water from there, so narrow straits and coves
 *     fill from their mouths. Negative inland.</li>
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
	/** Field samples per block of the finest level. */
	public static final int RES = 4;
	/** Points unreachable by water get this arrival value. */
	public static final float UNREACHABLE = -1000f;
	/**
	 * How far (texels) the far field may lead or lag the arrival. Fronts take {@code straight} of it
	 * at most, and below about 60 texels of lag a front still always runs toward the shore.
	 */
	private static final float FAR_PULL = 32f;

	public final int originX, originZ, size, waterY;
	/** Field samples per block, texels per field sample, and region texels per river decor texel. */
	public final int res;
	public final float tps;
	public final int decorScale;
	public final int samples; // size * res
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
	 * Ocean or beach water joined to the open ocean: a stretch of it reaching the region's edge, or
	 * big enough to be the sea itself. Waves start only here; a patch of beach biome water inland
	 * (on a lake or a river's bank) is not the sea, however wide.
	 */
	final boolean[] openSea;
	/** Ocean or beach water stretches of at least this many blocks count as the open sea even clear of the region's edge. */
	private static final int OPEN_SEA_BLOCKS = 512;
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
	/** Chunks that were missing from the snapshot (their columns count as open water). */
	public final java.util.Set<Long> missingChunks;
	/**
	 * What a finer level takes from this one: where fronts start (open water this far from land),
	 * each sea sample's way out, the small islands, and the rocks a river's shape ignores.
	 */
	float sourceAt;
	float[] way;
	/** How long the build took, in ms: the waves' fields, the rivers (and of that the river decor), conform. */
	public long[] phaseMs;
	volatile long decorMs;
	int[] isleBlocks;
	boolean[] smallLand;

	private ShoreRegion(ShoreSnapshot snap, int waterY, int res, int decorScale) {
		this.originX = snap.originX;
		this.originZ = snap.originZ;
		this.size = snap.size;
		this.waterY = waterY;
		this.res = res;
		this.tps = 16f / res;
		this.decorScale = decorScale;
		this.missingChunks = java.util.Set.copyOf(snap.missingChunks);
		this.samples = size * res;
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
		this.openSea = new boolean[size * size];
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
	 * shape the waves; {@code approachTexels}, {@code straight}: where fronts start and how much they
	 * follow the far field at sea (as the bake shader draws them).
	 */
	public record Settings(int minBodyBlocks, float openWaterBlocks, float downhill, int smoothingTexels,
		float farSmoothBlocks, float wrapAngle, int minIsland, float runupReachTexels,
		float riverSpeed, float riverRock, float riverWake, float riverTrail, float backwater, float approachTexels, float straight) {
	}

	/**
	 * A coarser level this one sits inside: within {@code marginBlocks} of this region's edge the
	 * fields are the parent's, over the next {@code bandBlocks} they blend into this region's own.
	 */
	public record Parent(ShoreRegion region, int marginBlocks, int bandBlocks) {
	}

	public static ShoreRegion build(ShoreSnapshot snap, Settings settings) {
		return build(snap, settings, null);
	}

	/** {@code memory} keeps rivers running the way they ran before (null: worked out afresh). */
	public static ShoreRegion build(ShoreSnapshot snap, Settings settings, RiverMemory memory) {
		return build(snap, settings, memory, RES, 1, null);
	}

	/**
	 * Builds at {@code res} field samples per block, with river decor at one texel per
	 * {@code decorScale} region texels; with a {@code parent}, taking its wider view (its rivers'
	 * ways replace {@code memory}'s).
	 */
	public static ShoreRegion build(ShoreSnapshot snap, Settings settings, RiverMemory memory, int res, int decorScale, Parent parent) {
		int size = snap.size;
		int waterY = snap.waterY;
		ShoreRegion r = new ShoreRegion(snap, waterY, res, decorScale);
		ShoreRegion up = parent == null ? null : parent.region();

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
		markOpenSea(r);
		if (up != null) {
			// Which water is the open sea, as the wider view sees it.
			for (int i = 0; i < size * size; i++) {
				int j = up.blockAt(r.originX + i % size, r.originZ + i / size);
				if (j < 0) continue;
				r.sea[i] = up.sea[j];
				r.openSea[i] = up.openSea[j];
			}
		}
		// The rivers only need the water and its kinds, not the waves' fields: both at once.
		long[] ms = new long[4];
		Fields.inParallel(() -> {
			long t0 = System.nanoTime();
			buildFields(r, settings, up);
			markDips(r);
			ms[0] = (System.nanoTime() - t0) / 1_000_000;
		}, () -> {
			long t0 = System.nanoTime();
			Rivers.build(r, settings, memory, up);
			ms[1] = (System.nanoTime() - t0) / 1_000_000;
		});
		long t0 = System.nanoTime();
		if (parent != null) conform(r, parent);
		ms[2] = r.decorMs;
		ms[3] = (System.nanoTime() - t0) / 1_000_000;
		r.phaseMs = ms;
		return r;
	}

	/**
	 * This region's index of the block at world (x, z), or -1 when it lies outside or in a chunk the
	 * snapshot did not have (taken for open water).
	 */
	int blockAt(int x, int z) {
		int bx = x - originX, bz = z - originZ;
		if (bx < 0 || bz < 0 || bx >= size || bz >= size) return -1;
		// The key as ChunkPos.pack makes it (this class stays free of the game's classes for the offline checks).
		if (!missingChunks.isEmpty() && missingChunks.contains((x >> 4) & 0xFFFFFFFFL | ((z >> 4) & 0xFFFFFFFFL) << 32)) return -1;
		return bz * size + bx;
	}

	/**
	 * A field at world texel (wx, wz), blended between the samples round it (with {@code reach},
	 * only those where it is not UNREACHABLE, as the bake shader blends); NaN outside the region,
	 * UNREACHABLE when none round it is reachable.
	 */
	float sampleAt(float[] f, double wx, double wz, float[] reach) {
		double sx = (wx - originX * 16.0) / tps - 0.5, sz = (wz - originZ * 16.0) / tps - 0.5;
		if (sx < -0.5 || sz < -0.5 || sx > samples - 0.5 || sz > samples - 0.5) return Float.NaN;
		int S = samples, ix = Math.max(0, Math.min(S - 2, (int) Math.floor(sx))), iz = Math.max(0, Math.min(S - 2, (int) Math.floor(sz)));
		double u = Math.max(0, Math.min(1, sx - ix)), v = Math.max(0, Math.min(1, sz - iz));
		double sum = 0, wsum = 0;
		for (int k = 0; k < 4; k++) {
			int p = (iz + (k >> 1)) * S + ix + (k & 1);
			double w = ((k & 1) == 1 ? u : 1 - u) * ((k >> 1) == 1 ? v : 1 - v);
			if (reach != null && reach[p] <= UNREACHABLE) continue;
			sum += f[p] * w;
			wsum += w;
		}
		return wsum > 1e-9 ? (float) (sum / wsum) : UNREACHABLE;
	}

	private int nearestSample(double wx, double wz) {
		int sx = (int) Math.floor((wx - originX * 16.0) / tps), sz = (int) Math.floor((wz - originZ * 16.0) / tps);
		return sx < 0 || sz < 0 || sx >= samples || sz >= samples ? -1 : sz * samples + sx;
	}

	/**
	 * Near its edge a region knows less than its parent (water past the edge, a body's true size), so
	 * there its fields go over to the parent's: the parent's alone within marginBlocks of the edge,
	 * blending into its own over the next bandBlocks.
	 */
	private static void conform(ShoreRegion r, Parent parent) {
		ShoreRegion up = parent.region();
		int S = r.samples, n = S * S, US = up.samples;
		float margin = parent.marginBlocks(), band = Math.max(1, parent.bandBlocks());
		if (r.riverFx == null && up.riverFx != null) {
			r.riverFx = new float[n];
			r.riverFy = new float[n];
			r.riverBlend = new float[n];
			r.riverShallow = new float[n];
		}
		if (r.fetch == null && up.fetch != null) r.fetch = new byte[n * FETCH_DIRS];
		Fields.ranges(S, n, (z0, z1) -> {
			int[] at = new int[4];
			double[] wt = new double[4];
			for (int p = z0 * S; p < z1 * S; p++) {
				float bx = (p % S + 0.5f) / r.res, bz = (p / S + 0.5f) / r.res;
				float edge = Math.min(Math.min(bx, bz), Math.min(r.size - bx, r.size - bz));
				if (edge >= margin + band) continue;
				float t = Math.max(0, Math.min(1, (margin + band - edge) / band)), w = t * t * (3 - 2 * t);
				double wx = r.originX * 16.0 + (p % S + 0.5) * r.tps, wz = r.originZ * 16.0 + (p / S + 0.5) * r.tps;
				// The parent's four samples round this point and their weights (as sampleAt finds them).
				double sx = (wx - up.originX * 16.0) / up.tps - 0.5, sz = (wz - up.originZ * 16.0) / up.tps - 0.5;
				if (sx < -0.5 || sz < -0.5 || sx > US - 0.5 || sz > US - 0.5) continue;
				int ix = Math.max(0, Math.min(US - 2, (int) Math.floor(sx))), iz = Math.max(0, Math.min(US - 2, (int) Math.floor(sz)));
				double u = Math.max(0, Math.min(1, sx - ix)), v = Math.max(0, Math.min(1, sz - iz));
				for (int k = 0; k < 4; k++) {
					at[k] = (iz + (k >> 1)) * US + ix + (k & 1);
					wt[k] = ((k & 1) == 1 ? u : 1 - u) * ((k >> 1) == 1 ? v : 1 - v);
				}
				r.arrival[p] = blendReach(r.arrival[p], mix(up.arrival, up.arrival, at, wt), w);
				r.waterline[p] = blendReach(r.waterline[p], mix(up.waterline, up.waterline, at, wt), w);
				r.far[p] = blendReach(r.far[p], mix(up.far, up.arrival, at, wt), w);
				r.cross[p] = blendReach(r.cross[p], mix(up.cross, up.cross, at, wt), w);
				r.crossWaterline[p] = blendReach(r.crossWaterline[p], mix(up.crossWaterline, up.crossWaterline, at, wt), w);
				r.dipEntry[p] = blendReach(r.dipEntry[p], mix(up.dipEntry, up.dipEntry, at, wt), w);
				r.shoreDist[p] = blend(r.shoreDist[p], mix(up.shoreDist, null, at, wt), w);
				r.exposure[p] = blend(r.exposure[p], mix(up.exposure, null, at, wt), w);
				if (r.riverFx != null) {
					boolean has = up.riverFx != null;
					r.riverFx[p] = blend(r.riverFx[p], has ? mix(up.riverFx, null, at, wt) : 0, w);
					r.riverFy[p] = blend(r.riverFy[p], has ? mix(up.riverFy, null, at, wt) : 0, w);
					r.riverBlend[p] = blend(r.riverBlend[p], has ? mix(up.riverBlend, null, at, wt) : 0, w);
					r.riverShallow[p] = blend(r.riverShallow[p], has ? mix(up.riverShallow, null, at, wt) : 0, w);
				}
				if (r.fetch != null && up.fetch != null) {
					int q = up.nearestSample(wx, wz);
					if (q < 0) continue;
					for (int d = 0; d < FETCH_DIRS; d++) {
						int mine = r.fetch[p * FETCH_DIRS + d] & 255, theirs = up.fetch[q * FETCH_DIRS + d] & 255;
						r.fetch[p * FETCH_DIRS + d] = (byte) Math.round(mine + (theirs - mine) * w);
					}
				}
			}
		});
	}

	/** A field blended over four samples (with {@code reach}, only where it is not UNREACHABLE). */
	private static float mix(float[] f, float[] reach, int[] at, double[] wt) {
		double sum = 0, wsum = 0;
		for (int k = 0; k < 4; k++) {
			if (reach != null && reach[at[k]] <= UNREACHABLE) continue;
			sum += f[at[k]] * wt[k];
			wsum += wt[k];
		}
		return wsum > 1e-9 ? (float) (sum / wsum) : UNREACHABLE;
	}

	private static float blend(float mine, float theirs, float w) {
		return Float.isNaN(theirs) ? mine : mine + (theirs - mine) * w;
	}

	/** As {@link #blend}, but where only one side is reachable, that of the side weighing more. */
	private static float blendReach(float mine, float theirs, float w) {
		if (Float.isNaN(theirs)) return mine;
		boolean a = mine > UNREACHABLE, b = theirs > UNREACHABLE;
		if (a && b) return mine + (theirs - mine) * w;
		return w < 0.5f ? mine : theirs;
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

	private static void markOpenSea(ShoreRegion r) {
		int size = r.size;
		boolean[] seen = new boolean[size * size];
		int[] queue = new int[size * size];
		for (int s0 = 0; s0 < size * size; s0++) {
			if (!r.ocean[s0] || seen[s0]) continue;
			int h = 0, t = 0;
			boolean edge = false;
			queue[t++] = s0;
			seen[s0] = true;
			while (h < t) {
				int i = queue[h++], x = i % size, z = i / size;
				if (x == 0 || z == 0 || x == size - 1 || z == size - 1) edge = true;
				for (int k = 0; k < 4; k++) {
					int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0);
					if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
					int j = nz * size + nx;
					if (r.ocean[j] && !seen[j]) { seen[j] = true; queue[t++] = j; }
				}
			}
			if (edge || t >= OPEN_SEA_BLOCKS) for (int k = 0; k < t; k++) r.openSea[queue[k]] = true;
		}
	}

	private static void markDips(ShoreRegion r) {
		int size = r.size, S = r.samples, RES = r.res;
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

	private static void buildFields(ShoreRegion r, Settings settings, ShoreRegion wide) {
		int S = r.samples, n = S * S, size = r.size, RES = r.res;
		float tps = r.tps;
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
		if (wide != null) {
			for (int i = 0; i < size * size; i++) {
				int j = wide.blockAt(r.originX + i % size, r.originZ + i / size);
				if (j >= 0) isleSize[i] = wide.isleBlocks[j];
			}
		}
		r.isleBlocks = isleSize;
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
		// Open water is water at least openWater blocks from land whose way out to the sea is as
		// wide, and no wider than its way out, so a wave never appears from nowhere as a ring
		// inside a cove before the real one comes through the mouth. There, and on the strip
		// between it and the shore it faces, its arrival is the distance to the
		// shore smoothed over farSmooth blocks: a front is a contour of that all the way in, so it
		// never gains a bulge it did not start with, and each beach's run-up starts when that same
		// front reaches it (a bay's head a little early, a headland's tip a little late).
		float open = settings.openWaterBlocks() * 16f;
		double downCost = 1.0 / settings.downhill();
		double[] cost = new double[n];
		float widest = 0;
		for (int p = 0; p < n; p++) if (seaS[p]) widest = Math.max(widest, rawW[p]);
		float sourceAt = wide != null ? wide.sourceAt : Math.min(open, widest * 0.9f);
		r.sourceAt = sourceAt;
		float[] way = Fields.openWay(rawW, seaS, S, S);
		if (wide != null) {
			// Each water's way out as the wider view sees it (a cove's mouth may lie past this edge).
			for (int p = 0; p < n; p++) {
				if (!seaS[p]) continue;
				float v = wide.sampleAt(wide.way, r.originX * 16.0 + (p % S + 0.5) * tps, r.originZ * 16.0 + (p / S + 0.5) * tps, null);
				if (!Float.isNaN(v) && v > -Fields.FAR / 2) way[p] = v;
			}
		}
		r.way = way;
		int rrF = Math.max(1, Math.round(settings.farSmoothBlocks() * 16f / 2f / tps));
		float[] smooth = Fields.boxBlur(Fields.boxBlur(rawW, S, S, rrF), S, S, rrF);
		// Only in the open sea: waves come in from it and fade travelling up a river or into a lake.
		// Its open water, and the strip between that and the shore it faces: any water within
		// openWater blocks of it through water (a cove behind a narrow mouth lies further).
		double[] near = new double[n];
		for (int p = 0; p < n; p++) near[p] = seaS[p] && r.openSea[((p / S) / RES) * size + (p % S) / RES] && rawW[p] >= sourceAt
			&& way[p] >= sourceAt && rawW[p] <= way[p] + tps ? 0 : Fields.FAR;
		Fields.dijkstra(near, S, S, (p, q, diag) -> seaS[p] && seaS[q] ? (diag ? Math.sqrt(2) : 1) * tps : -1, null, null,
			(p, q, nc) -> nc <= sourceAt * 1.1 + 2 * tps);
		for (int p = 0; p < n; p++) cost[p] = near[p] < Fields.FAR ? -smooth[p] : Fields.FAR;
		int[] pred = new int[n], order = new int[n];
		int settled = Fields.dijkstra(cost, S, S, (p, q, diag) -> {
			if (seaS[q] && !seaS[p]) return -1;
			int lp = lvl[p], lq = lvl[q];
			if (lq > lp || lq > 0 || lq < -1) return -1;
			double step = (diag ? Math.sqrt(2) : 1) * tps;
			return lq < 0 ? step * downCost : step;
		}, pred, order, null);
		// Where each point's front comes from: the point before it on its path or, in open water
		// (where every point starts its own), the neighbour it is highest toward.
		int[] up = new int[n];
		for (int i = 0; i < settled; i++) {
			int q = order[i], best = pred[q];
			if (best < 0) {
				int x = q % S, y = q / S;
				double hi = cost[q];
				for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
					int X = x + dx, Y = y + dy;
					if ((dx == 0 && dy == 0) || X < 0 || Y < 0 || X >= S || Y >= S) continue;
					int o = Y * S + X;
					if (cost[o] < hi) { hi = cost[o]; best = o; }
				}
			}
			up[q] = best;
		}
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

		// Which way the front travels at each point: the step from where its front comes from,
		// smoothed along the way.
		float[] dirX = new float[n], dirY = new float[n];
		for (int i = 0; i < settled; i++) {
			int q = order[i], pp = up[q];
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
		// so the blur stays centred on the coast instead of being pulled out to sea. Kept within
		// FAR_PULL of the arrival: where land fills most of the blur (a cove, an inlet, water among
		// islands) it would sink far below the arrival, and a front following it would run the wrong
		// way, bulging or breaking off into rings as it comes in.
		{
			int rr = Math.max(1, Math.round(settings.farSmoothBlocks() * 16f / 2f / tps));
			float[] a2 = new float[n];
			for (int p = 0; p < n; p++) a2[p] = arr[p] > UNREACHABLE ? arr[p] : Math.max(-200, r.shoreDist[p]);
			float[] A2 = Fields.boxBlur(Fields.boxBlur(a2, S, S, rr), S, S, rr);
			for (int p = 0; p < n; p++) r.far[p] = seaS[p] && arr[p] > UNREACHABLE ? arr[p] + Math.max(-FAR_PULL, Math.min(FAR_PULL, A2[p] - arr[p])) : arr[p];
		}

		frontField(r, up, order, settled, settings);
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

	/**
	 * The field a front follows (the arrival, pulled toward the far field at sea, as the bake shader
	 * works it out) never rises along the way the water travels, so no stretch of front is born late,
	 * as a ring or a bulge, after the rest has come in; and a front starting in a pocket of open water
	 * starts with the rest, where fronts start. Kept by adjusting the far field the shader reads.
	 */
	private static void frontField(ShoreRegion r, int[] up, int[] order, int settled, Settings st) {
		float ap = st.approachTexels(), str = st.straight();
		if (ap <= 0 || str <= 0) return;
		float[] arr = r.arrival, far = r.far, front = new float[arr.length];
		for (int i = 0; i < settled; i++) {
			int q = order[i], p = up[q];
			float dp = arr[q];
			if (dp <= UNREACHABLE) continue;
			float k = dp > 0 ? Math.min(1, dp / ap) * str : 0;
			float f = dp + (far[q] - dp) * k;
			if (k < 0.05f) { front[q] = f; continue; }
			if (p < 0 || arr[p] <= UNREACHABLE) f = Math.max(f, ap);
			else f = Math.min(f, front[p]);
			front[q] = f;
			far[q] = dp + (f - dp) / k;
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
	 * the other side's waterline. Only over the sea's own water (ocean or beach) and its shores: fronts
	 * coming into a river mouth or a lake from both sides meet and fade as they go, without a second
	 * front following each up it.
	 */
	private static void crossing(ShoreRegion r, Settings settings, boolean[] seaS, byte[] lvl, float[] dirX, float[] dirY) {
		int S = r.samples, n = S * S, RES = r.res;
		float tps = r.tps;
		float[] arr = r.arrival, dW = r.waterline;
		float reach = -settings.runupReachTexels();
		double cos = Math.cos(Math.toRadians(settings.wrapAngle()));
		boolean[] seam = new boolean[n];
		float[] partW = new float[n];
		// The second front's own travel direction: it starts as the other side's and carries on.
		float[] d2x = new float[n], d2y = new float[n];
		boolean[] inland = new boolean[n];
		for (int p = 0; p < n; p++) {
			int bi = ((p / S) / RES) * r.size + (p % S) / RES;
			inland[p] = r.water[bi] && !r.ocean[bi];
		}
		for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
			int p = y * S + x;
			if (inland[p] || arr[p] <= UNREACHABLE || (dirX[p] == 0 && dirY[p] == 0) || (!seaS[p] && arr[p] - dW[p] < reach)) continue;
			int[] qs = {x + 1 < S ? p + 1 : -1, y + 1 < S ? p + S : -1, x + 1 < S && y + 1 < S ? p + S + 1 : -1};
			for (int q : qs) {
				if (q < 0 || inland[q] || arr[q] <= UNREACHABLE || (dirX[q] == 0 && dirY[q] == 0) || seaS[p] != seaS[q]) continue;
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
			if (arr[q] <= UNREACHABLE || inland[q] || (seaS[q] && !seaS[p])) return -1;
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
