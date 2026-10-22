package dev.ekra.foamingshores.shore;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Grid algorithms behind the shore fields, all on row-major float grids of {@code w * h}.
 * Ported from the playground (foaming-shores-playground/index.html).
 */
final class Fields {
	private Fields() {
	}

	static final float FAR = 1e20f;

	/**
	 * Threads for the parts of a build that do not need each other (the rivers beside the waves'
	 * fields, the fetch, the blurs at each scale). Every part writes its own arrays, so the result is
	 * the same as one after the other.
	 */
	private static final AtomicInteger THREADS = new AtomicInteger();
	private static final ForkJoinPool POOL = new ForkJoinPool(Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() / 2)), pool -> {
		ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
		t.setName("Foaming Shores fields " + THREADS.getAndIncrement());
		t.setDaemon(true);
		// Below the render thread and the game's own workers: a build may take a little longer, frames
		// stay smooth.
		t.setPriority(Thread.NORM_PRIORITY - 2);
		return t;
	}, null, false);

	/** A range of rows or columns [from, to) of one pass. */
	interface Range {
		void run(int from, int to);
	}

	/**
	 * Runs {@code body} over 0..n in a few ranges at once when there is enough to share (rows or
	 * columns of a pass are independent, each worked exactly as on its own).
	 */
	static void ranges(int n, int cells, Range body) {
		int parts = cells < 1 << 16 ? 1 : Math.min(n, POOL.getParallelism());
		if (parts <= 1) {
			body.run(0, n);
			return;
		}
		Runnable[] jobs = new Runnable[parts];
		for (int i = 0; i < parts; i++) {
			int from = (int) ((long) n * i / parts), to = (int) ((long) n * (i + 1) / parts);
			jobs[i] = () -> body.run(from, to);
		}
		inParallel(jobs);
	}

	/** Runs the jobs on the shore threads at once and waits for all of them. */
	static void inParallel(Runnable... jobs) {
		ForkJoinTask<?>[] tasks = new ForkJoinTask<?>[jobs.length];
		for (int i = 0; i < jobs.length; i++) tasks[i] = ForkJoinTask.adapt(jobs[i]);
		if (ForkJoinTask.getPool() == POOL) ForkJoinTask.invokeAll(tasks);
		else POOL.invoke(ForkJoinTask.adapt(() -> ForkJoinTask.invokeAll(tasks)));
	}

	/** Starts a job on the shore threads; {@link ForkJoinTask#join} it for the result. */
	static <T> ForkJoinTask<T> start(Callable<T> job) {
		ForkJoinTask<T> task = ForkJoinTask.adapt(job);
		if (ForkJoinTask.getPool() == POOL) task.fork();
		else POOL.execute(task);
		return task;
	}

	/** Felzenszwalb-Huttenlocher exact Euclidean distance transform; returns distances (not squared). */
	static float[] distanceTo(boolean[] feature, int w, int h) {
		double[] g = new double[w * h];
		for (int i = 0; i < g.length; i++) g[i] = feature[i] ? 0 : FAR;
		int n = Math.max(w, h);
		ranges(w, w * h, (x0, x1) -> {
			double[] f = new double[n], d = new double[n], z = new double[n + 1];
			int[] v = new int[n];
			for (int x = x0; x < x1; x++) {
				for (int y = 0; y < h; y++) f[y] = g[y * w + x];
				transform1d(f, h, d, v, z);
				for (int y = 0; y < h; y++) g[y * w + x] = d[y];
			}
		});
		float[] out = new float[w * h];
		ranges(h, w * h, (y0, y1) -> {
			double[] f = new double[n], d = new double[n], z = new double[n + 1];
			int[] v = new int[n];
			for (int y = y0; y < y1; y++) {
				System.arraycopy(g, y * w, f, 0, w);
				transform1d(f, w, d, v, z);
				for (int x = 0; x < w; x++) out[y * w + x] = (float) Math.sqrt(d[x]);
			}
		});
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
		ranges(h, w * h, (y0, y1) -> {
			for (int y = y0; y < y1; y++) {
				int o = y * w;
				float acc = 0;
				for (int x = -r; x <= r; x++) acc += src[o + clamp(x, 0, w - 1)];
				for (int x = 0; x < w; x++) {
					tmp[o + x] = acc * norm;
					acc += src[o + Math.min(w - 1, x + r + 1)] - src[o + Math.max(0, x - r)];
				}
			}
		});
		// Columns side by side, a row at a time (the same sums per column, read along memory).
		ranges(w, w * h, (x0, x1) -> {
			float[] acc = new float[w];
			for (int y = -r; y <= r; y++) {
				int o = clamp(y, 0, h - 1) * w;
				for (int x = x0; x < x1; x++) acc[x] += tmp[o + x];
			}
			for (int y = 0; y < h; y++) {
				int o = y * w, in = Math.min(h - 1, y + r + 1) * w, off = Math.max(0, y - r) * w;
				for (int x = x0; x < x1; x++) {
					out[o + x] = acc[x] * norm;
					acc[x] += tmp[in + x] - tmp[off + x];
				}
			}
		});
		return out;
	}

	/** Separable square max filter of radius r (van Herk / Gil-Werman). */
	static float[] maxFilter(float[] src, int w, int h, int r) {
		float[] tmp = new float[w * h], out = new float[w * h];
		int n = Math.max(w, h), k = 2 * r + 1;
		ranges(h, w * h, (y0, y1) -> {
			float[] g = new float[n], hh = new float[n];
			for (int y = y0; y < y1; y++) maxPass(src, tmp, w, y * w, 1, r, k, g, hh);
		});
		ranges(w, w * h, (x0, x1) -> {
			float[] g = new float[n], hh = new float[n];
			for (int x = x0; x < x1; x++) maxPass(tmp, out, h, x, w, r, k, g, hh);
		});
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

	/**
	 * How open each {@code sea} point's way out is: the most {@code value} (distance from land) stays
	 * along the best 4-neighbour path from the widest point of its own connected water (a widest-path
	 * search). A cove behind a narrower mouth scores no more than its mouth. {@code -FAR} off the sea.
	 */
	static float[] openWay(float[] value, boolean[] sea, int w, int h) {
		int n = w * h;
		// The widest point of each connected stretch of water.
		int[] comp = new int[n];
		Arrays.fill(comp, -1);
		int[] stack = new int[n];
		double[] neg = new double[n];
		Arrays.fill(neg, FAR);
		for (int s = 0; s < n; s++) {
			if (!sea[s] || comp[s] >= 0) continue;
			int top = 0, best = s;
			stack[top++] = s;
			comp[s] = s;
			while (top > 0) {
				int p = stack[--top], x = p % w, y = p / w;
				if (value[p] > value[best]) best = p;
				for (int k = 0; k < 4; k++) {
					int q = k == 0 ? (x > 0 ? p - 1 : -1) : k == 1 ? (x < w - 1 ? p + 1 : -1) : k == 2 ? (y > 0 ? p - w : -1) : (y < h - 1 ? p + w : -1);
					if (q < 0 || !sea[q] || comp[q] >= 0) continue;
					comp[q] = s;
					stack[top++] = q;
				}
			}
			neg[best] = -value[best];
		}
		// Widest path, as a Dijkstra on the negated bottleneck.
		int[] heap = new int[n], pos = new int[n];
		boolean[] done = new boolean[n];
		Arrays.fill(pos, -1);
		int size = 0;
		for (int p = 0; p < n; p++) if (neg[p] < FAR) { heap[size] = p; pos[p] = size; size++; }
		for (int i = (size >> 1) - 1; i >= 0; i--) siftDown(heap, pos, neg, size, i);
		while (size > 0) {
			int p = heap[0];
			size--;
			pos[p] = -1;
			done[p] = true;
			if (size > 0) {
				heap[0] = heap[size];
				pos[heap[0]] = 0;
				siftDown(heap, pos, neg, size, 0);
			}
			int x = p % w, y = p / w;
			for (int k = 0; k < 4; k++) {
				int q = k == 0 ? (x > 0 ? p - 1 : -1) : k == 1 ? (x < w - 1 ? p + 1 : -1) : k == 2 ? (y > 0 ? p - w : -1) : (y < h - 1 ? p + w : -1);
				if (q < 0 || done[q] || !sea[q]) continue;
				double nc = Math.max(neg[p], -value[q]);
				if (nc < neg[q]) {
					neg[q] = nc;
					if (pos[q] < 0) { heap[size] = q; pos[q] = size; size++; siftUp(heap, pos, neg, size - 1); }
					else siftUp(heap, pos, neg, pos[q]);
				}
			}
		}
		float[] out = new float[n];
		for (int p = 0; p < n; p++) out[p] = neg[p] < FAR ? (float) -neg[p] : -FAR;
		return out;
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
