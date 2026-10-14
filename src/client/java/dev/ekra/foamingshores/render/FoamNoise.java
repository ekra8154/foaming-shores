package dev.ekra.foamingshores.render;

/**
 * The tiling 256x256 noise the bake shader reads: front raggedness, lip width and the foam lace
 * (edges of a Worley cell pattern, so foam reads as a net of bubbles). Ported from the playground.
 */
final class FoamNoise {
	static final int SIZE = 256;

	private FoamNoise() {
	}

	/** RGBA8 rows: r = raggedness (cell size from jagScale texels), g = lip width, b = lace. */
	static byte[] build(int jagScaleTexels, float laceCellTexels) {
		float[] jag = noiseField(Math.round(SIZE / (float) jagScaleTexels), 101);
		float[] lip = noiseField(32, 202);
		float[] lace = lace(laceCellTexels, 303);
		byte[] out = new byte[SIZE * SIZE * 4];
		for (int i = 0; i < SIZE * SIZE; i++) {
			out[i * 4] = (byte) Math.round(jag[i] * 255);
			out[i * 4 + 1] = (byte) Math.round(lip[i] * 255);
			out[i * 4 + 2] = (byte) Math.round(lace[i] * 255);
			out[i * 4 + 3] = (byte) 255;
		}
		return out;
	}

	static float hash3(int x, int y, int s) {
		int h = x * 374761393 + y * 668265263 + s * 1442695041;
		h = (h ^ (h >>> 13)) * 1274126177;
		h ^= h >>> 16;
		return (h & 0xFFFFFFFFL) / 4294967296f;
	}

	private static float smooth(float t) {
		return t * t * (3 - 2 * t);
	}

	private static float periodic(int cells, int seed, float x, float y) {
		float fx = ((x / SIZE) * cells % cells + cells) % cells, fy = ((y / SIZE) * cells % cells + cells) % cells;
		int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy);
		float tx = smooth(fx - ix), ty = smooth(fy - iy);
		int ix1 = (ix + 1) % cells, iy1 = (iy + 1) % cells;
		float a = hash3(ix, iy, seed), b = hash3(ix1, iy, seed), c = hash3(ix, iy1, seed), d = hash3(ix1, iy1, seed);
		return a + (b - a) * tx + (c - a) * ty + (a - b - c + d) * tx * ty;
	}

	private static float[] noiseField(int cells, int seed) {
		int c1 = Math.max(2, cells), c2 = Math.max(2, cells * 2);
		float[] out = new float[SIZE * SIZE];
		for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++)
			out[y * SIZE + x] = 0.68f * periodic(c1, seed, x + 0.5f, y + 0.5f) + 0.32f * periodic(c2, seed + 1, x + 0.5f, y + 0.5f);
		return out;
	}

	private static float[] lace(float cellPx, int seed) {
		int n = Math.max(2, Math.round(SIZE / cellPx));
		float cs = SIZE / (float) n;
		float[] out = new float[SIZE * SIZE];
		for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
			int gx = (int) Math.floor(x / cs), gy = (int) Math.floor(y / cs);
			float f1 = 1e9f, f2 = 1e9f;
			for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
				int cx = gx + dx, cy = gy + dy, wx = ((cx % n) + n) % n, wy = ((cy % n) + n) % n;
				float px = (cx + 0.15f + 0.7f * hash3(wx, wy, seed)) * cs, py = (cy + 0.15f + 0.7f * hash3(wx, wy, seed + 3)) * cs;
				float dd = (float) Math.hypot(px - (x + 0.5f), py - (y + 0.5f));
				if (dd < f1) { f2 = f1; f1 = dd; } else if (dd < f2) f2 = dd;
			}
			float edge = Math.max(0, 1 - (f2 - f1) / 1.7f);
			out[y * SIZE + x] = edge * (0.78f + 0.22f * hash3(x, y, seed + 9));
		}
		return out;
	}
}
