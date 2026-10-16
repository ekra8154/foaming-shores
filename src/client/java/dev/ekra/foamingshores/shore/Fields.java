package dev.ekra.foamingshores.shore;

import java.util.Arrays;

/**
 * Grid algorithms behind the shore fields, all on row-major float grids of {@code w * h}.
 * Ported from the playground (foaming-shores-playground/index.html).
 */
final class Fields {
	private Fields() {
	}

	static final float FAR = 1e20f;

	/** Felzenszwalb-Huttenlocher exact Euclidean distance transform; returns distances (not squared). */
	static float[] distanceTo(boolean[] feature, int w, int h) {
		double[] g = new double[w * h];
		for (int i = 0; i < g.length; i++) g[i] = feature[i] ? 0 : FAR;
		int n = Math.max(w, h);
		double[] f = new double[n], d = new double[n], z = new double[n + 1];
		int[] v = new int[n];
		for (int x = 0; x < w; x++) {
			for (int y = 0; y < h; y++) f[y] = g[y * w + x];
			transform1d(f, h, d, v, z);
			for (int y = 0; y < h; y++) g[y * w + x] = d[y];
		}
		for (int y = 0; y < h; y++) {
			System.arraycopy(g, y * w, f, 0, w);
			transform1d(f, w, d, v, z);
			System.arraycopy(d, 0, g, y * w, w);
		}
		float[] out = new float[w * h];
		for (int i = 0; i < out.length; i++) out[i] = (float) Math.sqrt(g[i]);
		return out;
	}

	private static void transform1d(double[] f, int len, double[] d, int[] v, double[] z) {
		int k = 0;
		v[0] = 0;
		z[0] = -FAR;
		z[1] = FAR;
		for (int q = 1; q < len; q++) {
			double s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
			while (s <= z[k]) {
				k--;
				s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
			}
			k++;
			v[k] = q;
			z[k] = s;
			z[k + 1] = FAR;
		}
		k = 0;
		for (int q = 0; q < len; q++) {
			while (z[k + 1] < q) k++;
			d[q] = (double) (q - v[k]) * (q - v[k]) + f[v[k]];
		}
	}

	/** Separable box blur of radius r with clamped edges. */
	static float[] boxBlur(float[] src, int w, int h, int r) {
		if (r <= 0) return src.clone();
		float[] tmp = new float[w * h], out = new float[w * h];
		float norm = 1f / (2 * r + 1);
		for (int y = 0; y < h; y++) {
			int o = y * w;
			float acc = 0;
			for (int x = -r; x <= r; x++) acc += src[o + clamp(x, 0, w - 1)];
			for (int x = 0; x < w; x++) {
				tmp[o + x] = acc * norm;
				acc += src[o + Math.min(w - 1, x + r + 1)] - src[o + Math.max(0, x - r)];
			}
		}
		for (int x = 0; x < w; x++) {
			float acc = 0;
			for (int y = -r; y <= r; y++) acc += tmp[clamp(y, 0, h - 1) * w + x];
			for (int y = 0; y < h; y++) {
				out[y * w + x] = acc * norm;
				acc += tmp[Math.min(h - 1, y + r + 1) * w + x] - tmp[Math.max(0, y - r) * w + x];
			}
		}
		return out;
	}

	/** Separable square max filter of radius r (van Herk / Gil-Werman). */
	static float[] maxFilter(float[] src, int w, int h, int r) {
		float[] tmp = new float[w * h], out = new float[w * h];
		int n = Math.max(w, h), k = 2 * r + 1;
		float[] g = new float[n], hh = new float[n];
		for (int y = 0; y < h; y++) maxPass(src, tmp, w, y * w, 1, r, k, g, hh);
		for (int x = 0; x < w; x++) maxPass(tmp, out, h, x, w, r, k, g, hh);
		return out;
	}

	private static void maxPass(float[] from, float[] to, int len, int off, int stride, int r, int k, float[] g, float[] hh) {
		for (int i = 0; i < len; i++) {
			float v = from[off + i * stride];
			g[i] = i % k == 0 ? v : Math.max(g[i - 1], v);
		}
		for (int i = len - 1; i >= 0; i--) {
			float v = from[off + i * stride];
			hh[i] = (i == len - 1 || (i + 1) % k == 0) ? v : Math.max(hh[i + 1], v);
		}
		for (int i = 0; i < len; i++) to[off + i * stride] = Math.max(hh[Math.max(0, i - r)], g[Math.min(len - 1, i + r)]);
	}

	/**
	 * Dijkstra over the 8-neighbour grid. {@code cost} holds each source's starting cost (FAR for
	 * non-sources) and is updated in place. {@link Step#cost} returns the cost of stepping from p to
	 * q, or a negative number when the step is not allowed.
	 */
	static void dijkstra(double[] cost, int w, int h, Step step) {
		dijkstra(cost, w, h, step, null, null, null);
	}

	/**
	 * As {@link #dijkstra(double[], int, int, Step)}, also recording each point's predecessor on its
	 * cheapest path ({@code pred}, -1 for sources and unreached points) and the order points settle
	 * in ({@code order}); either may be null. {@code accept}, if not null, can refuse a new cost for a
	 * point. Returns how many points settled.
	 */
	static int dijkstra(double[] cost, int w, int h, Step step, int[] pred, int[] order, Accept accept) {
		int n = w * h;
		if (pred != null) Arrays.fill(pred, -1);
		int settled = 0;
		int[] heap = new int[n], pos = new int[n];
		boolean[] done = new boolean[n];
		Arrays.fill(pos, -1);
		int size = 0;
		for (int p = 0; p < n; p++) if (cost[p] < FAR) { heap[size] = p; pos[p] = size; size++; }
		for (int i = (size >> 1) - 1; i >= 0; i--) siftDown(heap, pos, cost, size, i);
		while (size > 0) {
			int p = heap[0];
			size--;
			pos[p] = -1;
			done[p] = true;
			if (order != null) order[settled] = p;
			settled++;
			if (size > 0) {
				heap[0] = heap[size];
				pos[heap[0]] = 0;
				siftDown(heap, pos, cost, size, 0);
			}
			int x = p % w, y = p / w;
			for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
				if (dx == 0 && dy == 0) continue;
				int nx = x + dx, ny = y + dy;
				if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
				int q = ny * w + nx;
				if (done[q]) continue;
				boolean diag = dx != 0 && dy != 0;
				// A diagonal step needs both sides open: water cannot squeeze between two blocks
				// that only touch at a corner.
				if (diag && (step.cost(p, y * w + nx, false) < 0 || step.cost(p, ny * w + x, false) < 0)) continue;
				double c = step.cost(p, q, diag);
				if (c < 0) continue;
				double nc = cost[p] + c;
				if (nc < cost[q] && (accept == null || accept.accept(p, q, nc))) {
					cost[q] = nc;
					if (pred != null) pred[q] = p;
					if (pos[q] < 0) { heap[size] = q; pos[q] = size; size++; siftUp(heap, pos, cost, size - 1); }
					else siftUp(heap, pos, cost, pos[q]);
				}
			}
		}
		return settled;
	}

	interface Step {
		double cost(int p, int q, boolean diagonal);
	}

	interface Accept {
		/** Whether q may take {@code newCost} by way of p (if so, p becomes q's predecessor). */
		boolean accept(int p, int q, double newCost);
	}

	private static void siftUp(int[] heap, int[] pos, double[] c, int i) {
		int p = heap[i];
		double k = c[p];
		while (i > 0) {
			int pi = (i - 1) >> 1, pp = heap[pi];
			if (c[pp] <= k) break;
			heap[i] = pp;
			pos[pp] = i;
			i = pi;
		}
		heap[i] = p;
		pos[p] = i;
	}

	private static void siftDown(int[] heap, int[] pos, double[] c, int size, int i) {
		int p = heap[i];
		double k = c[p];
		for (;;) {
			int l = 2 * i + 1;
			if (l >= size) break;
			int m = (l + 1 < size && c[heap[l + 1]] < c[heap[l]]) ? l + 1 : l;
			if (c[heap[m]] >= k) break;
			heap[i] = heap[m];
			pos[heap[i]] = i;
			i = m;
		}
		heap[i] = p;
		pos[p] = i;
	}

	static int clamp(int v, int lo, int hi) {
		return v < lo ? lo : v > hi ? hi : v;
	}
}
