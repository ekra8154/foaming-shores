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
	 */
	record Spec(String name, int size, int res, int decorScale, int keepLo, int keepHi, int home, boolean fades) {
		int tiles() {
			return size / TILE;
		}
	}

	static final Spec NEAR = new Spec("near", 160, ShoreRegion.RES, 1, 4, 5, 4, false);
	static final Spec FAR = new Spec("far", 384, 2, 4, 9, 14, 11, true);

	/** Blocks at the region's edge where its fields are its parent's, and over which they blend in. */
	static final int MARGIN = 16, BAND = 16;

	final Spec spec;
	final int index;
	ShoreRegion region;
	/** Chunks missing from the snapshot behind the current region, and behind the one being built. */
	Set<Long> missing = Set.of(), buildingMissing = Set.of();
	/** Every tile with something to draw, by world tile; those this level draws (the near level's area is its own). */
	final Map<Long, Tile> tiles = new HashMap<>();
	final List<Tile> drawn = new ArrayList<>();
	List<FoamRenderer.Quad> faceQuads = List.of();
	int faceCount;
	long buildMs;

	GpuTexture fieldTexture, field2Texture, dipTexture, riverTexture, riverDecorTexture, fetchTexture, faceDataTexture, indexTexture;
	GpuTextureView fieldView, field2View, dipView, riverView, riverDecorView, fetchView, faceDataView, indexView;
	/** Per atlas page, the tile table the tile pass reads (see foam_tile.vsh). */
	final GpuTexture[] tableTextures = new GpuTexture[FoamAtlas.DENSITIES.length];
	final GpuTextureView[] tableViews = new GpuTextureView[FoamAtlas.DENSITIES.length];
	FaceTexture faceOutput;
	final Identifier faceId;
	MappableRingBuffer ubo, faceUbo;

	/** Tiles a table holds (its first texel is the header). */
	static final int TABLE = 1023;

	FoamLevel(int index, Spec spec) {
		this.index = index;
		this.spec = spec;
		this.faceId = Identifier.fromNamespaceAndPath("foamingshores", "foam_faces_" + spec.name());
	}

	/** A 16x16 block tile: its blocks with foam, their quads (uv over the tile, 0..1), and where it is baked. */
	static final class Tile {
		final int tx, tz;
		final List<int[]> blocks = new ArrayList<>();
		final List<FoamRenderer.Quad> quads = new ArrayList<>();
		/** The page and slot it was last baked into (drawn from), and those it is being baked into. */
		FoamAtlas.Page page, nextPage;
		int slot = -1, nextSlot = -1;

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

	/** Whether world tile (tx, tz) is one this level could draw: inside its region, not its outermost ring. */
	boolean covers(int tx, int tz) {
		if (region == null) return false;
		int i = tx - region.originX / TILE, j = tz - region.originZ / TILE, n = spec.tiles();
		return i >= 1 && j >= 1 && i < n - 1 && j < n - 1;
	}

	void ensureTextures(GpuDevice device) {
		if (fieldTexture != null) return;
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
			FoamRenderer.FACE_COLS * 2, FoamRenderer.MAX_FACES / FoamRenderer.FACE_COLS, 1, 1);
		faceDataView = device.createTextureView(faceDataTexture);
		GpuTexture faces = device.createTexture(n + "face foam", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
			GpuFormat.RGBA8_UNORM, FoamRenderer.FACE_TEX_W, FoamRenderer.FACE_TEX_H, 1, 1);
		faceOutput = new FaceTexture(faces, device.createTextureView(faces));
		Minecraft.getInstance().getTextureManager().register(faceId, faceOutput);
		ubo = new MappableRingBuffer(() -> n + "bake UBO", 130, FoamRenderer.UBO_SIZE);
		faceUbo = new MappableRingBuffer(() -> n + "face bake UBO", 130, FoamRenderer.UBO_SIZE);
	}

	/** Uploads a region's fields for the bake. */
	void upload(ShoreRegion r) {
		GpuDevice device = RenderSystem.getDevice();
		ensureTextures(device);
		// Fields: RGBA32F per sample = arrival, shore distance, exposure, block level (8 pool, 9 sea).
		int S = r.samples, res = r.res;
		ByteBuffer buf = MemoryUtil.memAlloc(S * S * 16);
		try {
			for (int sz = 0; sz < S; sz++) for (int sx = 0; sx < S; sx++) {
				int p = sz * S + sx, bi = (sz / res) * r.size + sx / res;
				boolean water = r.level[bi] == 0 && r.water[bi];
				buf.putFloat(r.arrival[p]).putFloat(r.shoreDist[p]).putFloat(r.exposure[p])
					.putFloat(water ? (r.sea[bi] ? 9 : 8) : r.level[bi]);
			}
			buf.flip();
			device.createCommandEncoder().writeToTexture(fieldTexture, buf, 0, 0, 0, 0, S, S);
			// Fields 2: waterline, far field, crossing front, crossing front's waterline.
			buf.clear();
			for (int p = 0; p < S * S; p++) buf.putFloat(r.waterline[p]).putFloat(r.far[p]).putFloat(r.cross[p]).putFloat(r.crossWaterline[p]);
			buf.flip();
			device.createCommandEncoder().writeToTexture(field2Texture, buf, 0, 0, 0, 0, S, S);
			// Dips: where each dip's water enters, and whether a sample is in a dip.
			buf.clear();
			for (int sz = 0; sz < S; sz++) for (int sx = 0; sx < S; sx++) {
				int bi = (sz / res) * r.size + sx / res;
				buf.putFloat(r.dipEntry[sz * S + sx]).putFloat(r.dipId[bi] >= 0 ? 1 : 0).putFloat(r.isle[sz * S + sx] ? 1 : 0).putFloat(0);
			}
			buf.flip();
			device.createCommandEncoder().writeToTexture(dipTexture, buf, 0, 0, 0, 0, S, S);
			// Rivers: current (texels per second), how much river, how shallow.
			buf.clear();
			for (int p = 0; p < S * S; p++) {
				if (r.riverFx == null) buf.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
				else buf.putFloat(r.riverFx[p]).putFloat(r.riverFy[p]).putFloat(r.riverBlend[p]).putFloat(r.riverShallow[p]);
			}
			buf.flip();
			device.createCommandEncoder().writeToTexture(riverTexture, buf, 0, 0, 0, 0, S, S);
		} finally {
			MemoryUtil.memFree(buf);
		}
		// Still water's fetch: per field sample, 16 directions, as four RGBA texels side by side.
		ByteBuffer fetch = MemoryUtil.memCalloc(S * S * ShoreRegion.FETCH_DIRS);
		try {
			if (r.fetch != null) fetch.put(r.fetch).flip();
			device.createCommandEncoder().writeToTexture(fetchTexture, fetch, 0, 0, 0, 0, S * 4, S);
		} finally {
			MemoryUtil.memFree(fetch);
		}
		// Rocks' wake, pillow and foam trails, and still water, per decor texel.
		int T = r.size * 16 / r.decorScale;
		ByteBuffer decor = MemoryUtil.memCalloc(T * T * 4);
		try {
			if (r.riverDecor != null) decor.put(r.riverDecor).flip();
			device.createCommandEncoder().writeToTexture(riverDecorTexture, decor, 0, 0, 0, 0, T, T);
		} finally {
			MemoryUtil.memFree(decor);
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
