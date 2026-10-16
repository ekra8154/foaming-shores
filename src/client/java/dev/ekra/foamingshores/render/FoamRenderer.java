package dev.ekra.foamingshores.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.ekra.foamingshores.FoamSettings;
import dev.ekra.foamingshores.FoamingShoresClient;
import dev.ekra.foamingshores.shore.ShoreRegion;
import dev.ekra.foamingshores.shore.ShoreSnapshot;
import dev.ekra.foamingshores.sound.ShoreAudio;
import dev.ekra.foamingshores.sound.ShoreSounds;
import dev.ekra.foamingshores.wave.WaveMath;
import dev.ekra.foamingshores.wave.WaveSchedule;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Prototype renderer for one region around the player:
 * <ol>
 * <li>every so often, snapshot the surface and build the shore fields on a worker thread;</li>
 * <li>every animation step (2 ticks by default), bake the region's foam into a texture on the GPU (stateless, so a bake needs
 *     nothing but the fields, the recent waves and the time);</li>
 * <li>every frame, draw a quad over each shore block through a vanilla translucent render type
 *     that samples that texture, so shader packs light it like any other translucent geometry.</li>
 * </ol>
 */
public final class FoamRenderer {
	/** Region side in blocks. */
	public static final int REGION = 96;
	private static final int TEXELS = REGION * 16;
	private static final int MAX_WAVES = 12;
	/** Rebuild once the player is this far (blocks) from the region's centre. */
	private static final int REBUILD_DISTANCE = 24;
	public static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath("foamingshores", "foam_region");
	public static final Identifier FACES_ID = Identifier.fromNamespaceAndPath("foamingshores", "foam_faces");
	/** A single white texel: droplets and flecks are coloured per vertex. */
	public static final Identifier PIXEL_ID = Identifier.fromNamespaceAndPath("foamingshores", "foam_pixel");
	/**
	 * Droplets and flecks draw exactly like vanilla's translucent particles: lightmap only, no
	 * directional face shading (which turned them grey depending on the view), and shader packs
	 * treat them as particles.
	 */
	private static final RenderType DROPLETS = RenderType.create("foamingshores_droplets",
		RenderSetup.builder(RenderPipelines.TRANSLUCENT_PARTICLE)
			.setOitPipelines(RenderPipelines.OIT_PARTICLE)
			.withTexture("Sampler0", PIXEL_ID)
			.useLightmap()
			.createRenderSetup());
	/** Face texture: tiles of 16 x TILE_H texels, FACE_COLS per row. */
	private static final int FACE_COLS = 128, TILE_H = 80, MAX_FACES = 2048;
	private static final int FACE_TEX_W = FACE_COLS * 16, FACE_TEX_H = (MAX_FACES / FACE_COLS) * TILE_H;
	private static final int UBO_SIZE = new Std140SizeCalculator()
		.putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
		.get() + 16 * 2 * MAX_WAVES;

	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Foaming Shores fields");
		t.setDaemon(true);
		return t;
	});
	private final FoamSettings settings = FoamSettings.INSTANCE;
	/** The settings as of the last apply, to tell what a save changed. */
	private FoamSettings applied = settings.copy();
	private boolean noiseDirty, wantRebuild;
	/** Game time at which a block-change rebuild is due, or MAX_VALUE for none. */
	private long rebuildAt = Long.MAX_VALUE;

	private ClientLevel level;
	private ShoreRegion region;
	private List<Quad> quads = List.of();
	private CompletableFuture<Built> building;
	/** Chunks missing from the snapshot behind the current region, and behind the one being built. */
	private Set<Long> missing = Set.of(), buildingMissing = Set.of();

	private GpuTexture fieldTexture, noiseTexture, faceDataTexture, dipTexture;
	private GpuTextureView fieldView, noiseView, faceDataView, dipView;
	private byte[] noiseBytes;
	private RegionTexture output, faceOutput;
	private MappableRingBuffer ubo, faceUbo;
	private List<Quad> faceQuads = List.of();
	private int faceCount;
	private final Splashes splashes = new Splashes();
	private final ShoreAudio audio = new ShoreAudio();
	private long lastBakeStep = Long.MIN_VALUE;
	/** Frames drawn so far, and the frame of the last bake: the bake's uniform buffer is a ring of 3
	 *  fenced per frame (like vanilla's lightmap), so it may be rotated at most once per frame. */
	private long frames, lastBakeFrame = -1;
	private boolean bakeFailed;
	private int waterColor = 0x3F76E4;

	/**
	 * A quad sampling the region texture: four corners (x, y, z) with their (u, v), a normal and a
	 * light value. Tops cover a block's 16x16 texels; faces stretch the 1-texel strip along the
	 * block's edge down the face, so a face shows what the sand at its edge shows.
	 */
	private record Quad(float[] xyz, float[] uv, float nx, float ny, float nz, int light) {
	}

	private record Built(ShoreRegion region, List<int[]> bandBlocks) {
	}

	// ---- Tick: keep the region current and bake ---------------------------------------------

	/**
	 * Called after the settings screen saves. Settings that shape the shore fields or which blocks
	 * get foam rebuild the region; the noise scales regenerate the noise; the rest show on the next
	 * bake.
	 */
	public void settingsChanged() {
		if (settings.differs(applied, "smoothing", "minBody", "openWater", "downhill", "runup", "approach", "jag", "lipWidth",
			"splashHeight", "fallFade")) {
			wantRebuild = true; // rebuilt on the next tick, keeping the current one until then
		}
		if (settings.differs(applied, "jagScale", "laceCell")) noiseDirty = true;
		audio.settingsChanged();
		applied = settings.copy();
		lastBakeStep = Long.MIN_VALUE;
	}

	/**
	 * A block changed. Changes near the water's height inside the region rebuild it, a few ticks later
	 * so a burst of changes (an explosion, a fill) costs one rebuild.
	 */
	public void blockChanged(ClientLevel lvl, BlockPos pos) {
		ShoreRegion r = region;
		if (lvl != level || r == null) return;
		if (pos.getX() < r.originX || pos.getZ() < r.originZ || pos.getX() >= r.originX + REGION || pos.getZ() >= r.originZ + REGION) return;
		if (Math.abs(pos.getY() - r.waterY) > 6) return;
		rebuildAt = lvl.getGameTime() + 5;
	}

	/** A chunk the current (or pending) region had to treat as open water has arrived: rebuild with it. */
	public void chunkLoaded(ClientLevel lvl, ChunkPos pos) {
		if (lvl != level) return;
		long key = pos.pack();
		if (missing.contains(key) || buildingMissing.contains(key)) wantRebuild = true;
	}

	public void tick(Minecraft mc) {
		ClientLevel lvl = mc.level;
		if (lvl != level) {
			level = lvl;
			region = null;
			quads = List.of();
			faceQuads = List.of();
			faceCount = 0;
			building = null;
			splashes.clear();
			audio.clear();
		}
		audio.tick(mc, lvl == null ? 0 : lvl.getGameTime() / 20.0);
		if (lvl == null || mc.player == null) return;

		if (building != null && building.isDone()) {
			Built built = building.join();
			building = null;
			if (built != null) {
				install(lvl, built);
				missing = buildingMissing;
			}
		}
		if (lvl.getGameTime() >= rebuildAt) {
			rebuildAt = Long.MAX_VALUE;
			wantRebuild = true;
		}
		int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
		boolean stale = region == null || wantRebuild
			|| Math.abs(px - (region.originX + REGION / 2)) > REBUILD_DISTANCE
			|| Math.abs(pz - (region.originZ + REGION / 2)) > REBUILD_DISTANCE;
		if (stale && building == null) {
			wantRebuild = false;
			ShoreSnapshot snap = ShoreSnapshot.capture(lvl, px - REGION / 2, pz - REGION / 2, REGION);
			buildingMissing = snap.missingChunks;
			ShoreRegion.Settings rs = new ShoreRegion.Settings(settings.minBody, settings.openWater, settings.downhill, settings.smoothing);
			building = CompletableFuture.supplyAsync(() -> {
				try {
					ShoreRegion r = ShoreRegion.build(snap, rs);
					return new Built(r, bandBlocks(r));
				} catch (Throwable t) {
					FoamingShoresClient.LOGGER.error("Building shore fields failed", t);
					return null;
				}
			}, worker);
		}
		waterColor = BiomeColors.getAverageWaterColor(lvl, mc.player.blockPosition());

		if (noiseDirty && noiseTexture != null) {
			noiseDirty = false;
			uploadNoise(RenderSystem.getDevice());
		}
		if (region != null) {
			double now = lvl.getGameTime() / 20.0;
			List<WaveSchedule.Wave> recent = WaveSchedule.recent(now, settings, MAX_WAVES);
			splashes.tick(now, recent, settings);
		}

		// The animation moves in steps of `ticks` game ticks (vanilla water uses 2).
		int every = Math.max(1, settings.ticks);
		long step = lvl.getGameTime() / every;
		// Several ticks can run in one frame (catching up after a join or a hitch); bake once per frame.
		if (region != null && step != lastBakeStep && frames != lastBakeFrame) {
			lastBakeStep = step;
			lastBakeFrame = frames;
			bake(step * every);
		}
	}

	/** Blocks whose top lies within reach of the waves, with the level they sit at. */
	private List<int[]> bandBlocks(ShoreRegion r) {
		float lo = -(settings.runup * 16 + settings.jag + 10), hi = settings.approach * 16 + settings.jag + settings.lipWidth * 2 + 10;
		List<int[]> out = new ArrayList<>();
		int S = r.samples, res = ShoreRegion.RES;
		for (int bz = 0; bz < r.size; bz++) {
			for (int bx = 0; bx < r.size; bx++) {
				boolean hit = false;
				for (int sz = 0; sz < res && !hit; sz++) for (int sx = 0; sx < res && !hit; sx++) {
					float a = r.arrival[(bz * res + sz) * S + bx * res + sx];
					hit = a >= lo && a <= hi;
				}
				if (hit) out.add(new int[] {bx, bz});
			}
		}
		return out;
	}

	private void install(ClientLevel lvl, Built built) {
		ShoreRegion r = built.region();
		ensureTextures();
		// Fields: RGBA32F per sample = arrival, shore distance, exposure, block level (8 pool, 9 sea).
		int S = r.samples;
		ByteBuffer buf = MemoryUtil.memAlloc(S * S * 16);
		try {
			for (int sz = 0; sz < S; sz++) for (int sx = 0; sx < S; sx++) {
				int p = sz * S + sx, bi = (sz / ShoreRegion.RES) * r.size + sx / ShoreRegion.RES;
				boolean water = r.level[bi] == 0 && isWaterColumn(r, bi);
				buf.putFloat(r.arrival[p]).putFloat(r.shoreDist[p]).putFloat(r.exposure[p])
					.putFloat(water ? (r.sea[bi] ? 9 : 8) : r.level[bi]);
			}
			buf.flip();
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(fieldTexture, buf, 0, 0, 0, 0, S, S);
			// Dips: where each dip's water enters, and whether a sample is in a dip.
			buf.clear();
			for (int sz = 0; sz < S; sz++) for (int sx = 0; sx < S; sx++) {
				int bi = (sz / ShoreRegion.RES) * r.size + sx / ShoreRegion.RES;
				buf.putFloat(r.dipEntry[sz * S + sx]).putFloat(r.dipId[bi] >= 0 ? 1 : 0).putFloat(0).putFloat(0);
			}
			buf.flip();
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(dipTexture, buf, 0, 0, 0, 0, S, S);
		} finally {
			MemoryUtil.memFree(buf);
		}

		List<Quad> qs = new ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		final float t = 1f / TEXELS;
		for (int[] b : built.bandBlocks()) {
			int bx = b[0], bz = b[1], bi = bz * r.size + bx;
			int wx = r.originX + bx, wz = r.originZ + bz;
			boolean water = isWaterColumn(r, bi);
			if (!water && r.level[bi] > 0) continue; // walls: their tops get no foam; their faces are in buildFaces
			float top = water ? r.waterY + 8f / 9f : r.waterY + 1 + r.level[bi];
			float y = top + (water ? 0.01f : 0.002f);
			pos.set(wx, water ? r.waterY : r.waterY + 1 + r.level[bi], wz);
			int light = LightCoordsUtil.getLightCoords(lvl, pos);
			float u0 = bx * 16 * t, v0 = bz * 16 * t, u1 = (bx + 1) * 16 * t, v1 = (bz + 1) * 16 * t;
			// Side quads stand SIDE_NUDGE off the block's sides and start at the top quad's height, so
			// reach the top that far over every side that drops to a lower neighbour: no slit between.
			float xa = wx - (water || !lowerSide(r, bx, bz, -1, 0, top) ? 0 : SIDE_NUDGE);
			float xb = wx + 1 + (water || !lowerSide(r, bx, bz, 1, 0, top) ? 0 : SIDE_NUDGE);
			float za = wz - (water || !lowerSide(r, bx, bz, 0, -1, top) ? 0 : SIDE_NUDGE);
			float zb = wz + 1 + (water || !lowerSide(r, bx, bz, 0, 1, top) ? 0 : SIDE_NUDGE);
			qs.add(new Quad(
				new float[] {xa, y, za, xa, y, zb, xb, y, zb, xb, y, za},
				new float[] {u0, v0, u0, v1, u1, v1, u1, v0}, 0, 1, 0, light));
		}
		buildFaces(lvl, r, built.bandBlocks());
		region = r;
		quads = qs;
		lastBakeStep = Long.MIN_VALUE;
	}

	/**
	 * Wall and drop faces. Both hang off a shore block's edge: a wall is a neighbour whose top is
	 * higher (the face is the wall's side, washed from its foot), a drop is a neighbour at least two
	 * blocks lower (the face is the shore block's own side, which water pours down). Each face's column
	 * u follows the shore block's texel along the shared edge; the face table tells the bake shader
	 * where that texel is, which way the face points, its kind and its height.
	 */
	private void buildFaces(ClientLevel lvl, ShoreRegion r, List<int[]> band) {
		int washPx = Math.min(TILE_H, Math.round(settings.splashHeight * 0.6f) + 1);
		int fallPx = Math.min(TILE_H, (int) Math.ceil(settings.fallFade * 1.65f) + 1);
		List<Quad> qs = new ArrayList<>();
		List<Splashes.Foot> feet = new ArrayList<>();
		// For the sound: each wall and drop face as (edge texel x, z, step x, z, out x, z, top in texels).
		List<int[]> wallRows = new ArrayList<>(), dropRows = new ArrayList<>();
		ByteBuffer data = MemoryUtil.memAlloc(FACE_COLS * 2 * (MAX_FACES / FACE_COLS) * 16);
		try {
			MemoryUtil.memSet(data, 0);
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			int n = 0;
			for (int[] b : band) {
				int bx = b[0], bz = b[1], bi = bz * r.size + bx;
				boolean water = isWaterColumn(r, bi);
				if (!water && r.level[bi] > 0) continue;
				float top = water ? r.waterY + 8f / 9f : r.topY[bi] + 1;
				for (int dir = 0; dir < 4 && n < MAX_FACES; dir++) {
					int dx = dir == 2 ? -1 : dir == 3 ? 1 : 0, dz = dir == 0 ? -1 : dir == 1 ? 1 : 0;
					int nx = bx + dx, nz = bz + dz;
					if (nx < 0 || nz < 0 || nx >= r.size || nz >= r.size) continue;
					int ni = nz * r.size + nx;
					boolean nWater = isWaterColumn(r, ni);
					float nTop = nWater ? r.waterY + 8f / 9f : r.topY[ni] + 1;
					int kind, h;
					float yTop;
					if (!nWater && r.level[ni] > 0 && nTop > top + 0.01f) {
						kind = 2; // wall
						h = Math.min(washPx, Math.round((nTop - top) * 16));
						yTop = top + h / 16f;
					} else if (!water && top - nTop >= 2 - 0.01f) {
						kind = 1; // drop
						h = Math.min(fallPx, Math.round((top - nTop) * 16));
						yTop = top + 0.002f; // level with the top quad, which reaches over this side
					} else if (!water && top - nTop > 0.01f) {
						kind = nWater ? 3 : 4; // the ledge over the sea, or a step down into a dip
						h = Math.min(TILE_H, Math.max(1, Math.round((top - nTop) * 16)));
						yTop = top + 0.002f;
					} else continue;
					if (h <= 0) continue;

					// The shore block's texels along the shared edge, as region texel coordinates.
					float ex = dx > 0 ? bx * 16 + 15 : bx * 16;
					float ez = dz > 0 ? bz * 16 + 15 : bz * 16;
					float sx = dz != 0 ? 1 : 0, sz = dx != 0 ? 1 : 0;
					int col = n % FACE_COLS, row = n / FACE_COLS;
					data.position((row * FACE_COLS * 2 + col * 2) * 16);
					data.putFloat(ex).putFloat(ez).putFloat(sx).putFloat(sz);
					data.putFloat(dx).putFloat(dz).putFloat(kind).putFloat(h);
					int[] soundRow = {Math.round(ex), Math.round(ez), Math.round(sx), Math.round(sz), dx, dz, Math.round(top * 16)};
					if (kind == 2) wallRows.add(soundRow);
					else if (kind == 1) dropRows.add(soundRow);
					if (kind == 2) {
						for (int u = 0; u < 16; u++)
							feet.add(new Splashes.Foot((int) (ex + sx * u), (int) (ez + sz * u), dx, dz, top));
					}

					// The quad on the shared side, nudged off the block face it overlays: a drop's out
					// over the drop, a wall's back toward the shore.
					int wx = r.originX + bx, wz = r.originZ + bz;
					float nudge = kind == 2 ? -SIDE_NUDGE : SIDE_NUDGE;
					float yBot = yTop - h / 16f;
					float u0 = col * 16f / FACE_TEX_W, u1 = (col + 1) * 16f / FACE_TEX_W;
					float vT = row * (float) TILE_H / FACE_TEX_H, vB = (row * TILE_H + h) / (float) FACE_TEX_H;
					float[] xyz;
					if (dz != 0) {
						float pz = (dz < 0 ? wz : wz + 1) + dz * nudge;
						xyz = new float[] {wx, yTop, pz, wx + 1, yTop, pz, wx + 1, yBot, pz, wx, yBot, pz};
					} else {
						float px = (dx < 0 ? wx : wx + 1) + dx * nudge;
						xyz = new float[] {px, yTop, wz, px, yTop, wz + 1, px, yBot, wz + 1, px, yBot, wz};
					}
					float[] uv = {u0, vT, u1, vT, u1, vB, u0, vB};
					// Light from the open block in front of the face.
					int lx = kind != 2 ? r.originX + nx : wx, lz = kind != 2 ? r.originZ + nz : wz;
					pos.set(lx, (int) Math.floor(yTop - 0.5f), lz);
					float sign = kind != 2 ? 1 : -1;
					qs.add(new Quad(xyz, uv, dx * sign, 0, dz * sign, LightCoordsUtil.getLightCoords(lvl, pos)));
					n++;
				}
			}
			data.position(0);
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(faceDataTexture, data, 0, 0, 0, 0, FACE_COLS * 2, MAX_FACES / FACE_COLS);
			faceCount = n;
			faceQuads = qs;
			splashes.setRegion(r, feet);
			audio.setSounds(ShoreSounds.build(r, wallRows, dropRows));
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	/** How far side quads stand off the block faces they overlay. */
	private static final float SIDE_NUDGE = 0.002f;

	/** Whether the neighbour in direction (dx, dz) has a lower top than {@code top}, so this side gets a face. */
	private static boolean lowerSide(ShoreRegion r, int bx, int bz, int dx, int dz, float top) {
		int nx = bx + dx, nz = bz + dz;
		if (nx < 0 || nz < 0 || nx >= r.size || nz >= r.size) return false;
		int ni = nz * r.size + nx;
		float nTop = isWaterColumn(r, ni) ? r.waterY + 8f / 9f : r.waterY + 1 + r.level[ni];
		return nTop < top - 0.01f;
	}

	private static boolean isWaterColumn(ShoreRegion r, int bi) {
		return r.water[bi];
	}

	private void ensureTextures() {
		if (output != null) return;
		GpuDevice device = RenderSystem.getDevice();
		int S = REGION * ShoreRegion.RES;
		fieldTexture = device.createTexture("Foaming Shores fields", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA32_FLOAT, S, S, 1, 1);
		fieldView = device.createTextureView(fieldTexture);
		dipTexture = device.createTexture("Foaming Shores dips", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA32_FLOAT, S, S, 1, 1);
		dipView = device.createTextureView(dipTexture);
		noiseTexture = device.createTexture("Foaming Shores noise", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, FoamNoise.SIZE, FoamNoise.SIZE, 1, 1);
		noiseView = device.createTextureView(noiseTexture);
		uploadNoise(device);
		GpuTexture out = device.createTexture("Foaming Shores foam", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, TEXELS, TEXELS, 1, 1);
		output = new RegionTexture(out, device.createTextureView(out));
		Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, output);
		ubo = new MappableRingBuffer(() -> "Foaming Shores bake UBO", 130, UBO_SIZE);

		faceDataTexture = device.createTexture("Foaming Shores face table", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA32_FLOAT, FACE_COLS * 2, MAX_FACES / FACE_COLS, 1, 1);
		faceDataView = device.createTextureView(faceDataTexture);
		GpuTexture faces = device.createTexture("Foaming Shores face foam", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, FACE_TEX_W, FACE_TEX_H, 1, 1);
		faceOutput = new RegionTexture(faces, device.createTextureView(faces));
		Minecraft.getInstance().getTextureManager().register(FACES_ID, faceOutput);
		faceUbo = new MappableRingBuffer(() -> "Foaming Shores face bake UBO", 130, UBO_SIZE);

		DynamicTexture pixel = new DynamicTexture(() -> "Foaming Shores pixel", 1, 1, false);
		pixel.getPixels().setPixel(0, 0, 0xFFFFFFFF);
		pixel.upload();
		Minecraft.getInstance().getTextureManager().register(PIXEL_ID, pixel);
	}

	private void uploadNoise(GpuDevice device) {
		byte[] noise = FoamNoise.build(settings.jagScale, settings.laceCell);
		noiseBytes = noise;
		splashes.setNoise(noise);
		audio.setNoise(noise);
		ByteBuffer nb = MemoryUtil.memAlloc(noise.length);
		try {
			nb.put(noise).flip();
			device.createCommandEncoder().writeToTexture(noiseTexture, nb, 0, 0, 0, 0, FoamNoise.SIZE, FoamNoise.SIZE);
		} finally {
			MemoryUtil.memFree(nb);
		}
	}

	/** Draws the region's foam at the given game tick into the region texture. */
	private void bake(long tick) {
		// Keep times small for float precision: seconds since a recent round epoch.
		long epochTicks = (tick / 24000) * 24000;
		double now = tick / 20.0, epoch = epochTicks / 20.0;
		List<WaveSchedule.Wave> waves = WaveSchedule.recent(now, settings, MAX_WAVES);
		CompiledRenderPipeline pipeline = bakeFailed ? null : RenderSystem.getCompiledPipelineNullable(FoamPipelines.BAKE);
		if (pipeline == null) {
			if (!bakeFailed) FoamingShoresClient.LOGGER.error("The foam bake shader did not compile; Foaming Shores will draw nothing");
			bakeFailed = true;
			return;
		}
		WaterSprites water = waterSprites();
		pass(pipeline, ubo, output, waves, now, epoch, 0, water);
		if (faceCount > 0) pass(pipeline, faceUbo, faceOutput, waves, now, epoch, 1, water);
	}

	/** Where vanilla's water textures sit in the block atlas, in atlas texels. */
	private record WaterSprites(GpuTextureView atlas, int stillX, int stillY, int flowX, int flowY) {
	}

	/** Looked up at every bake: a resource reload can move the sprites. */
	private static WaterSprites waterSprites() {
		Minecraft mc = Minecraft.getInstance();
		FluidModel model = mc.getModelManager().getFluidStateModelSet().get(Fluids.WATER.defaultFluidState());
		TextureAtlasSprite still = model.stillMaterial().sprite(), flow = model.flowingMaterial().sprite();
		AbstractTexture atlas = mc.getTextureManager().getTexture(still.atlasLocation());
		GpuTextureView view = atlas.getTextureView();
		int w = view.getWidth(0), h = view.getHeight(0);
		return new WaterSprites(view,
			Math.round(still.getU0() * w), Math.round(still.getV0() * h),
			Math.round(flow.getU0() * w), Math.round(flow.getV0() * h));
	}

	/** One bake pass: mode 0 draws block tops into the region texture, mode 1 draws faces. */
	private void pass(CompiledRenderPipeline pipeline, MappableRingBuffer buffer, RegionTexture target, List<WaveSchedule.Wave> waves,
		double now, double epoch, int mode, WaterSprites water) {
		FoamSettings s = settings;
		try (GpuBufferSlice.MappedView view = buffer.currentBuffer().map(false, true)) {
			Std140Builder b = Std140Builder.intoBuffer(view.data())
				.putVec4(TEXELS, ShoreRegion.RES, REGION * ShoreRegion.RES, 0)
				.putVec4(Math.floorMod(region.originX * 16, 256), Math.floorMod(region.originZ * 16, 256), (float) (now - epoch), waves.size())
				.putVec4(s.lipWidth, s.jag, s.offshore, s.laceCover)
				.putVec4(s.laceWet, s.laceDry, s.laceSea, s.drift)
				.putVec4(s.dryTime, s.wetDark, s.sheetAlpha, s.shelter)
				.putVec4(s.approach * 16, s.runup * 16, s.speed * 16, s.stall)
				.putVec4(s.backwash, s.foamTint, s.soak, s.puddleSpread)
				.putVec4(((waterColor >> 16) & 255) / 255f, ((waterColor >> 8) & 255) / 255f, (waterColor & 255) / 255f, 1)
				.putVec4(s.spill, s.fallFade, s.splashHeight, s.pile)
				.putVec4(mode, faceCount, FACE_TEX_W, FACE_TEX_H)
				.putVec4(water.stillX, water.stillY, water.flowX, water.flowY);
			for (int i = 0; i < MAX_WAVES; i++) {
				if (i < waves.size()) {
					WaveSchedule.Wave w = waves.get(i);
					b.putVec4((float) (w.t0() - epoch), w.amp(), w.ox(), w.oy());
					b.putVec4(w.lx(), w.ly(), w.bx(), w.by());
				} else {
					b.putVec4(0, 0, 0, 0).putVec4(0, 0, 0, 0);
				}
			}
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		try (RenderPass pass = encoder.createRenderPass(() -> "Foaming Shores bake", target.getTextureView(), Optional.empty())) {
			pass.setPipeline(pipeline);
			pass.setUniform("FoamBake", buffer.currentBuffer());
			pass.setUniform("FieldSampler", fieldView, nearest);
			pass.setUniform("NoiseSampler", noiseView, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
			pass.setUniform("FaceSampler", faceDataView, nearest);
			pass.setUniform("DipSampler", dipView, nearest);
			pass.setUniform("AtlasSampler", water.atlas(), nearest);
			pass.setUniform("RegionSampler", mode == 1 ? output.getTextureView() : noiseView, nearest);
			pass.draw(3, 1, 0, 0);
		}
		buffer.rotate();
	}

	// ---- Frame: draw the quads ---------------------------------------------------------------------

	public void submit(LevelRenderContext context) {
		frames++;
		if (region == null || quads.isEmpty() || output == null || bakeFailed) return;
		Vec3 cam = context.levelState().cameraRenderState.pos;
		PoseStack poseStack = context.poseStack();
		poseStack.pushPose();
		poseStack.translate(-cam.x, -cam.y, -cam.z);
		RenderType type = RenderTypes.entityTranslucent(TEXTURE_ID, false);
		submitQuads(context, poseStack, type, quads);
		if (!faceQuads.isEmpty()) submitQuads(context, poseStack, RenderTypes.entityTranslucent(FACES_ID, false), faceQuads);
		if (!splashes.isEmpty() && level != null) {
			var camState = context.levelState().cameraRenderState;
			float ft = settings.foamTint;
			int foam = ((int) (250 + (((waterColor >> 16) & 255) - 250) * ft) << 16)
				| ((int) (252 + (((waterColor >> 8) & 255) - 252) * ft) << 8)
				| (int) (250 + ((waterColor & 255) - 250) * ft);
			ClientLevel lvl = level;
			context.submitNodeCollector().submitCustomGeometry(poseStack, DROPLETS,
				(pose, buffer) -> splashes.render(pose, buffer, lvl, camState.orientation, camState.cameraEntityPartialTicks, foam));
		}
		poseStack.popPose();
	}

	private static void submitQuads(LevelRenderContext context, PoseStack poseStack, RenderType type, List<Quad> qs) {
		context.submitNodeCollector().submitCustomGeometry(poseStack, type, (pose, buffer) -> {
			for (Quad q : qs) {
				float[] p = q.xyz(), uv = q.uv();
				for (int k = 0; k < 4; k++)
					vertex(buffer, pose, p[k * 3], p[k * 3 + 1], p[k * 3 + 2], uv[k * 2], uv[k * 2 + 1], q.light(), q.nx(), q.ny(), q.nz());
			}
		});
	}

	private static void vertex(com.mojang.blaze3d.vertex.VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, float u, float v, int light,
		float nx, float ny, float nz) {
		buffer.addVertex(pose, x, y, z)
			.setColor(255, 255, 255, 255)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(light)
			.setNormal(pose, nx, ny, nz);
	}

	/** The baked foam texture, registered so vanilla render types can sample it. */
	private static final class RegionTexture extends AbstractTexture {
		RegionTexture(GpuTexture texture, GpuTextureView view) {
			this.texture = texture;
			this.textureView = view;
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		}
	}
}
