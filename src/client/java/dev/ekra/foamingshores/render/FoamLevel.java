package dev.ekra.foamingshores.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.ekra.foamingshores.shore.ShoreRegion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One level of detail: a square region of shore fields around the player, the textures the bake
 * reads them from, and its block tops cut into 16x16 block tiles. The near level is fine (4 field
 * samples per block) and small; the far level coarse (2 per block) and wide. The far one is built
 * first when both are, and the near one inherits from it (see {@link ShoreRegion.Parent}), so where
 * the drawing goes over from one to the other the waves and currents run on unbroken. Each level
 * leaves its outermost tile undrawn: a region's edge knows least about what lies past it.
 */
final class FoamLevel {
	/** Tile side in blocks. */
	static final int TILE = 16;

	/**
	 * A level's shape. {@code size} in blocks (whole tiles); field samples per block; region texels
	 * per river decor texel; the region stays put while the player's tile, counted from its corner,
	 * is within keepLo..keepHi (each way), and is laid with the player's tile at keepLo or keepHi
	 * (ahead of where it is going) when it moves, or at {@code home} when first laid. {@code fades}:
	 * the outermost drawn tiles dissolve toward the edge (the furthest level, where foam ends).
	 * {@code maxFaces}: wall and drop faces it can draw (nearest first), a multiple of FACE_COLS.
	 */
	record Spec(String name, int size, int res, int decorScale, int keepLo, int keepHi, int home, boolean fades, int maxFaces) {
		int tiles() {
			return size / TILE;
		}

		int faceRows() {
			return maxFaces / FoamRenderer.FACE_COLS;
		}

		int faceTexH() {
			return faceRows() * FoamRenderer.TILE_H;
		}
	}

	static final Spec NEAR = new Spec("near", 160, ShoreRegion.RES, 1, 4, 5, 4, false, 2048);
	static final Spec FAR = new Spec("far", 384, 2, 4, 9, 14, 11, true, 4096);
	/** The shapes for lighter detail: the near level alone (it is then the outermost, so it fades), a smaller far level. */
	static final Spec NEAR_LIGHT = new Spec("near", 128, ShoreRegion.RES, 1, 3, 4, 3, true, 2048);
	static final Spec FAR_BALANCED = new Spec("far", 256, 2, 4, 6, 9, 7, true, 4096);

	/** Blocks at the region's edge where its fields are its parent's, and over which they blend in. */
	static final int MARGIN = 16, BAND = 16;

	/** The level's largest shape, which its textures are made for, and its shape at the current detail. */
	final Spec max;
	Spec spec;
	final int index;
	ShoreRegion region;
	/** Chunks missing from the snapshot behind the current region, and behind the one being built. */
	Set<Long> missing = Set.of(), buildingMissing = Set.of();
	/** Every tile with something to draw, by world tile; those this level draws (the near level's area is its own). */
	final Map<Long, Tile> tiles = new HashMap<>();
	final List<Tile> drawn = new ArrayList<>();
	List<FoamRenderer.Quad> faceQuads = List.of();
	/** Each face's shore block's tile (as key()), side by side with faceQuads. */
	long[] faceTiles = new long[0];
	int faceCount;
	/** Whether each face buffer holds a bake of the current faces. */
	final boolean[] faceBaked = new boolean[2];
	long buildMs;

	GpuTexture fieldTexture, field2Texture, dipTexture, riverTexture, riverDecorTexture, fetchTexture, faceDataTexture, indexTexture, fadeTexture;
	GpuTextureView fieldView, field2View, dipView, riverView, riverDecorView, fetchView, faceDataView, indexView, fadeView;
	/** Per atlas page, the tile table the tile pass reads (see foam_tile.vsh). */
	final GpuTexture[] tableTextures = new GpuTexture[FoamAtlas.DENSITIES.length];
	final GpuTextureView[] tableViews = new GpuTextureView[FoamAtlas.DENSITIES.length];
	/** The faces, baked into two buffers like the atlas pages. */
	final FaceTexture[] faceOutputs = new FaceTexture[2];
	final Identifier[] faceIds = new Identifier[2];
	/**
	 * Bake uniforms for tops and faces, a ring for each step's parity: the step being finished and the
	 * next one can both be baked in one frame, and each ring may turn only once a frame.
	 */
	final MappableRingBuffer[] ubo = new MappableRingBuffer[2], faceUbo = new MappableRingBuffer[2];

	/** Tiles a table holds (its first texel is the header). */
	static final int TABLE = 1023;

	FoamLevel(int index, Spec max) {
		this.index = index;
		this.max = max;
		this.spec = max;
		Spec spec = max;
		for (int b = 0; b < 2; b++) faceIds[b] = Identifier.fromNamespaceAndPath("foamingshores", "foam_faces_" + spec.name() + "_" + b);
	}

	/**
	 * A 16x16 block tile: its blocks with foam, their quads (uv over the tile, 0..1), and in each atlas
	 * buffer its page and slot, and whether it has been baked there (a tile out of view keeps its last
	 * bake until it comes back into view).
	 */
	static final class Tile {
		final int tx, tz;
		final List<int[]> blocks = new ArrayList<>();
		final List<FoamRenderer.Quad> quads = new ArrayList<>();
		final FoamAtlas.Page[] pages = new FoamAtlas.Page[2];
		final int[] slots = {-1, -1};
		final boolean[] baked = new boolean[2];

		Tile(int tx, int tz) {
			this.tx = tx;
			this.tz = tz;
		}
	}

	static long key(int tx, int tz) {
		return ((long) tx << 32) ^ (tz & 0xFFFFFFFFL);
	}

	/** The region's corner, in tiles, for a player in world tile (ptx, ptz). */
	int[] place(int ptx, int ptz) {
		if (region == null) return new int[] {ptx - spec.home(), ptz - spec.home()};
		int ox = region.originX / TILE, oz = region.originZ / TILE;
		return new int[] {placeAxis(ox, ptx), placeAxis(oz, ptz)};
	}

	private int placeAxis(int origin, int p) {
		int i = p - origin;
		if (i < spec.keepLo()) return p - spec.keepHi();
		if (i > spec.keepHi()) return p - spec.keepLo();
		return origin;
	}

	/** Whether the player's tile is two tiles or more past the window it is kept in, either way: time to lay it again whatever the throttle says. */
	boolean nearEdge(int ptx, int ptz) {
		int i = ptx - region.originX / TILE, j = ptz - region.originZ / TILE;
		return i < spec.keepLo() - 1 || j < spec.keepLo() - 1 || i > spec.keepHi() + 1 || j > spec.keepHi() + 1;
	}

	/** Whether world tile (tx, tz) is one this level could draw: inside its region, not its outermost ring. */
	boolean covers(int tx, int tz) {
		if (region == null) return false;
		int i = tx - region.originX / TILE, j = tz - region.originZ / TILE, n = spec.tiles();
		return i >= 1 && j >= 1 && i < n - 1 && j < n - 1;
	}

	void ensureTextures(GpuDevice device) {
		if (fieldTexture != null) return;
		Spec spec = max;
		int S = spec.size() * spec.res(), T = spec.size() * 16 / spec.decorScale();
		String n = "Foaming Shores " + spec.name() + " ";
		fieldTexture = device.createTexture(n + "fields", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA32_FLOAT, S, S, 1, 1);
		fieldView = device.createTextureView(fieldTexture);
		field2Texture = device.createTexture(n + "fields 2", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA32_FLOAT, S, S, 1, 1);
		field2View = device.createTextureView(field2Texture);
		dipTexture = device.createTexture(n + "dips", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA32_FLOAT, S, S, 1, 1);
		dipView = device.createTextureView(dipTexture);
		riverTexture = device.createTexture(n + "rivers", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA32_FLOAT, S, S, 1, 1);
		riverView = device.createTextureView(riverTexture);
		riverDecorTexture = device.createTexture(n + "river rocks", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, T, T, 1, 1);
		riverDecorView = device.createTextureView(riverDecorTexture);
		fetchTexture = device.createTexture(n + "fetch", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, S * 4, S, 1, 1);
		fetchView = device.createTextureView(fetchTexture);
		for (int i = 0; i < tableTextures.length; i++) {
			tableTextures[i] = device.createTexture(n + "tile table " + i, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA32_FLOAT, TABLE + 1, 1, 1, 1);
			tableViews[i] = device.createTextureView(tableTextures[i]);
		}
		// Where each of the region's tiles was baked, for the face pass (see foam_bake.fsh topAt).
		indexTexture = device.createTexture(n + "tile index", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA32_FLOAT,
			spec.tiles(), spec.tiles(), 1, 1);
		indexView = device.createTextureView(indexTexture);
		faceDataTexture = device.createTexture(n + "face table", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA32_FLOAT,
			FoamRenderer.FACE_COLS * 2, spec.faceRows(), 1, 1);
		faceDataView = device.createTextureView(faceDataTexture);
		for (int b = 0; b < 2; b++) {
			GpuTexture faces = device.createTexture(n + "face foam " + b, GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
				GpuFormat.RGBA8_UNORM, FoamRenderer.FACE_TEX_W, spec.faceTexH(), 1, 1);
			faceOutputs[b] = new FaceTexture(faces, device.createTextureView(faces));
			Minecraft.getInstance().getTextureManager().register(faceIds[b], faceOutputs[b]);
		}
		for (int b = 0; b < 2; b++) {
			int bb = b;
			ubo[b] = new MappableRingBuffer(() -> n + "bake UBO " + bb, 130, FoamRenderer.UBO_SIZE);
			faceUbo[b] = new MappableRingBuffer(() -> n + "face bake UBO " + bb, 130, FoamRenderer.UBO_SIZE);
		}
	}

	/**
	 * The fade texture, sized to the current shape (the shader takes its size for the region's tiles).
	 */
	GpuTextureView fadeView(GpuDevice device) {
		int n = spec.tiles();
		if (fadeTexture == null || fadeTexture.getWidth(0) != n) {
			if (fadeTexture != null) fadeTexture.close();
			fadeTexture = device.createTexture("Foaming Shores " + spec.name() + " tile fade", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
				GpuFormat.RGBA32_FLOAT, n, n, 1, 1);
			fadeView = device.createTextureView(fadeTexture);
		}
		return fadeView;
	}

	/**
	 * A region's fields packed for the GPU, made on the worker so the render thread only hands them
	 * over. Off-heap: freed once uploaded, or by whoever drops the build.
	 */
	static final class Uploads {
		final int S, T;
		final ByteBuffer field, field2, dip, river, fetch, decor;

		/** Packs {@code r}; runs off the render thread. */
		Uploads(ShoreRegion r) {
			S = r.samples;
			T = r.size * 16 / r.decorScale;
			int res = r.res;
			// Fields: RGBA32F per sample = arrival, shore distance, exposure, block level (8 pool, 9 sea).
			field = MemoryUtil.memAlloc(S * S * 16);
			for (int sz = 0; sz < S; sz++) for (int sx = 0; sx < S; sx++) {
				int p = sz * S + sx, bi = (sz / res) * r.size + sx / res;
				boolean water = r.level[bi] == 0 && r.water[bi];
				field.putFloat(r.arrival[p]).putFloat(r.shoreDist[p]).putFloat(r.exposure[p])
					.putFloat(water ? (r.sea[bi] ? 9 : 8) : r.level[bi]);
			}
			field.flip();
			// Fields 2: waterline, far field, crossing front, crossing front's waterline.
			field2 = MemoryUtil.memAlloc(S * S * 16);
			for (int p = 0; p < S * S; p++) field2.putFloat(r.waterline[p]).putFloat(r.far[p]).putFloat(r.cross[p]).putFloat(r.crossWaterline[p]);
			field2.flip();
			// Dips: where each dip's water enters, and whether a sample is in a dip.
			dip = MemoryUtil.memAlloc(S * S * 16);
			for (int sz = 0; sz < S; sz++) for (int sx = 0; sx < S; sx++) {
				int bi = (sz / res) * r.size + sx / res;
				dip.putFloat(r.dipEntry[sz * S + sx]).putFloat(r.dipId[bi] >= 0 ? 1 : 0).putFloat(r.isle[sz * S + sx] ? 1 : 0).putFloat(0);
			}
			dip.flip();
			// Rivers: current (texels per second), how much river, how shallow.
			river = MemoryUtil.memAlloc(S * S * 16);
			for (int p = 0; p < S * S; p++) {
				if (r.riverFx == null) river.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
				else river.putFloat(r.riverFx[p]).putFloat(r.riverFy[p]).putFloat(r.riverBlend[p]).putFloat(r.riverShallow[p]);
			}
			river.flip();
			// Still water's fetch: per field sample, 16 directions, as four RGBA texels side by side.
			fetch = MemoryUtil.memCalloc(S * S * ShoreRegion.FETCH_DIRS);
			if (r.fetch != null) fetch.put(r.fetch).flip();
			// Rocks' wake, pillow and foam trails, and still water, per decor texel.
			decor = MemoryUtil.memCalloc(T * T * 4);
			if (r.riverDecor != null) decor.put(r.riverDecor).flip();
		}

		void free() {
			for (ByteBuffer b : new ByteBuffer[] {field, field2, dip, river, fetch, decor}) MemoryUtil.memFree(b);
		}
	}

	/** Uploads a region's packed fields for the bake, and frees them. */
	void upload(Uploads u) {
		GpuDevice device = RenderSystem.getDevice();
		ensureTextures(device);
		try {
			var encoder = device.createCommandEncoder();
			encoder.writeToTexture(fieldTexture, u.field, 0, 0, 0, 0, u.S, u.S);
			encoder.writeToTexture(field2Texture, u.field2, 0, 0, 0, 0, u.S, u.S);
			encoder.writeToTexture(dipTexture, u.dip, 0, 0, 0, 0, u.S, u.S);
			encoder.writeToTexture(riverTexture, u.river, 0, 0, 0, 0, u.S, u.S);
			encoder.writeToTexture(fetchTexture, u.fetch, 0, 0, 0, 0, u.S * 4, u.S);
			encoder.writeToTexture(riverDecorTexture, u.decor, 0, 0, 0, 0, u.T, u.T);
		} finally {
			u.free();
		}
	}

	/** The baked faces, registered so vanilla render types can sample them. */
	static final class FaceTexture extends AbstractTexture {
		FaceTexture(GpuTexture texture, GpuTextureView view) {
			this.texture = texture;
			this.textureView = view;
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		}
	}
}
