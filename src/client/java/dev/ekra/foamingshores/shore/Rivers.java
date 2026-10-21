package dev.ekra.foamingshores.shore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinTask;

import dev.ekra.foamingshores.wave.WaveMath;

/**
 * River currents and the foam around rocks (see the playground's rivers). River water is the body's
 * water lying in a river biome.
 * <ul>
 * <li>The axis: the distance to the nearest bank rises from both banks to the middle, so its slope
 *     points across the channel; averaged as a line rather than an arrow (a structure tensor, so
 *     opposite banks do not cancel) it gives the way along the river, bending round rocks and bends.</li>
 * <li>Which way along it: down a smooth "how far up the river" field (the distance to the sea along
 *     the water, or, for a stretch whose sea is out of reach, away from its far end), and then,
 *     stretch by stretch, whatever the {@link RiverMemory} remembers for its chunks: once a river
 *     has a direction it keeps it.</li>
 * <li>Speed: faster where narrow and over the shallows, slower toward the banks.</li>
 * <li>Rocks (land sticking up with river on two sides or more): foam piled on the upstream face, a
 *     V-shaped wake, and foam trails carried downstream, at texel resolution.</li>
 * </ul>
 */
final class Rivers {
	private Rivers() {
	}

	/** A blur radius given in samples at the finest resolution, as samples at this region's. */
	private static int sr(ShoreRegion r, int k) {
		return r.res == ShoreRegion.RES ? k : Math.max(1, Math.round(k * r.res / (float) ShoreRegion.RES));
	}

	/** {@code up}: a coarser level this region sits in, whose rivers run the way these must; or null. */
	static void build(ShoreRegion r, ShoreRegion.Settings settings, RiverMemory memory, ShoreRegion up) {
		int size = r.size, S = r.samples, n = S * S, RES = r.res;
		float tps = r.tps;
		boolean[] water = new boolean[n], land = new boolean[n], rivS = new boolean[n], stillS = new boolean[n], inland = new boolean[n];
		boolean anyRiver = false, anyInland = false;
		for (int p = 0; p < n; p++) {
			int bi = ((p / S) / RES) * size + (p % S) / RES;
			water[p] = r.water[bi];
			land[p] = !water[p];
			rivS[p] = water[p] && r.river[bi];
			stillS[p] = water[p] && r.still[bi];
			inland[p] = rivS[p] || stillS[p];
			anyRiver |= rivS[p];
			anyInland |= inland[p];
		}
		if (!anyInland) return;
		ForkJoinTask<byte[]> fetching = Fields.start(() -> fetch(r, water, rivS, stillS));

		float[] bank = Fields.distanceTo(land, S, S), toRiver = Fields.distanceTo(rivS, S, S);
		for (int p = 0; p < n; p++) {
			bank[p] = Math.max(0, bank[p] * tps - tps / 2);
			toRiver[p] = anyRiver ? Math.max(0, toRiver[p] * tps - tps / 2) : 1e9f;
		}
		// The banks as the river's shape sees them: small land standing in the water (a rock, a
		// placed block) is left out, so the current runs along the river past it instead of
		// circling it.
		boolean[] smallLand = smallLand(r);
		if (up != null) {
			// The rocks as the wider view sees them (a piece of land cut by this edge may be a rock).
			for (int i = 0; i < size * size; i++) {
				int j = up.blockAt(r.originX + i % size, r.originZ + i / size);
				if (j >= 0) smallLand[i] = up.smallLand[j];
			}
		}
		r.smallLand = smallLand;
		boolean[] shore = new boolean[n];
		for (int p = 0; p < n; p++) shore[p] = land[p] && !smallLand[((p / S) / RES) * size + (p % S) / RES];
		float[] shoreBank = Fields.distanceTo(shore, S, S);
		for (int p = 0; p < n; p++) shoreBank[p] = Math.max(0, shoreBank[p] * tps - tps / 2);
		// How much of a river's current carries on into the water it runs into (a lake, a backwater,
		// the sea), and which river sample it came from: along the water from the river, spending
		// itself block by block until it is gone. It carries the backwater setting plus one and a
		// half times the river's own half-width (measured on river water only), so a big river
		// pushes far out and a stream barely past its mouth; (1 - the share of that gone)^2.
		float[] pull = new float[n];
		int[] src = new int[n];
		java.util.Arrays.fill(src, -1);
		if (anyRiver) {
			boolean[] notRiver = new boolean[n];
			for (int p = 0; p < n; p++) notRiver[p] = !rivS[p];
			float[] riverBank = Fields.distanceTo(notRiver, S, S);
			for (int p = 0; p < n; p++) riverBank[p] = Math.max(0, riverBank[p] * tps - tps / 2);
			float[] riverHalf = halfWidth(riverBank, S, tps), reachOf = new float[n];
			double[] gone = new double[n];
			java.util.Arrays.fill(gone, Fields.FAR);
			double back = Math.max(0.5, settings.backwater()) * 16;
			for (int p = 0; p < n; p++) {
				if (!rivS[p]) continue;
				gone[p] = 0;
				reachOf[p] = (float) Math.min(MAX_REACH, back + 1.5 * riverHalf[p]);
				src[p] = p;
			}
			Fields.dijkstra(gone, S, S, (p, q, diag) -> water[q] && !rivS[q] ? (diag ? Math.sqrt(2) : 1) * tps / reachOf[p] : -1, null, null,
				(p, q, c) -> { reachOf[q] = reachOf[p]; src[q] = src[p]; return true; });
			for (int p = 0; p < n; p++) {
				if (rivS[p]) pull[p] = 1;
				else if (gone[p] < 1) pull[p] = (float) ((1 - gone[p]) * (1 - gone[p]));
				else src[p] = -1;
			}
		}
		// How much river or still water (fading into the sea where they meet) and how shallow.
		float[] rv = new float[n], sv = new float[n];
		for (int p = 0; p < n; p++) {
			int bi = ((p / S) / RES) * size + (p % S) / RES;
			rv[p] = inland[p] ? 1 : 0;
			sv[p] = inland[p] ? (r.depth[bi] <= 1 ? 1 : r.depth[bi] == 2 ? 0.5f : 0) : 0;
		}
		float[] rvB = Fields.boxBlur(Fields.boxBlur(rv, S, S, sr(r, 1)), S, S, sr(r, 1)), svB = Fields.boxBlur(sv, S, S, sr(r, 1));
		float[] blend = new float[n], shallow = new float[n], flowB = new float[n];
		for (int p = 0; p < n; p++) {
			if (land[p]) continue;
			// At sea, river water shows as far as a river's current carries out (thinning as it goes).
			blend[p] = inland[p] ? Math.min(1, Math.max(Math.max(0.5f, rvB[p]) * 2 - 1, rvB[p] * 1.6f)) : Math.max(Math.max(0, rvB[p] * 2 - 1) * 0.6f, pull[p]);
			shallow[p] = svB[p];
			// Where a current runs: the river, and lakes and the sea as far as it carries into them.
			flowB[p] = rivS[p] ? blend[p] : stillS[p] ? pull[p] * blend[p] : pull[p];
		}
		r.riverBlend = blend;
		r.riverShallow = shallow;

		float[] fx = new float[n], fy = new float[n], hx = new float[n], hy = new float[n], hb = new float[n];
		boolean[] dom = new boolean[n];
		if (anyRiver) {
			// The axis.
			for (int p = 0; p < n; p++) dom[p] = water[p] && (toRiver[p] <= 40 || pull[p] > 0.05f);
			float[] jxx = new float[n], jxy = new float[n], jyy = new float[n];
			for (int y = 1; y < S - 1; y++) for (int x = 1; x < S - 1; x++) {
				int p = y * S + x;
				if (!dom[p]) continue;
				float gx = (shoreBank[p + 1] - shoreBank[p - 1]) * 0.5f, gy = (shoreBank[p + S] - shoreBank[p - S]) * 0.5f;
				jxx[p] = gx * gx;
				jxy[p] = gx * gy;
				jyy[p] = gy * gy;
			}
			// Averaged over the river's own width, all the way across: over about its width each
			// way, so every slice of a channel gets one way along it, the straight line through the
			// stretch, rather than following whichever bank is nearer, bay by bay and step by step
			// (land in a staircase of blocks turned the way a quarter turn at each step). Near land the
			// current is turned along it further down. Scales in between are blended as tensors.
			float[] halfW = halfWidth(shoreBank, S, tps);
			float[][][] scales = new float[TENSOR_RADII.length][][];
			Runnable[] blurs = new Runnable[TENSOR_RADII.length];
			for (int k = 0; k < TENSOR_RADII.length; k++) {
				int kk = k;
				blurs[k] = () -> scales[kk] = blurTensor(jxx, jxy, jyy, S, TENSOR_RADII[kk]);
			}
			Fields.inParallel(blurs);
			float[] ax = new float[n], ay = new float[n], sure = new float[n];
			for (int p = 0; p < n; p++) {
				if (!dom[p]) continue;
				float want = Math.max(TENSOR_RADII[0], Math.min(TENSOR_RADII[TENSOR_RADII.length - 1], halfW[p] / tps));
				int k = 0;
				while (k < TENSOR_RADII.length - 2 && TENSOR_RADII[k + 1] < want) k++;
				float t = (want - TENSOR_RADII[k]) / (TENSOR_RADII[k + 1] - TENSOR_RADII[k]), u = 1 - t;
				float[][] lo = scales[k], hi = scales[k + 1];
				float txx = u * lo[0][p] + t * hi[0][p];
				float txy = u * lo[1][p] + t * hi[1][p];
				float tyy = u * lo[2][p] + t * hi[2][p];
				double th = 0.5 * Math.atan2(2 * txy, txx - tyy); // across the river
				ax[p] = (float) -Math.sin(th);
				ay[p] = (float) Math.cos(th);
				// How clearly the banks give a way here: 1 between straight banks, 0 in a round pool.
				float sum = txx + tyy;
				sure[p] = sum > 1e-6f ? (float) (Math.hypot(txx - tyy, 2 * txy) / sum) : 0;
			}

			// Which way: down the distance to the sea along the water (ocean or beach water), or
			// away from the far end of a stretch that cannot reach it.
			float[] pot = new float[n];
			java.util.Arrays.fill(pot, -1);
			int[] queue = new int[n];
			int qh = 0, qt = 0;
			for (int p = 0; p < n; p++) {
				int bi = ((p / S) / RES) * size + (p % S) / RES;
				if (water[p] && !rivS[p] && r.ocean[bi]) { pot[p] = 0; queue[qt++] = p; }
			}
			while (qh < qt) {
				int p = queue[qh++];
				for (int k = 0; k < 4; k++) {
					int q = neighbour(p, k, S);
					if (q >= 0 && water[q] && pot[q] < 0) { pot[q] = pot[p] + 1; queue[qt++] = q; }
				}
			}
			boolean[] seen = new boolean[n];
			int[] order = new int[n], dist = new int[n];
			for (int s0 = 0; s0 < n; s0++) {
				if (!dom[s0] || seen[s0] || pot[s0] >= 0) continue;
				int far = order[walkAll(s0, dom, pot, S, order, dist) - 1];
				int count = walkAll(far, dom, pot, S, order, dist);
				for (int i = 0; i < count; i++) { seen[order[i]] = true; pot[order[i]] = 100000 - dist[order[i]]; }
			}
			float[] pv = new float[n], pm = new float[n];
			for (int p = 0; p < n; p++) if (dom[p] && pot[p] >= 0) { pv[p] = pot[p]; pm[p] = 1; }
			float[] PV = Fields.boxBlur(Fields.boxBlur(pv, S, S, sr(r, 1)), S, S, sr(r, 1)), PM = Fields.boxBlur(Fields.boxBlur(pm, S, S, sr(r, 1)), S, S, sr(r, 1));
			float[] sm = new float[n];
			for (int p = 0; p < n; p++) sm[p] = PM[p] > 1e-4f ? PV[p] / PM[p] : 0;
			for (int y = 1; y < S - 1; y++) for (int x = 1; x < S - 1; x++) {
				int p = y * S + x;
				if (!dom[p]) continue;
				float gx = (dom[p + 1] ? sm[p + 1] : sm[p]) - (dom[p - 1] ? sm[p - 1] : sm[p]);
				float gy = (dom[p + S] ? sm[p + S] : sm[p]) - (dom[p - S] ? sm[p - S] : sm[p]);
				if (ax[p] * gx + ay[p] * gy > 0) { ax[p] = -ax[p]; ay[p] = -ay[p]; }
			}
			// Each stretch runs one way, whatever that means for the sea: water running out of the sea
			// looks better than a river clashing with itself (where the sea is about as far either way,
			// at a junction, a wide mouth or a channel along the coast with outlets, the way down to it
			// splits). So the way is spread along the axis, from where the banks give it most clearly
			// (a flood, clearest first), each sample taking the way of the one it was reached from;
			// the way down to the sea only picks which way each connected stretch runs as a whole.
			spreadWay(dom, sure, ax, ay, S);
			// Then where the flood met itself, a sample running against the ways round it (each
			// weighted by how clear its axis is) is turned round: over a block, then two, then four.
			for (int rad : new int[] {4, 8, 16}) {
				float[] vx = new float[n], vy = new float[n];
				for (int p = 0; p < n; p++) if (dom[p]) { vx[p] = ax[p] * sure[p]; vy[p] = ay[p] * sure[p]; }
				float[] VX = Fields.boxBlur(Fields.boxBlur(vx, S, S, sr(r, rad)), S, S, sr(r, rad)), VY = Fields.boxBlur(Fields.boxBlur(vy, S, S, sr(r, rad)), S, S, sr(r, rad));
				for (int p = 0; p < n; p++) if (dom[p] && ax[p] * VX[p] + ay[p] * VY[p] < 0) { ax[p] = -ax[p]; ay[p] = -ay[p]; }
			}
			if (up != null) followParent(r, dom, sure, ax, ay, up);
			else if (memory != null) remember(r, dom, rivS, pot, ax, ay, memory);
			// Where a river runs out into a lake or the sea its current carries on the way it ran where
			// it left the river, turning to the water's own shape as it spends itself.
			for (int p = 0; p < n; p++) {
				int s0 = src[p];
				if (!dom[p] || rivS[p] || s0 < 0 || pull[p] <= 0) continue;
				float lx = ax[p], ly = ay[p];
				if (lx * ax[s0] + ly * ay[s0] < 0) { lx = -lx; ly = -ly; }
				float w = pull[p], vx = w * ax[s0] + (1 - w) * lx, vy = w * ay[s0] + (1 - w) * ly;
				float l = (float) Math.hypot(vx, vy);
				if (l > 1e-4f) { ax[p] = vx / l; ay[p] = vy / l; }
			}

			// Speed: a narrow river runs faster and a wide one slower (as the square root of how much
			// narrower or wider than sixteen blocks it is, 0.6 to 1.8 times), and across it from nothing
			// at the bank (or a rock) to full speed along its middle.
			for (int p = 0; p < n; p++) {
				if (!dom[p]) continue;
				float half = Math.max(8, halfW[p]);
				double base = settings.riverSpeed() * 16 * Math.min(1.8, Math.max(0.6, Math.sqrt(128 / half))) * (1 + 0.4 * shallow[p]) * flowB[p];
				double out = Math.min(1, bank[p] / half);
				float sp = (float) (base * (1 - (1 - out) * (1 - out)));
				fx[p] = ax[p] * sp;
				fy[p] = ay[p] * sp;
				hx[p] = ax[p];
				hy[p] = ay[p];
				hb[p] = (float) base;
				// As a river's current spends itself in still water it breaks into slow swirls:
				// eddies (the curl of a noise fixed in the world, so they keep their place) strongest
				// halfway out, where the current is half spent.
				if (stillS[p]) {
					double e = 4 * pull[p] * (1 - pull[p]);
					if (e > 0.01) {
						double bx = r.originX + (p % S + 0.5) / RES, bz = r.originZ + (p / S + 0.5) / RES, d = 0.1;
						double cx = (WaveMath.vnoise(bx / 3, (bz + d) / 3, 601) - WaveMath.vnoise(bx / 3, (bz - d) / 3, 601)) / (2 * d);
						double cz = -(WaveMath.vnoise((bx + d) / 3, bz / 3, 601) - WaveMath.vnoise((bx - d) / 3, bz / 3, 601)) / (2 * d);
						double k = settings.riverSpeed() * 16 * 0.3 * 3 * e * blend[p];
						fx[p] += (float) (cx * k);
						fy[p] += (float) (cz * k);
					}
				}
			}
			// The current as it comes at land: the river's way averaged over a few blocks (so it
			// does not bend round a rock or a point of the bank before reaching it), at its speed
			// out in the stream.
			hx = Fields.boxBlur(Fields.boxBlur(hx, S, S, sr(r, 8)), S, S, sr(r, 8));
			hy = Fields.boxBlur(Fields.boxBlur(hy, S, S, sr(r, 8)), S, S, sr(r, 8));
			for (int p = 0; p < n; p++) {
				float l = (float) Math.hypot(hx[p], hy[p]);
				if (!dom[p] || l < 1e-4f) { hx[p] = 0; hy[p] = 0; continue; }
				hx[p] = hx[p] / l * hb[p];
				hy[p] = hy[p] / l * hb[p];
			}
			// Past a rock the current parts: near land, whatever would run into it is turned along
			// it, keeping its speed.
			for (int y = 1; y < S - 1; y++) for (int x = 1; x < S - 1; x++) {
				int p = y * S + x;
				float w = 1 - bank[p] / 16;
				if (!dom[p] || w <= 0) continue;
				float gx = (bank[p + 1] - bank[p - 1]) * 0.5f, gy = (bank[p + S] - bank[p - S]) * 0.5f, gl = (float) Math.hypot(gx, gy);
				float sp = (float) Math.hypot(fx[p], fy[p]);
				if (gl < 1e-4f || sp < 1e-4f) continue;
				float nx = gx / gl, ny = gy / gl, into = fx[p] * nx + fy[p] * ny;
				if (into >= 0) continue;
				float tx = fx[p] - w * into * nx, ty = fy[p] - w * into * ny, tl = (float) Math.hypot(tx, ty);
				if (tl < 1e-4f) continue;
				fx[p] = tx / tl * sp;
				fy[p] = ty / tl * sp;
			}
			fx = Fields.boxBlur(fx, S, S, sr(r, 1));
			fy = Fields.boxBlur(fy, S, S, sr(r, 1));
			for (int p = 0; p < n; p++) if (land[p]) { fx[p] = 0; fy[p] = 0; }
		}
		r.riverFx = fx;
		r.riverFy = fy;
		int T = size * 16 / r.decorScale, k = r.decorScale;
		byte[] decor = anyRiver ? decor(r, settings, dom, rivS, fx, fy, hx, hy, bank, smallLand) : new byte[T * T * 4];
		// Still water in the decor's last channel: 0 for none, else 1..255 for how much the wind's
		// ripples show there (none where a river's current rules).
		int[] column = new int[T];
		for (int t = 0; t < T; t++) column[t] = (int) ((t * k + k * 0.5) / tps);
		for (int tz = 0; tz < T; tz++) {
			int row = column[tz] * S;
			for (int tx = 0; tx < T; tx++) {
				int sp = row + column[tx];
				if (stillS[sp]) decor[(tz * T + tx) * 4 + 3] = (byte) (1 + Math.round(254 * (1 - pull[sp])));
			}
		}
		r.riverDecor = decor;
		r.fetch = fetching.join();
	}

	/** Per block, small land standing in the water: pieces of land not touching the region's edge. */
	private static boolean[] smallLand(ShoreRegion r) {
		int size = r.size;
		boolean[] small = new boolean[size * size], seen = new boolean[size * size];
		int[] queue = new int[size * size];
		for (int s0 = 0; s0 < size * size; s0++) {
			if (r.water[s0] || seen[s0]) continue;
			int h = 0, t = 0;
			boolean edge = false;
			queue[t++] = s0;
			seen[s0] = true;
			while (h < t) {
				int i = queue[h++], bx = i % size, bz = i / size;
				if (bx == 0 || bz == 0 || bx == size - 1 || bz == size - 1) edge = true;
				for (int k = 0; k < 4; k++) {
					int nx = bx + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = bz + (k == 2 ? 1 : k == 3 ? -1 : 0);
					if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
					int j = nz * size + nx;
					if (!r.water[j] && !seen[j]) { seen[j] = true; queue[t++] = j; }
				}
			}
			if (!edge && t <= ROCK_BLOCKS) for (int k = 0; k < t; k++) small[queue[k]] = true;
		}
		return small;
	}

	/** Land pieces of at most this many blocks are rocks to a river's shape, not banks. */
	private static final int ROCK_BLOCKS = 6;

	/**
	 * Still water's fetch: from each field sample of still water (and of land beside it, for the
	 * lapping on its shores), how far the wind has blown over open water (water that is not river)
	 * to get there, for each of 16 wind directions.
	 */
	private static byte[] fetch(ShoreRegion r, boolean[] water, boolean[] rivS, boolean[] stillS) {
		int S = r.samples, n = S * S, D = ShoreRegion.FETCH_DIRS;
		float tps = r.tps, max = ShoreRegion.FETCH_MAX;
		float[] toStill = Fields.distanceTo(stillS, S, S);
		byte[] out = new byte[n * D];
		int steps = (int) (max / tps), groups = 4;
		Runnable[] jobs = new Runnable[groups];
		for (int g = 0; g < groups; g++) {
			int first = g * D / groups, last = (g + 1) * D / groups;
			jobs[g] = () -> fetchDirections(first, last, S, water, rivS, stillS, toStill, steps, tps, max, out);
		}
		Fields.inParallel(jobs);
		return out;
	}

	/** {@link #fetch} for wind directions {@code first} to {@code last - 1}. */
	private static void fetchDirections(int first, int last, int S, boolean[] water, boolean[] rivS, boolean[] stillS, float[] toStill,
		int steps, float tps, float max, byte[] out) {
		int n = S * S, D = ShoreRegion.FETCH_DIRS;
		for (int d = first; d < last; d++) {
			double a = d * Math.PI * 2 / D, ux = -Math.cos(a), uz = -Math.sin(a); // upwind
			for (int p = 0; p < n; p++) {
				boolean onLand = !water[p];
				if (!stillS[p] && !(onLand && toStill[p] <= 2)) continue;
				double x = p % S, z = p / S;
				boolean started = !onLand;
				int skipped = 0;
				float f = 0;
				for (int k = 0; k < steps; k++) {
					x += ux;
					z += uz;
					int ix = (int) Math.round(x), iz = (int) Math.round(z);
					if (ix < 0 || iz < 0 || ix >= S || iz >= S) {
						if (started) f += (steps - k) * tps; // open water beyond the region, as far as we know
						break;
					}
					int q = iz * S + ix;
					boolean open = water[q] && !rivS[q];
					if (!started) {
						if (open) started = true;
						else if (++skipped > 3) break;
						else continue;
					}
					if (!open) break;
					f += tps;
				}
				out[p * D + d] = (byte) Math.round(Math.min(1, f / max) * 255);
			}
		}
	}

	/**
	 * Spreads one way through each connected stretch of {@code dom}: a flood that always goes on from
	 * the clearest axis reached so far ({@code sure} in 64 steps, newest first within a step), each
	 * sample turned to agree with the one that reached it. Then each stretch as a whole is turned to
	 * run the way {@code ax}, {@code ay} ran before (down to the sea) over most of it, weighted by
	 * {@code sure}.
	 */
	private static void spreadWay(boolean[] dom, float[] sure, float[] ax, float[] ay, int S) {
		int n = S * S, B = 64;
		float[] px = ax.clone(), py = ay.clone();
		int[] head = new int[B], next = new int[n], comp = new int[n], members = new int[n];
		java.util.Arrays.fill(head, -1);
		java.util.Arrays.fill(comp, -1);
		// Every sample, clearest first, to start each stretch from its clearest point.
		int[] count = new int[B], starts = new int[n];
		for (int p = 0; p < n; p++) if (dom[p]) count[bucket(sure[p], B)]++;
		int[] at = new int[B];
		for (int b = B - 1, o = 0; b >= 0; b--) { at[b] = o; o += count[b]; }
		int total = 0;
		for (int p = 0; p < n; p++) if (dom[p]) { starts[at[bucket(sure[p], B)]++] = p; total++; }
		for (int si = 0; si < total; si++) {
			int start = starts[si];
			if (comp[start] >= 0) continue;
			comp[start] = start;
			int top = bucket(sure[start], B), m = 0;
			next[start] = head[top];
			head[top] = start;
			while (true) {
				while (top >= 0 && head[top] < 0) top--;
				if (top < 0) break;
				int p = head[top];
				head[top] = next[p];
				members[m++] = p;
				for (int k = 0; k < 4; k++) {
					int q = neighbour(p, k, S);
					if (q < 0 || !dom[q] || comp[q] >= 0) continue;
					if (ax[q] * ax[p] + ay[q] * ay[p] < 0) { ax[q] = -ax[q]; ay[q] = -ay[q]; }
					comp[q] = start;
					int b = bucket(sure[q], B);
					next[q] = head[b];
					head[b] = q;
					if (b > top) top = b;
				}
			}
			double vote = 0;
			for (int i = 0; i < m; i++) { int p = members[i]; vote += sure[p] * (ax[p] * px[p] + ay[p] * py[p]); }
			if (vote < 0) for (int i = 0; i < m; i++) { int p = members[i]; ax[p] = -ax[p]; ay[p] = -ay[p]; }
		}
	}

	private static int bucket(float sure, int B) {
		return Math.max(0, Math.min(B - 1, (int) (sure * B)));
	}

	/** The furthest a river's current carries into other water, in texels (24 blocks). */
	private static final float MAX_REACH = 384;

	/** The axis tensor's averaging radii (samples, each blurred twice), from a narrow stream to a wide river. */
	private static final int[] TENSOR_RADII = {2, 4, 8, 12, 16, 24, 32};

	/**
	 * The water's half-width at each sample, in texels: the bank distance along its middle line.
	 * The most bank distance in ever wider squares round the sample, until the most lies well
	 * inside the square (so the middle line has been reached, not just the square's edge).
	 */
	private static float[] halfWidth(float[] bankD, int S, float tps) {
		int[] radii = {2, 4, 8, 16, 32, 64};
		float[][] most = new float[radii.length][];
		Runnable[] filters = new Runnable[radii.length];
		for (int k = 0; k < radii.length; k++) {
			int kk = k;
			filters[k] = () -> most[kk] = Fields.maxFilter(bankD, S, S, radii[kk]);
		}
		Fields.inParallel(filters);
		float[] out = new float[S * S];
		for (int p = 0; p < S * S; p++) {
			int k = 0;
			while (k < radii.length - 1 && most[k][p] - bankD[p] >= radii[k] * tps * 0.8f) k++;
			out[p] = most[k][p];
		}
		return out;
	}

	/** The structure tensor's three parts, each box-blurred twice at radius {@code r}. */
	private static float[][] blurTensor(float[] jxx, float[] jxy, float[] jyy, int S, int r) {
		return new float[][] {Fields.boxBlur(Fields.boxBlur(jxx, S, S, r), S, S, r), Fields.boxBlur(Fields.boxBlur(jxy, S, S, r), S, S, r),
			Fields.boxBlur(Fields.boxBlur(jyy, S, S, r), S, S, r)};
	}

	private static int neighbour(int p, int k, int S) {
		int x = p % S, y = p / S;
		return switch (k) {
			case 0 -> x > 0 ? p - 1 : -1;
			case 1 -> x < S - 1 ? p + 1 : -1;
			case 2 -> y > 0 ? p - S : -1;
			default -> y < S - 1 ? p + S : -1;
		};
	}

	/**
	 * Breadth-first from {@code from} over a stretch cut off from the sea: fills the visiting order
	 * (the last is the point furthest away) and each point's distance; returns how many it reached.
	 */
	private static int walkAll(int from, boolean[] dom, float[] pot, int S, int[] order, int[] dist) {
		boolean[] visited = new boolean[dom.length];
		int h = 0, t = 0;
		order[t++] = from;
		visited[from] = true;
		dist[from] = 0;
		while (h < t) {
			int p = order[h++];
			for (int k = 0; k < 4; k++) {
				int q = neighbour(p, k, S);
				if (q < 0 || !dom[q] || pot[q] >= 0 || visited[q]) continue;
				visited[q] = true;
				dist[q] = dist[p] + 1;
				order[t++] = q;
			}
		}
		return t;
	}

	/**
	 * Runs the current the way the wider level's runs: sample by sample wherever that level has a
	 * current of its own (its way is already coherent, worked out over more of the river), and each
	 * stretch elsewhere as most of it agrees (weighted by how clear its axis is). Two levels never run
	 * opposite ways, so where one blends into the other the current never cancels out.
	 */
	private static void followParent(ShoreRegion r, boolean[] dom, float[] sure, float[] ax, float[] ay, ShoreRegion up) {
		if (up.riverFx == null) return;
		int S = r.samples, n = S * S;
		float[] px = new float[n], py = new float[n];
		for (int p = 0; p < n; p++) {
			if (!dom[p]) continue;
			double wx = r.originX * 16.0 + (p % S + 0.5) * r.tps, wz = r.originZ * 16.0 + (p / S + 0.5) * r.tps;
			float fx = up.sampleAt(up.riverFx, wx, wz, null), fy = up.sampleAt(up.riverFy, wx, wz, null);
			if (!Float.isNaN(fx)) { px[p] = fx; py[p] = fy; }
		}
		boolean[] seen = new boolean[n];
		int[] queue = new int[n];
		for (int s0 = 0; s0 < n; s0++) {
			if (!dom[s0] || seen[s0]) continue;
			int h = 0, t = 0;
			queue[t++] = s0;
			seen[s0] = true;
			double vote = 0;
			while (h < t) {
				int p = queue[h++];
				vote += (0.05 + sure[p]) * (ax[p] * px[p] + ay[p] * py[p]);
				for (int k = 0; k < 4; k++) {
					int q = neighbour(p, k, S);
					if (q >= 0 && dom[q] && !seen[q]) { seen[q] = true; queue[t++] = q; }
				}
			}
			for (int i = 0; i < t; i++) {
				int p = queue[i];
				float d = ax[p] * px[p] + ay[p] * py[p];
				boolean has = px[p] * px[p] + py[p] * py[p] > 0.25f;
				if (has ? d < 0 : vote < 0) { ax[p] = -ax[p]; ay[p] = -ay[p]; }
			}
		}
	}

	/**
	 * Keeps each stretch of river running the way it ran before: per chunk, the direction at the
	 * river point nearest the chunk's middle. A stretch whose remembered chunks mostly disagree with
	 * the way just worked out is turned round; then every chunk it crosses is remembered. A way worked
	 * out from the sea is sure: it is only turned round by other sure memories, and it corrects
	 * guesses made while the sea was out of sight (once; after that they are sure too).
	 */
	private static void remember(ShoreRegion r, boolean[] dom, boolean[] rivS, float[] pot, float[] ax, float[] ay, RiverMemory memory) {
		int S = r.samples, n = S * S;
		float tps = r.tps;
		boolean[] seen = new boolean[n];
		ArrayDeque<Integer> q = new ArrayDeque<>();
		List<Integer> comp = new ArrayList<>();
		for (int s0 = 0; s0 < n; s0++) {
			if (!dom[s0] || seen[s0]) continue;
			comp.clear();
			q.add(s0);
			seen[s0] = true;
			while (!q.isEmpty()) {
				int p = q.poll();
				comp.add(p);
				for (int k = 0; k < 4; k++) {
					int nb = neighbour(p, k, S);
					if (nb >= 0 && dom[nb] && !seen[nb]) { seen[nb] = true; q.add(nb); }
				}
			}
			// The river point nearest each chunk's middle.
			Map<Long, int[]> nearest = new HashMap<>();
			Map<Long, Float> best = new HashMap<>();
			for (int p : comp) {
				if (!rivS[p]) continue;
				double wx = r.originX + ((p % S) + 0.5) * tps / 16, wz = r.originZ + ((p / S) + 0.5) * tps / 16;
				int cx = Math.floorDiv((int) Math.floor(wx), 16), cz = Math.floorDiv((int) Math.floor(wz), 16);
				double dx = wx - (cx * 16 + 8), dz = wz - (cz * 16 + 8);
				float d2 = (float) (dx * dx + dz * dz);
				long key = RiverMemory.key(cx, cz);
				Float b = best.get(key);
				if (b == null || d2 < b) { best.put(key, d2); nearest.put(key, new int[] {cx, cz, p}); }
			}
			if (nearest.isEmpty()) continue;
			// Sure memories outrank guesses; only when there are none do guesses vote.
			boolean sure = false, anySure = false;
			for (int p : comp) if (rivS[p] && pot[p] >= 0 && pot[p] < 50000) { sure = true; break; }
			for (int[] c : nearest.values()) { float[] saved = memory.get(c[0], c[1]); if (saved != null && saved[2] > 0.5f) anySure = true; }
			int votes = 0;
			for (int[] c : nearest.values()) {
				float[] saved = memory.get(c[0], c[1]);
				if (saved == null || ((sure || anySure) && saved[2] < 0.5f)) continue;
				votes += saved[0] * ax[c[2]] + saved[1] * ay[c[2]] >= 0 ? 1 : -1;
			}
			if (votes < 0) for (int p : comp) { ax[p] = -ax[p]; ay[p] = -ay[p]; }
			for (int[] c : nearest.values()) memory.put(c[0], c[1], ax[c[2]], ay[c[2]], sure || anySure);
		}
	}

	/**
	 * Wake (R), pillow (G) and foam trails (B) per decor texel (one per {@code decorScale} region
	 * texels across), 0..255. The foam piles on whatever the water runs into, as hard as it runs into
	 * it: a rock or a slab at the waterline head on, a point of the bank jutting into a fast current,
	 * never a bank the current runs along. How hard: the river's way averaged over a few blocks, at the
	 * stream's speed ({@code hx}, {@code hy}), against the land's face, taken from the distance to land
	 * smoothed over a couple of blocks so a staircase bank reads as the slope it is. Wakes (a V of light
	 * lines) trail behind small land standing in the river. Positions are worked out at each decor
	 * texel's centre in region texels.
	 */
	private static byte[] decor(ShoreRegion r, ShoreRegion.Settings s, boolean[] dom, boolean[] rivS, float[] fx, float[] fy,
		float[] hx, float[] hy, float[] bank, boolean[] small) {
		int size = r.size, S = r.samples, k = r.decorScale, T = size * 16 / k, per = 16 / k;
		float tps = r.tps;
		byte[] out = new byte[T * T * 4];
		float rockFoam = s.riverRock();
		if (rockFoam <= 0) return out;
		// How hard the current hits land, per field sample beside it (texels per second).
		float[] face = Fields.boxBlur(Fields.boxBlur(bank, S, S, sr(r, 2)), S, S, sr(r, 2));
		float[] hit = new float[S * S];
		for (int y = 1; y < S - 1; y++) for (int x = 1; x < S - 1; x++) {
			int p = y * S + x;
			if (!dom[p] || bank[p] > 8) continue;
			float gx = (face[p + 1] - face[p - 1]) * 0.5f, gy = (face[p + S] - face[p - S]) * 0.5f, gl = (float) Math.hypot(gx, gy);
			if (gl < 1e-4f) continue;
			hit[p] = Math.max(0, -(hx[p] * gx + hy[p] * gy) / gl);
		}
		// The pillow (on water within 6 texels of land, as hard as the current hits it there) and the
		// wakes behind small land standing in the river, both at once.
		float[] pillow = new float[T * T], wake = new float[T * T];
		boolean[] foamy = new boolean[size * size];
		Fields.inParallel(() -> pillow(r, k, T, per, tps, dom, hit, rockFoam, pillow, foamy),
			() -> wakes(r, s, k, T, tps, dom, rivS, fx, fy, small, rockFoam, wake));
		// Foam trails: what piles up drifts downstream, thinning as it goes. Only texels near foam can
		// carry any.
		float trail = s.riverTrail() * 16;
		float[] foam = new float[T * T];
		if (trail > 0) {
			int[] cells = new int[1024];
			int count = 0;
			int reach = (int) Math.ceil(trail + 16);
			// Each foamy block's square, in turn, adds the water texels it covers that no square before
			// it did (row by row, left to right): each row keeps the stretches already covered, so only
			// the gaps are visited.
			int[][] covered = new int[T][];
			int[] coveredCount = new int[T];
			for (int fb = 0; fb < size * size; fb++) {
				if (!foamy[fb]) continue;
				int cx = (fb % size) * 16 + 8, cz = (fb / size) * 16 + 8;
				int x0 = Math.max(0, Math.floorDiv(cx - reach, k)), x1 = Math.min(T, (cx + reach) / k + 1);
				for (int tz = Math.max(0, Math.floorDiv(cz - reach, k)); tz < Math.min(T, (cz + reach) / k + 1); tz++) {
					int[] iv = covered[tz];
					int nIv = coveredCount[tz], from = x0;
					for (int j = 0; j <= nIv && from < x1; j++) {
						int gapEnd = j < nIv ? Math.min(x1, iv[2 * j]) : x1;
						for (int tx = from; tx < gapEnd; tx++) {
							double fxc = tx * k + k * 0.5, fzc = tz * k + k * 0.5;
							int ti = tz * T + tx, bi = (int) (fzc / 16) * size + (int) (fxc / 16), sp = (int) (fzc / tps) * S + (int) (fxc / tps);
							if (!r.water[bi] || !dom[sp]) continue;
							if (count == cells.length) cells = java.util.Arrays.copyOf(cells, count * 2);
							cells[count++] = ti;
						}
						if (j < nIv) from = Math.max(from, iv[2 * j + 1]);
					}
					covered[tz] = addStretch(iv, nIv, x0, x1, coveredCount, tz);
				}
			}
			System.arraycopy(pillow, 0, foam, 0, foam.length);
			// Each step carries foam two texels downstream (a decor texel at least).
			double step = Math.max(2, k);
			double decay = Math.exp(-step / trail);
			int iters = (int) Math.ceil(trail / step);
			// Each cell's texel one step upstream (the current does not change between steps); -1 for
			// cells too slow to carry anything.
			int[] up = new int[count];
			int[] all = cells;
			Fields.ranges(count, count, (i0, i1) -> {
				float[] flow = new float[2];
				for (int i = i0; i < i1; i++) {
					int ti = all[i], tx = ti % T, tz = ti / T;
					up[i] = -1;
					flowAt(fx, fy, S, tps, (float) (tx * k + k * 0.5), (float) (tz * k + k * 0.5), flow);
					double sp = Math.sqrt((double) flow[0] * flow[0] + (double) flow[1] * flow[1]);
					if (sp < 0.5) continue;
					int ux = (int) Math.round(tx - flow[0] / sp * step / k), uz = (int) Math.round(tz - flow[1] / sp * step / k);
					if (ux < 0 || uz < 0 || ux >= T || uz >= T) continue;
					up[i] = uz * T + ux;
				}
			});
			int[] from = new int[count], upstream = new int[count];
			int m = 0;
			for (int i = 0; i < count; i++) {
				if (up[i] < 0) continue;
				from[m] = all[i];
				upstream[m++] = up[i];
			}
			// Values only ever rise, so once a pass changes nothing every later one would match it.
			for (int it = 0; it < iters; it++) {
				boolean changed = false;
				for (int i = 0; i < m; i++) {
					float v = (float) (foam[upstream[i]] * decay);
					if (v > foam[from[i]]) {
						foam[from[i]] = v;
						changed = true;
					}
				}
				if (!changed) break;
			}
			for (int i = 0; i < foam.length; i++) foam[i] = Math.max(0, foam[i] - pillow[i] * 0.5f);
		}
		for (int i = 0; i < T * T; i++) {
			if (wake[i] == 0 && pillow[i] == 0 && foam[i] == 0) continue; // most of the region: already 0
			out[i * 4] = (byte) Math.round(Math.min(1, wake[i]) * 255);
			out[i * 4 + 1] = (byte) Math.round(Math.min(1, pillow[i]) * 255);
			out[i * 4 + 2] = (byte) Math.round(Math.min(1, foam[i]) * 255);
		}
		return out;
	}

	/**
	 * Adds the stretch [a, b) to a row's sorted, separate stretches (pairs of start, end in
	 * {@code iv}, {@code n} of them), merging what it touches; returns the row's array.
	 */
	private static int[] addStretch(int[] iv, int n, int a, int b, int[] counts, int row) {
		int[] out = new int[2 * (n + 1)];
		int m = 0;
		boolean placed = false;
		for (int j = 0; j < n; j++) {
			int s0 = iv[2 * j], s1 = iv[2 * j + 1];
			if (s1 < a) { out[2 * m] = s0; out[2 * m + 1] = s1; m++; continue; }
			if (s0 > b) {
				if (!placed) { out[2 * m] = a; out[2 * m + 1] = b; m++; placed = true; }
				out[2 * m] = s0; out[2 * m + 1] = s1; m++;
				continue;
			}
			a = Math.min(a, s0);
			b = Math.max(b, s1);
		}
		if (!placed) { out[2 * m] = a; out[2 * m + 1] = b; m++; }
		counts[row] = m;
		return out;
	}

	/** The foam pillow per decor texel, and which blocks have any (see decor). */
	private static void pillow(ShoreRegion r, int k, int T, int per, float tps, boolean[] dom, float[] hit, float rockFoam, float[] pillow, boolean[] foamy) {
		int size = r.size, S = r.samples;
		for (int bz = 0; bz < size; bz++) for (int bx = 0; bx < size; bx++) {
			if (!r.water[bz * size + bx]) continue;
			boolean nearLand = false;
			for (int dz = -1; dz <= 1 && !nearLand; dz++) for (int dx = -1; dx <= 1; dx++) {
				int nx = bx + dx, nz = bz + dz;
				if (nx >= 0 && nz >= 0 && nx < size && nz < size && !r.water[nz * size + nx]) { nearLand = true; break; }
			}
			if (!nearLand) continue;
			for (int tz = bz * per; tz < bz * per + per; tz++) for (int tx = bx * per; tx < bx * per + per; tx++) {
				double cx = tx * k + k * 0.5, cz = tz * k + k * 0.5;
				int sp = (int) (cz / tps) * S + (int) (cx / tps);
				if (!dom[sp] || hit[sp] <= 0) continue;
				// Distance from the texel's centre to the nearest land block beside it.
				double lx = cx - bx * 16, lz = cz - bz * 16, d = 99;
				for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
					int nx = bx + dx, nz = bz + dz;
					if ((dx == 0 && dz == 0) || nx < 0 || nz < 0 || nx >= size || nz >= size || r.water[nz * size + nx]) continue;
					double ex = dx < 0 ? lx : dx > 0 ? 16 - lx : 0, ez = dz < 0 ? lz : dz > 0 ? 16 - lz : 0;
					d = Math.min(d, Math.sqrt(ex * ex + ez * ez));
				}
				if (d > 6) continue;
				float v = (float) (rockFoam * Math.min(1, Math.max(0, (hit[sp] - 2.5) / 6)) * (1 - d / 6));
				if (v > 0) {
					pillow[tz * T + tx] = v;
					foamy[bz * size + bx] = true;
				}
			}
		}
	}

	/** Wakes behind small land standing in the river, per decor texel (see decor). */
	private static void wakes(ShoreRegion r, ShoreRegion.Settings s, int k, int T, float tps, boolean[] dom, boolean[] rivS, float[] fx, float[] fy,
		boolean[] small, float rockFoam, float[] wake) {
		int size = r.size, S = r.samples;
		List<int[]> rocks = new ArrayList<>();
		for (int bz = 0; bz < size; bz++) for (int bx = 0; bx < size; bx++) {
			int i = bz * size + bx;
			if (r.water[i] || !small[i]) continue;
			boolean wet = (bx > 0 && r.water[i - 1] && r.river[i - 1]) || (bx < size - 1 && r.water[i + 1] && r.river[i + 1])
				|| (bz > 0 && r.water[i - size] && r.river[i - size]) || (bz < size - 1 && r.water[i + size] && r.river[i + size]);
			if (wet) rocks.add(new int[] {bx, bz});
		}
		// Lines a texel or so wide, never thinner than about half a decor texel.
		float Lw = s.riverWake() * 16, tan = 0.34f;
		double lineW = Math.max(1.3, 0.6 * k);
		for (int[] rock : rocks) {
			if (Lw <= 0) break;
			int cx = rock[0] * 16 + 8, cz = rock[1] * 16 + 8;
			// The river's way past the rock, from its current in a ring round it.
			double sx = 0, sz = 0;
			int y0 = Math.max(0, (int) Math.floor((cz - 33) / tps)), y1 = Math.min(S - 1, (int) Math.ceil((cz + 33) / tps));
			int x0 = Math.max(0, (int) Math.floor((cx - 33) / tps)), x1 = Math.min(S - 1, (int) Math.ceil((cx + 33) / tps));
			for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
				int p = y * S + x;
				if (!rivS[p]) continue;
				double px = (x + 0.5) * tps - cx, pz = (y + 0.5) * tps - cz, r2 = px * px + pz * pz;
				if (r2 < 144 || r2 > 1024) continue;
				sx += fx[p];
				sz += fy[p];
			}
			double sl = Math.hypot(sx, sz);
			if (sl < 1e-3) continue;
			double dx = sx / sl, dz = sz / sl;
			int R = (int) Math.ceil(Lw + 12);
			for (int tz = Math.max(0, Math.floorDiv(cz - R, k)); tz < Math.min(T, (cz + R) / k + 1); tz++) {
				for (int tx = Math.max(0, Math.floorDiv(cx - R, k)); tx < Math.min(T, (cx + R) / k + 1); tx++) {
					double fxc = tx * k + k * 0.5, fzc = tz * k + k * 0.5;
					int bi = (int) (fzc / 16) * size + (int) (fxc / 16), sp = (int) (fzc / tps) * S + (int) (fxc / tps);
					if (!r.water[bi] || !dom[sp]) continue;
					double ox = fxc - cx, oz = fzc - cz;
					double along = ox * dx + oz * dz, across = Math.abs(-ox * dz + oz * dx);
					if (along >= 6 && along <= Lw + 8) {
						double line = Math.max(0, 1 - Math.abs(across - (8 + (along - 6) * tan)) / lineW);
						int ti = tz * T + tx;
						if (line > 0) wake[ti] = (float) Math.max(wake[ti], line * rockFoam * (1 - (along - 6) / (Lw + 2)));
					}
				}
			}
		}
	}

	/** The current at a region texel position, blended between field samples. */
	static void flowAt(float[] fx, float[] fy, int S, float tps, float tx, float tz, float[] out) {
		float sx = tx / tps - 0.5f, sz = tz / tps - 0.5f;
		int ix = Math.max(0, Math.min(S - 2, (int) Math.floor(sx))), iz = Math.max(0, Math.min(S - 2, (int) Math.floor(sz)));
		float u = Math.max(0, Math.min(1, sx - ix)), v = Math.max(0, Math.min(1, sz - iz));
		int p = iz * S + ix;
		out[0] = (fx[p] * (1 - u) + fx[p + 1] * u) * (1 - v) + (fx[p + S] * (1 - u) + fx[p + S + 1] * u) * v;
		out[1] = (fy[p] * (1 - u) + fy[p + 1] * u) * (1 - v) + (fy[p + S] * (1 - u) + fy[p + S + 1] * u) * v;
	}
}
