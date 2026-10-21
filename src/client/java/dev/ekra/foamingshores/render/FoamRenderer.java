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
import dev.ekra.foamingshores.shore.RiverMemory;
import dev.ekra.foamingshores.shore.ShoreRegion;
import dev.ekra.foamingshores.shore.ShoreSnapshot;
import dev.ekra.foamingshores.sound.ShoreAudio;
import dev.ekra.foamingshores.sound.ShoreSounds;
import dev.ekra.foamingshores.wave.WaveMath;
import dev.ekra.foamingshores.wave.WaveSchedule;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
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
import java.nio.file.Path;
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
		.putVec4().putVec4().putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
		.get() + 16 * 2 * MAX_WAVES;
	/** Seconds between the moments each wind pattern takes up (see pass). */
	private static final double WIND_EPOCH = 24;

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

	private GpuTexture fieldTexture, field2Texture, noiseTexture, faceDataTexture, dipTexture, riverTexture, riverDecorTexture, fetchTexture;
	private GpuTextureView fieldView, field2View, noiseView, faceDataView, dipView, riverView, riverDecorView, fetchView;
	/** Which way the current world's rivers run, kept between visits. */
	private RiverMemory rivers = new RiverMemory(null);
	private byte[] noiseBytes;
	private RegionTexture output, faceOutput;
	private MappableRingBuffer ubo, faceUbo;
	private List<Quad> faceQuads = List.of();
	private int faceCount;
	private final Splashes splashes = new Splashes();
	private final ShoreAudio audio = new ShoreAudio();
	private long lastBakeStep = Long.MIN_VALUE;
	/** Each wind pattern's moment and the rain it took up then. */
	private final long[] windMoment = {Long.MIN_VALUE, Long.MIN_VALUE};
	private final float[] windRain = new float[2];
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
			"splashHeight", "fallFade", "farSmooth", "wrapAngle", "minIsland", "waveVary", "travelFade", "wrap", "wrapLag",
			"riverSpeed", "riverRock", "riverWake", "riverTrail", "backwater", "straight")) {
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
			RiverMemory old = rivers;
			worker.execute(old::save);
			rivers = new RiverMemory(lvl == null ? null : riverFile(mc, lvl));
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
			ShoreRegion.Settings rs = new ShoreRegion.Settings(settings.minBody, settings.openWater, settings.downhill, settings.smoothing,
				settings.farSmooth, settings.wrapAngle, settings.minIsland, settings.runup * 16 * (1 + settings.waveVary) + settings.jag + 4,
				settings.riverSpeed, settings.riverRock, settings.riverWake, settings.riverTrail, settings.backwater, settings.approach * 16, settings.straight);
			RiverMemory memory = rivers;
			building = CompletableFuture.supplyAsync(() -> {
				try {
					ShoreRegion r = ShoreRegion.build(snap, rs, memory);
					memory.save();
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

	/**
	 * Where this world's river directions are kept: in a singleplayer world's own folder, or per
	 * server address in the config folder; one file per dimension.
	 */
	private static Path riverFile(Minecraft mc, ClientLevel lvl) {
		String dim = lvl.dimension().identifier().toString().replaceAll("[^A-Za-z0-9._-]", "_");
		IntegratedServer local = mc.getSingleplayerServer();
		if (local != null) return local.getWorldPath(LevelResource.ROOT).resolve("foamingshores").resolve("rivers_" + dim + ".json");
		ServerData server = mc.getCurrentServer();
		String name = (server != null ? server.ip : "unknown").replaceAll("[^A-Za-z0-9._-]", "_");
		return FabricLoader.getInstance().getConfigDir().resolve("foamingshores").resolve("rivers").resolve(name).resolve(dim + ".json");
	}

	/** Blocks whose top lies within reach of the waves or on a river (and its banks), with the level they sit at. */
	private List<int[]> bandBlocks(ShoreRegion r) {
		// Land is measured from its own waterline (up to the run-up's reach); the sea anywhere a
		// travelling front still has some size left.
		float lo = -(settings.runup * 16 * (1 + settings.waveVary) + settings.jag + 10), hi = settings.approach * 16 + settings.jag + settings.lipWidth * 2 + 10;
		float fade = settings.travelFade * 16;
		List<int[]> out = new ArrayList<>();
		int S = r.samples, res = ShoreRegion.RES;
		for (int bz = 0; bz < r.size; bz++) {
			for (int bx = 0; bx < r.size; bx++) {
				boolean hit = false;
				for (int sz = 0; sz < res && !hit; sz++) for (int sx = 0; sx < res && !hit; sx++) {
					int q = (bz * res + sz) * S + bx * res + sx;
					float a = r.arrival[q], w = r.waterline[q];
					hit = a > ShoreRegion.UNREACHABLE && a <= hi && a - w >= lo && w > -fade;
				}
				// River water, and the land beside it (its banks stay wet).
				if (!hit && r.riverBlend != null) hit = riverNear(r, bx, bz);
				if (hit) out.add(new int[] {bx, bz});
			}
		}
		return out;
	}

	/** Whether a block is river or still water, or land at the water's level right beside some. */
	private static boolean riverNear(ShoreRegion r, int bx, int bz) {
		int bi = bz * r.size + bx;
		if (r.water[bi]) {
			int S = r.samples, res = ShoreRegion.RES;
			for (int sz = 0; sz < res; sz++) for (int sx = 0; sx < res; sx++) if (r.riverBlend[(bz * res + sz) * S + bx * res + sx] > 0) return true;
			return false;
		}
		if (r.level[bi] != 0 || r.low[bi]) return false;
		for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
			int nx = bx + dx, nz = bz + dz;
			if (nx < 0 || nz < 0 || nx >= r.size || nz >= r.size) continue;
			if (r.river[nz * r.size + nx] || r.still[nz * r.size + nx]) return true;
		}
		return false;
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
			// Fields 2: waterline, far field, crossing front, crossing front's waterline.
			buf.clear();
			for (int p = 0; p < S * S; p++) buf.putFloat(r.waterline[p]).putFloat(r.far[p]).putFloat(r.cross[p]).putFloat(r.crossWaterline[p]);
			buf.flip();
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(field2Texture, buf, 0, 0, 0, 0, S, S);
			// Dips: where each dip's water enters, and whether a sample is in a dip.
			buf.clear();
			for (int sz = 0; sz < S; sz++) for (int sx = 0; sx < S; sx++) {
				int bi = (sz / ShoreRegion.RES) * r.size + sx / ShoreRegion.RES;
				buf.putFloat(r.dipEntry[sz * S + sx]).putFloat(r.dipId[bi] >= 0 ? 1 : 0).putFloat(r.isle[sz * S + sx] ? 1 : 0).putFloat(0);
			}
			buf.flip();
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(dipTexture, buf, 0, 0, 0, 0, S, S);
			// Rivers: current (texels per second), how much river, how shallow.
			buf.clear();
			for (int p = 0; p < S * S; p++) {
				if (r.riverFx == null) buf.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
				else buf.putFloat(r.riverFx[p]).putFloat(r.riverFy[p]).putFloat(r.riverBlend[p]).putFloat(r.riverShallow[p]);
			}
			buf.flip();
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(riverTexture, buf, 0, 0, 0, 0, S, S);
		} finally {
			MemoryUtil.memFree(buf);
		}
		// Still water's fetch: per field sample, 16 directions, as four RGBA texels side by side.
		ByteBuffer fetch = MemoryUtil.memCalloc(S * S * ShoreRegion.FETCH_DIRS);
		try {
			if (r.fetch != null) fetch.put(r.fetch).flip();
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(fetchTexture, fetch, 0, 0, 0, 0, S * 4, S);
		} finally {
			MemoryUtil.memFree(fetch);
		}
		// Rocks' wake, pillow and foam trails, and still water, per region texel.
		ByteBuffer decor = MemoryUtil.memCalloc(TEXELS * TEXELS * 4);
		try {
			if (r.riverDecor != null) decor.put(r.riverDecor).flip();
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(riverDecorTexture, decor, 0, 0, 0, 0, TEXELS, TEXELS);
		} finally {
			MemoryUtil.memFree(decor);
		}

		List<Quad> qs = new ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		final float t = 1f / TEXELS;
		for (int[] b : built.bandBlocks()) {
			int bx = b[0], bz = b[1], bi = bz * r.size + bx;
			int wx = r.originX + bx, wz = r.originZ + bz;
			boolean water = isWaterColumn(r, bi);
			if (!water && r.level[bi] > 0) continue; // walls: their tops get no foam; their faces are in buildFaces
			if (r.low[bi]) continue; // a pool below the body's level: the waves never get there
			// Water the foam lies on: the body's own, or a stream running over this land.
			boolean surface = r.surfY[bi] != ShoreSnapshot.NO_SURFACE;
			// Under falling water (every corner a full block) the surface is inside the waterfall.
			if (surface && water && r.corners[bi * 4] >= 1 && r.corners[bi * 4 + 1] >= 1 && r.corners[bi * 4 + 2] >= 1 && r.corners[bi * 4 + 3] >= 1) continue;
			float top = water ? r.waterY + 8f / 9f : r.waterY + 1 + r.level[bi];
			float y = top + (water ? 0.01f : 0.002f);
			pos.set(wx, surface ? r.surfY[bi] : r.waterY + 1 + r.level[bi], wz);
			int light = LightCoordsUtil.getLightCoords(lvl, pos);
			if (surface) light = LightCoordsUtil.max(light, LightCoordsUtil.getLightCoords(lvl, pos.above())); // as vanilla lights its surface
			float u0 = bx * 16 * t, v0 = bz * 16 * t, u1 = (bx + 1) * 16 * t, v1 = (bz + 1) * 16 * t;
			// Side quads stand SIDE_NUDGE off the block's sides and start at the top quad's height, so
			// reach the top that far over every side that drops to a lower neighbour: no slit between.
			float xa = wx - (surface || !lowerSide(r, bx, bz, -1, 0, top) ? 0 : SIDE_NUDGE);
			float xb = wx + 1 + (surface || !lowerSide(r, bx, bz, 1, 0, top) ? 0 : SIDE_NUDGE);
			float za = wz - (surface || !lowerSide(r, bx, bz, 0, -1, top) ? 0 : SIDE_NUDGE);
			float zb = wz + 1 + (surface || !lowerSide(r, bx, bz, 0, 1, top) ? 0 : SIDE_NUDGE);
			// On water, follow vanilla's surface corner by corner (in its vertex order, so the quad splits
			// into the same two triangles): flowing water slopes, and the sea dips a little by the shore.
			float yNW = y, ySW = y, ySE = y, yNE = y;
			if (surface) {
				float base = r.surfY[bi] + 0.01f;
				yNW = base + r.corners[bi * 4];
				ySW = base + r.corners[bi * 4 + 1];
				ySE = base + r.corners[bi * 4 + 2];
				yNE = base + r.corners[bi * 4 + 3];
			}
			qs.add(new Quad(
				new float[] {xa, yNW, za, xa, ySW, zb, xb, ySE, zb, xb, yNE, za},
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
				if ((!water && r.level[bi] > 0) || r.low[bi]) continue;
				float top = water ? r.waterY + 8f / 9f : r.topY[bi] + 1;
				for (int dir = 0; dir < 4 && n < MAX_FACES; dir++) {
					int dx = dir == 2 ? -1 : dir == 3 ? 1 : 0, dz = dir == 0 ? -1 : dir == 1 ? 1 : 0;
					int nx = bx + dx, nz = bz + dz;
					if (nx < 0 || nz < 0 || nx >= r.size || nz >= r.size) continue;
					int ni = nz * r.size + nx;
					boolean nWater = isWaterColumn(r, ni), nLow = r.low[ni];
					// Water beside: its surface where it meets this side (the lower of its two corners there).
					float nTop = nWater || nLow ? edgeSurface(r, ni, -dx, -dz) : r.topY[ni] + 1;
					int kind, h;
					float yTop;
					if (!nWater && !nLow && r.level[ni] > 0 && nTop > top + 0.01f) {
						kind = 2; // wall
						h = Math.min(washPx, Math.round((nTop - top) * 16));
						yTop = top + h / 16f;
					} else if (water && nLow && top - nTop > 0.3f) {
						kind = 5; // waterfall: the body's own water going over into lower water
						h = Math.min(fallPx, Math.round((top - nTop) * 16));
						yTop = top;
					} else if (!water && (top - nTop >= 2 - 0.01f || (nLow && top - nTop > 0.3f))) {
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
					else if (kind == 1 || kind == 5) dropRows.add(soundRow);
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
					// A wall rising out of the water, or a waterfall, starts where vanilla's surface meets
					// this side, which slopes where the water flows: the two surface corners on this side,
					// at its west/north end (a) and its east/south end (b).
					float da = 0, db = 0;
					if ((kind == 2 || kind == 5) && water) {
						int ca = dz < 0 ? 0 : dz > 0 ? 1 : dx < 0 ? 0 : 3, cb = dz < 0 ? 3 : dz > 0 ? 2 : dx < 0 ? 1 : 2;
						da = r.corners[bi * 4 + ca] - 8f / 9f;
						db = r.corners[bi * 4 + cb] - 8f / 9f;
					}
					float[] xyz;
					if (dz != 0) {
						float pz = (dz < 0 ? wz : wz + 1) + dz * nudge;
						xyz = new float[] {wx, yTop + da, pz, wx + 1, yTop + db, pz, wx + 1, yBot + db, pz, wx, yBot + da, pz};
					} else {
						float px = (dx < 0 ? wx : wx + 1) + dx * nudge;
						xyz = new float[] {px, yTop + da, wz, px, yTop + db, wz + 1, px, yBot + db, wz + 1, px, yBot + da, wz};
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
			audio.setSounds(ShoreSounds.build(r, wallRows, dropRows, settings));
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

	/** A water column's surface height (blocks) along its side (sx, sz): the lower of the two corners there. */
	private static float edgeSurface(ShoreRegion r, int bi, int sx, int sz) {
		int ca = sz < 0 ? 0 : sz > 0 ? 1 : sx < 0 ? 0 : 3, cb = sz < 0 ? 3 : sz > 0 ? 2 : sx < 0 ? 1 : 2;
		int base = r.surfY[bi] != ShoreSnapshot.NO_SURFACE ? r.surfY[bi] : r.waterY;
		return base + Math.min(r.corners[bi * 4 + ca], r.corners[bi * 4 + cb]);
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
		field2Texture = device.createTexture("Foaming Shores fields 2", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA32_FLOAT, S, S, 1, 1);
		field2View = device.createTextureView(field2Texture);
		dipTexture = device.createTexture("Foaming Shores dips", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA32_FLOAT, S, S, 1, 1);
		dipView = device.createTextureView(dipTexture);
		riverTexture = device.createTexture("Foaming Shores rivers", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA32_FLOAT, S, S, 1, 1);
		riverView = device.createTextureView(riverTexture);
		riverDecorTexture = device.createTexture("Foaming Shores river rocks", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, TEXELS, TEXELS, 1, 1);
		riverDecorView = device.createTextureView(riverDecorTexture);
		fetchTexture = device.createTexture("Foaming Shores fetch", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, S * 4, S, 1, 1);
		fetchView = device.createTextureView(fetchTexture);
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

	/**
	 * The wind at moment t for pattern i: the way it blows (x, z), its strength and its direction as
	 * 0..16 of the fetch's directions. The rain is the rain when the pattern took up this moment, so a
	 * shower coming on never changes a pattern's wind while it shows.
	 */
	private void putWind(Std140Builder b, FoamSettings s, int i, long moment, double t, float rainNow) {
		if (windMoment[i] != moment) {
			windMoment[i] = moment;
			windRain[i] = rainNow;
		}
		double ang = Math.toRadians(s.windDir + s.windShift * (WaveMath.vnoise(t / 90, 0.5, 501) * 2 - 1));
		double strength = Math.min(1.5, s.windStrength * (0.75 + 0.5 * WaveMath.vnoise(t / 40, 3.5, 503)) * (1 + 0.5 * windRain[i]));
		double dirIndex = ((ang / (Math.PI * 2) * ShoreRegion.FETCH_DIRS) % ShoreRegion.FETCH_DIRS + ShoreRegion.FETCH_DIRS) % ShoreRegion.FETCH_DIRS;
		b.putVec4((float) Math.cos(ang), (float) Math.sin(ang), (float) strength, (float) dirIndex);
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
				.putVec4(water.stillX, water.stillY, water.flowX, water.flowY)
				.putVec4(s.straight, s.peel, s.peelScale, s.waveVary)
				.putVec4(s.travelFade * 16, s.wrap, s.wrapLag * 16, 0);
			// Where the region sits on the swell's noise lattice (whole cells and the fraction), so the
			// shader works with small numbers however far out in the world the region is.
			double lx = region.originX / (double) Math.max(1, s.peelScale), lz = region.originZ / (double) Math.max(1, s.peelScale);
			b.putVec4((float) Math.floor(lx), (float) Math.floor(lz), (float) (lx - Math.floor(lx)), (float) (lz - Math.floor(lz)));
			b.putVec4(s.riverStreaks, s.riverLen, s.riverShallow, s.riverBank);
			// The wind: one for the whole world, wandering slowly about its set direction and strength,
			// following the world's time (so every player sees the same), stronger in rain. Ripples,
			// gusts and lapping read it through two patterns, each holding one moment's wind fixed:
			// turning a crest pattern sweeps it the faster the farther it is from where it turns, the
			// world's origin, so a wind that turned under drawn crests would send them racing. Each
			// pattern takes up a new moment only while it is hidden (its weight 0), every WIND_EPOCH
			// seconds; the shader swaps between the two patch by patch.
			float rain = level == null ? 0 : level.getRainLevel(1);
			double u = now / WIND_EPOCH;
			long kA = (long) Math.floor(u), kB = (long) Math.floor(u + 0.5);
			putWind(b, s, 0, kA, (kA + 0.5) * WIND_EPOCH, rain);
			b.putVec4(s.chop, s.chopPeriod, s.fetchFull * 16, s.chopRunup);
			b.putVec4(rain, s.rainRings, region.originX * 16f, region.originZ * 16f);
			putWind(b, s, 1, kB, kB * WIND_EPOCH, rain);
			b.putVec4((float) (1 - Math.abs(2 * (u - kA) - 1)), s.glints, s.paws, s.rises);
			b.putVec4(s.edgeRings, s.riverSparkle, s.recolorWater ? 1 : 0, s.edgeRingLayers);
			b.putVec4(s.bugRings, s.riverLenBySpeed, s.riverCountBySpeed, 0);
			b.putVec4(s.breeziness, s.breezeOften, s.breezeSize, s.breezeStrength);
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
			pass.setUniform("Field2Sampler", field2View, nearest);
			pass.setUniform("NoiseSampler", noiseView, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
			pass.setUniform("FaceSampler", faceDataView, nearest);
			pass.setUniform("DipSampler", dipView, nearest);
			pass.setUniform("RiverSampler", riverView, nearest);
			pass.setUniform("RiverDecorSampler", riverDecorView, nearest);
			pass.setUniform("FetchSampler", fetchView, nearest);
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
		submitQuads(context, poseStack, FoamPipelines.surface(TEXTURE_ID), quads, cam);
		if (!faceQuads.isEmpty()) submitQuads(context, poseStack, FoamPipelines.surface(FACES_ID), faceQuads, cam);
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

	/**
	 * How far each vertex is pulled toward the eye, per block of distance squared. Vanilla's reversed
	 * float depth tells the foam's small lift apart from the block under it at any range, but Iris
	 * turns reversed depth off while a shaderpack is on, and then one depth step is about
	 * d² / (near * 2^24) blocks: past ~40 blocks it outgrows the 0.002 lift and the foam z-fights.
	 * Pulling along the line of sight keeps every vertex where it is on screen and moves shared
	 * corners alike, so tops and faces stay joined; four depth steps of margin.
	 */
	private static final double EYE_PULL = 4.0 / (0.05 * (1 << 24));

	private static void submitQuads(LevelRenderContext context, PoseStack poseStack, RenderType type, List<Quad> qs, Vec3 cam) {
		context.submitNodeCollector().submitCustomGeometry(poseStack, type, (pose, buffer) -> {
			for (Quad q : qs) {
				float[] p = q.xyz(), uv = q.uv();
				for (int k = 0; k < 4; k++) {
					double dx = p[k * 3] - cam.x, dy = p[k * 3 + 1] - cam.y, dz = p[k * 3 + 2] - cam.z;
					double keep = 1 - EYE_PULL * Math.sqrt(dx * dx + dy * dy + dz * dz);
					vertex(buffer, pose, (float) (cam.x + dx * keep), (float) (cam.y + dy * keep), (float) (cam.z + dz * keep),
						uv[k * 2], uv[k * 2 + 1], q.light(), q.nx(), q.ny(), q.nz());
				}
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
