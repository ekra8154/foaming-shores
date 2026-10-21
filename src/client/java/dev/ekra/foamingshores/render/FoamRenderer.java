package dev.ekra.foamingshores.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Draws the foam in two levels of detail round the player (see {@link FoamLevel}):
 * <ol>
 * <li>every so often, snapshot the surface and build each level's shore fields on worker threads
 *     (the near level as soon as it is needed, the far level now and then, a few rows of the world
 *     read per tick, then the near level again inside it so the two agree);</li>
 * <li>every animation step (2 ticks by default), bake every tile with foam into the atlas (see
 *     {@link FoamAtlas}) at the density its distance needs, and the faces (stateless, so a bake
 *     needs nothing but the fields, the recent waves and the time);</li>
 * <li>every frame, draw a quad over each shore block through a vanilla translucent render type
 *     that samples the atlas, so shader packs light it like any other translucent geometry.</li>
 * </ol>
 */
public final class FoamRenderer {
	private static final int MAX_WAVES = 12;
	/**
	 * While chunks round the player are still arriving (joining, teleporting, flying into new land),
	 * a build waits until none has come for this many ticks, so a burst of chunks costs one build
	 * instead of a chain of them each waiting for the last; but never longer than MAX_SETTLE ticks.
	 */
	private static final int SETTLE_TICKS = 2, MAX_SETTLE = 10;
	/**
	 * Ticks between far rebuilds for chunks arriving at its edge or blocks changing in it (chunks keep
	 * arriving there as the player travels); moving out of its window rebuilds it at once.
	 */
	private static final int FAR_COOLDOWN = 60;
	/** Nanoseconds of world reading per tick for the far level's snapshot. */
	private static final long FAR_CAPTURE_BUDGET = 3_000_000;
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
	static final int FACE_COLS = 128, TILE_H = 80, MAX_FACES = 2048;
	static final int FACE_TEX_W = FACE_COLS * 16, FACE_TEX_H = (MAX_FACES / FACE_COLS) * TILE_H;
	static final int UBO_SIZE = new Std140SizeCalculator()
		.putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
		.get() + 16 * 2 * MAX_WAVES;
	/** Seconds between the moments each wind pattern takes up (see writeUbo). */
	private static final double WIND_EPOCH = 24;

	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Foaming Shores fields");
		t.setDaemon(true);
		return t;
	});
	private final FoamSettings settings = FoamSettings.INSTANCE;
	/** The settings as of the last apply, to tell what a save changed. */
	private FoamSettings applied = settings.copy();
	private boolean noiseDirty, wantNear, wantFar;
	/** Game time at which a block-change rebuild of each level is due, or MAX_VALUE for none. */
	private long nearAt = Long.MAX_VALUE, farAt = Long.MAX_VALUE;

	private ClientLevel level;
	private final FoamLevel near = new FoamLevel(0, FoamLevel.NEAR), far = new FoamLevel(1, FoamLevel.FAR);
	private final FoamLevel[] levels = {near, far};
	private CompletableFuture<Built> building;
	/** A far level built and waiting for the near level built inside it, to go in together. */
	private Built pendingFar;
	/** The far level's snapshot while it is being read, a few rows a tick. */
	private ShoreSnapshot.Capture farCapture;
	private long farCaptureNanos, lastFarStart = -1000;

	private FoamAtlas atlas;
	private GpuTexture noiseTexture;
	private GpuTextureView noiseView;
	/** Which way the current world's rivers run, kept between visits. */
	private RiverMemory rivers = new RiverMemory(null);
	private byte[] noiseBytes;
	private final Splashes splashes = new Splashes();
	private final ShoreAudio audio = new ShoreAudio();
	private long lastBakeStep = Long.MIN_VALUE;
	/** Client ticks so far, the tick a chunk round the player last arrived, and since when a level has been stale. */
	private long ticks, chunkArrivedAt = -1000, staleSince = -1;
	private int playerX, playerZ;
	/** Each wind pattern's moment and the rain it took up then. */
	private final long[] windMoment = {Long.MIN_VALUE, Long.MIN_VALUE};
	private final float[] windRain = new float[2];
	/** Frames drawn so far, and the frame of the last bake: the bake's uniform buffers are rings of 3
	 *  fenced per frame (like vanilla's lightmap), so each may be rotated at most once per frame. */
	private long frames, lastBakeFrame = -1;
	private boolean bakeFailed;
	private int waterColor = 0x3F76E4;

	// For the F3 readout: GPU time of the bakes (a ring of timestamp pairs read a few frames late),
	// the drawing's CPU time per frame, what the last bake did.
	private GpuQueryPool[] timers;
	private int timer;
	private boolean timersFailed;
	private double bakeGpuMs, submitMs, installMs, farCaptureMs;
	private final int[] tilesAt = new int[FoamAtlas.DENSITIES.length];
	private long pixelsBaked;

	/**
	 * A quad sampling a baked texture: four corners (x, y, z) with their (u, v), a normal and a
	 * light value. Tops cover a block's texels of its tile (uv over the tile, 0..1); faces stretch
	 * their tile of the face texture down the face.
	 */
	record Quad(float[] xyz, float[] uv, float nx, float ny, float nz, int light) {
	}

	private record Built(FoamLevel level, ShoreRegion region, List<int[]> bandBlocks, long ms) {
	}

	public FoamRenderer() {
		// Build a made-up shore a few times while the game starts, so the first build in a world runs
		// compiled code (the first, interpreted builds take several times as long).
		worker.execute(() -> {
			try {
				ShoreSnapshot snap = ShoreSnapshot.warmUp(96);
				for (int i = 0; i < 3; i++) bandBlocks(ShoreRegion.build(snap, regionSettings()));
				ShoreRegion.build(snap, regionSettings(), null, FoamLevel.FAR.res(), FoamLevel.FAR.decorScale(), null);
			} catch (Throwable t) {
				FoamingShoresClient.LOGGER.warn("Warming up the shore fields failed", t);
			}
		});
	}

	// ---- Tick: keep the levels current and bake ------------------------------------------------

	/**
	 * Called after the settings screen saves. Settings that shape the shore fields or which blocks
	 * get foam rebuild the levels; the noise scales regenerate the noise; the rest show on the next
	 * bake.
	 */
	public void settingsChanged() {
		if (settings.differs(applied, "smoothing", "minBody", "openWater", "downhill", "runup", "approach", "jag", "lipWidth",
			"splashHeight", "fallFade", "farSmooth", "wrapAngle", "minIsland", "waveVary", "travelFade", "wrap", "wrapLag",
			"riverSpeed", "riverRock", "riverWake", "riverTrail", "backwater", "straight")) {
			wantNear = true; // rebuilt on the next ticks, keeping the current ones until then
			wantFar = true;
			lastFarStart = -1000;
		}
		if (settings.differs(applied, "jagScale", "laceCell")) noiseDirty = true;
		audio.settingsChanged();
		applied = settings.copy();
		lastBakeStep = Long.MIN_VALUE;
	}

	/**
	 * A block changed. Changes near the water's height rebuild the levels they are in, a few ticks
	 * later so a burst of changes (an explosion, a fill) costs one rebuild; the far level later still.
	 */
	public void blockChanged(ClientLevel lvl, BlockPos pos) {
		if (lvl != level) return;
		long now = lvl.getGameTime();
		if (inside(near.region, pos)) nearAt = Math.min(nearAt, now + 5);
		if (inside(far.region, pos) && farAt == Long.MAX_VALUE) farAt = now + 40;
	}

	private static boolean inside(ShoreRegion r, BlockPos pos) {
		if (r == null || Math.abs(pos.getY() - r.waterY) > 6) return false;
		return pos.getX() >= r.originX && pos.getZ() >= r.originZ && pos.getX() < r.originX + r.size && pos.getZ() < r.originZ + r.size;
	}

	/** A chunk a level (current or being built) had to treat as open water has arrived: rebuild with it. */
	public void chunkLoaded(ClientLevel lvl, ChunkPos pos) {
		if (lvl != level) return;
		long key = pos.pack();
		if (near.missing.contains(key) || near.buildingMissing.contains(key)) wantNear = true;
		if (far.missing.contains(key) || far.buildingMissing.contains(key)) wantFar = true;
		int reach = FoamLevel.NEAR.size();
		if (Math.abs(pos.getMiddleBlockX() - playerX) <= reach && Math.abs(pos.getMiddleBlockZ() - playerZ) <= reach) chunkArrivedAt = ticks;
	}

	public void tick(Minecraft mc) {
		ClientLevel lvl = mc.level;
		if (lvl != level) {
			level = lvl;
			RiverMemory old = rivers;
			worker.execute(old::save);
			rivers = new RiverMemory(lvl == null ? null : riverFile(mc, lvl));
			for (FoamLevel l : levels) clearLevel(l);
			building = null;
			pendingFar = null;
			farCapture = null;
			lastFarStart = -1000;
			splashes.clear();
			audio.clear();
		}
		audio.tick(mc, lvl == null ? 0 : lvl.getGameTime() / 20.0);
		if (lvl == null || mc.player == null) return;
		int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
		ticks++;
		playerX = px;
		playerZ = pz;

		int ptx = Math.floorDiv(px, FoamLevel.TILE), ptz = Math.floorDiv(pz, FoamLevel.TILE);
		if (building != null && building.isDone()) {
			Built built = building.join();
			building = null;
			long t0 = System.nanoTime();
			if (built == null) pendingFar = null;
			else if (built.level() == far && (near.region == null || !near.covers(ptx, ptz))) {
				// Nothing near the player to keep agreeing with (just joined, or teleported): the far
				// level draws everything at once, at the density each tile needs, and the near level
				// built inside it next takes over its own tiles without a change.
				install(lvl, built);
				assign(lvl);
				startNear(lvl, built.region());
			} else if (built.level() == far) {
				// The near level again, inside the new far one, so both go in agreeing.
				pendingFar = built;
				startNear(lvl, built.region());
			} else {
				if (pendingFar != null) install(lvl, pendingFar);
				pendingFar = null;
				install(lvl, built);
				assign(lvl);
			}
			installMs = (System.nanoTime() - t0) / 1e6;
		}
		if (lvl.getGameTime() >= nearAt) {
			nearAt = Long.MAX_VALUE;
			wantNear = true;
		}
		if (lvl.getGameTime() >= farAt) {
			farAt = Long.MAX_VALUE;
			wantFar = true;
		}
		boolean nearMoved = moved(near, ptx, ptz), farMoved = moved(far, ptx, ptz);
		// No far level round the player (joining, a teleport): it comes first, read in one go.
		boolean farLost = far.region == null || !far.covers(ptx, ptz);
		boolean nearStale = near.region == null || wantNear || nearMoved;
		boolean farStale = farLost || farMoved || (wantFar && ticks - lastFarStart >= FAR_COOLDOWN);
		if (!nearStale && !farStale) staleSince = -1;
		else if (staleSince < 0) staleSince = ticks;
		boolean settling = ticks - chunkArrivedAt < SETTLE_TICKS && ticks - staleSince < MAX_SETTLE;

		// Otherwise the far level's snapshot is read a little each tick, and the near level goes
		// first whenever it is stale (new land arriving close by must not wait for the far level).
		if (farCapture != null && !farLost) {
			long t0 = System.nanoTime();
			while (!farCapture.step(lvl, 8) && System.nanoTime() - t0 < FAR_CAPTURE_BUDGET) {
				// a few rows at a time until this tick's share is spent
			}
			farCaptureNanos += System.nanoTime() - t0;
			ShoreSnapshot snap = farCapture.snapshot();
			if (snap != null && building == null && !(nearStale && !settling)) {
				farCapture = null;
				farCaptureMs = farCaptureNanos / 1e6;
				startFarBuild(snap);
			}
		}
		if (building == null && pendingFar == null && !settling) {
			if (farLost) {
				startFar(ptx, ptz);
				long t0 = System.nanoTime();
				farCapture.step(lvl, Integer.MAX_VALUE);
				ShoreSnapshot snap = farCapture.snapshot();
				farCapture = null;
				farCaptureMs = (System.nanoTime() - t0) / 1e6;
				startFarBuild(snap);
			} else if (nearStale) startNear(lvl, far.region);
			else if (farCapture == null && farStale) startFar(ptx, ptz);
		}
		waterColor = BiomeColors.getAverageWaterColor(lvl, mc.player.blockPosition());

		if (noiseDirty && noiseTexture != null) {
			noiseDirty = false;
			uploadNoise(RenderSystem.getDevice());
		}
		if (near.region != null) {
			double now = lvl.getGameTime() / 20.0;
			List<WaveSchedule.Wave> recent = WaveSchedule.recent(now, settings, MAX_WAVES);
			splashes.tick(now, recent, settings);
		}

		// The animation moves in steps of `ticks` game ticks (vanilla water uses 2).
		int every = Math.max(1, settings.ticks);
		long step = lvl.getGameTime() / every;
		// Several ticks can run in one frame (catching up after a join or a hitch); bake once per frame.
		if ((near.region != null || far.region != null) && step != lastBakeStep && frames != lastBakeFrame) {
			lastBakeStep = step;
			lastBakeFrame = frames;
			bake(mc, step * every);
		}
	}

	/** Whether the player has left a level's window, so it needs laying again. */
	private static boolean moved(FoamLevel l, int ptx, int ptz) {
		if (l.region == null) return false;
		int[] o = l.place(ptx, ptz);
		return o[0] * FoamLevel.TILE != l.region.originX || o[1] * FoamLevel.TILE != l.region.originZ;
	}

	/** Snapshots the near level now and builds it, inside {@code parent} when there is one. */
	private void startNear(ClientLevel lvl, ShoreRegion parent) {
		wantNear = false;
		staleSince = -1;
		int[] o = near.place(Math.floorDiv(playerX, FoamLevel.TILE), Math.floorDiv(playerZ, FoamLevel.TILE));
		ShoreSnapshot.Capture c = new ShoreSnapshot.Capture(o[0] * FoamLevel.TILE, o[1] * FoamLevel.TILE, FoamLevel.NEAR.size(),
			parent != null ? parent.waterY : Integer.MIN_VALUE);
		c.step(lvl, Integer.MAX_VALUE);
		ShoreSnapshot snap = c.snapshot();
		near.buildingMissing = snap.missingChunks;
		ShoreRegion.Settings rs = regionSettings();
		RiverMemory memory = rivers;
		ShoreRegion.Parent up = parent == null ? null : new ShoreRegion.Parent(parent, FoamLevel.MARGIN, FoamLevel.BAND);
		building = CompletableFuture.supplyAsync(() -> {
			try {
				long t0 = System.nanoTime();
				// Inside a far level the rivers run its way; on its own the near level keeps its memory.
				ShoreRegion r = ShoreRegion.build(snap, rs, up == null ? memory : null, FoamLevel.NEAR.res(), FoamLevel.NEAR.decorScale(), up);
				if (up == null) memory.save();
				return new Built(near, r, bandBlocks(r), (System.nanoTime() - t0) / 1_000_000);
			} catch (Throwable t) {
				FoamingShoresClient.LOGGER.error("Building shore fields failed", t);
				return null;
			}
		}, worker);
	}

	/** Starts reading the far level's snapshot round the player's tile. */
	private void startFar(int ptx, int ptz) {
		wantFar = false;
		staleSince = -1;
		lastFarStart = ticks;
		int[] o = far.place(ptx, ptz);
		farCapture = new ShoreSnapshot.Capture(o[0] * FoamLevel.TILE, o[1] * FoamLevel.TILE, FoamLevel.FAR.size(), Integer.MIN_VALUE);
		farCaptureNanos = 0;
	}

	private void startFarBuild(ShoreSnapshot snap) {
		far.buildingMissing = snap.missingChunks;
		ShoreRegion.Settings rs = regionSettings();
		RiverMemory memory = rivers;
		building = CompletableFuture.supplyAsync(() -> {
			try {
				long t0 = System.nanoTime();
				ShoreRegion r = ShoreRegion.build(snap, rs, memory, FoamLevel.FAR.res(), FoamLevel.FAR.decorScale(), null);
				memory.save();
				return new Built(far, r, bandBlocks(r), (System.nanoTime() - t0) / 1_000_000);
			} catch (Throwable t) {
				FoamingShoresClient.LOGGER.error("Building far shore fields failed", t);
				return null;
			}
		}, worker);
	}

	private ShoreRegion.Settings regionSettings() {
		return new ShoreRegion.Settings(settings.minBody, settings.openWater, settings.downhill, settings.smoothing,
			settings.farSmooth, settings.wrapAngle, settings.minIsland, settings.runup * 16 * (1 + settings.waveVary) + settings.jag + 4,
			settings.riverSpeed, settings.riverRock, settings.riverWake, settings.riverTrail, settings.backwater, settings.approach * 16, settings.straight);
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
		int S = r.samples, res = r.res;
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
			int S = r.samples, res = r.res;
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

	private void clearLevel(FoamLevel l) {
		for (FoamLevel.Tile t : l.tiles.values()) freeSlots(t);
		l.tiles.clear();
		l.drawn.clear();
		l.region = null;
		l.missing = Set.of();
		l.buildingMissing = Set.of();
		l.faceQuads = List.of();
		l.faceCount = 0;
	}

	private static void freeSlots(FoamLevel.Tile t) {
		if (t.page != null) t.page.free(t.slot);
		if (t.nextPage != null) t.nextPage.free(t.nextSlot);
		t.page = t.nextPage = null;
		t.slot = t.nextSlot = -1;
	}

	/** Puts a built level in place: its fields on the GPU, its tiles and their quads. */
	private void install(ClientLevel lvl, Built built) {
		FoamLevel l = built.level();
		ShoreRegion r = built.region();
		l.region = r;
		l.missing = l.buildingMissing;
		l.buildMs = built.ms();
		l.upload(r);
		Map<Long, FoamLevel.Tile> old = new HashMap<>(l.tiles);
		l.tiles.clear();
		for (int[] b : built.bandBlocks()) {
			int tx = Math.floorDiv(r.originX + b[0], FoamLevel.TILE), tz = Math.floorDiv(r.originZ + b[1], FoamLevel.TILE);
			l.tiles.computeIfAbsent(FoamLevel.key(tx, tz), k -> new FoamLevel.Tile(tx, tz)).blocks.add(b);
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (Map.Entry<Long, FoamLevel.Tile> e : l.tiles.entrySet()) {
			FoamLevel.Tile t = e.getValue();
			// A tile drawn before keeps its last bake until it is baked again (the next bake).
			FoamLevel.Tile was = old.remove(e.getKey());
			if (was != null) {
				t.page = was.page;
				t.slot = was.slot;
				t.nextPage = was.nextPage;
				t.nextSlot = was.nextSlot;
			}
			for (int[] b : t.blocks) {
				Quad q = topQuad(lvl, r, b[0], b[1], t, pos);
				if (q != null) t.quads.add(q);
			}
		}
		for (FoamLevel.Tile t : old.values()) freeSlots(t);
		lastBakeStep = Long.MIN_VALUE;
	}

	/** The quad over a shore block's top, uv over its tile; null for blocks whose tops get no foam. */
	private static Quad topQuad(ClientLevel lvl, ShoreRegion r, int bx, int bz, FoamLevel.Tile tile, BlockPos.MutableBlockPos pos) {
		int bi = bz * r.size + bx;
		int wx = r.originX + bx, wz = r.originZ + bz;
		boolean water = isWaterColumn(r, bi);
		if (!water && r.level[bi] > 0) return null; // walls: their tops get no foam; their faces are in buildFaces
		if (r.low[bi]) return null; // a pool below the body's level: the waves never get there
		// Water the foam lies on: the body's own, or a stream running over this land.
		boolean surface = r.surfY[bi] != ShoreSnapshot.NO_SURFACE;
		// Under falling water (every corner a full block) the surface is inside the waterfall.
		if (surface && water && r.corners[bi * 4] >= 1 && r.corners[bi * 4 + 1] >= 1 && r.corners[bi * 4 + 2] >= 1 && r.corners[bi * 4 + 3] >= 1) return null;
		float top = water ? r.waterY + 8f / 9f : r.waterY + 1 + r.level[bi];
		float y = top + (water ? 0.01f : 0.002f);
		pos.set(wx, surface ? r.surfY[bi] : r.waterY + 1 + r.level[bi], wz);
		int light = LightCoordsUtil.getLightCoords(lvl, pos);
		if (surface) light = LightCoordsUtil.max(light, LightCoordsUtil.getLightCoords(lvl, pos.above())); // as vanilla lights its surface
		int lx = wx - tile.tx * FoamLevel.TILE, lz = wz - tile.tz * FoamLevel.TILE;
		float u0 = lx / 16f, v0 = lz / 16f, u1 = (lx + 1) / 16f, v1 = (lz + 1) / 16f;
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
		return new Quad(new float[] {xa, yNW, za, xa, ySW, zb, xb, ySE, zb, xb, yNE, za},
			new float[] {u0, v0, u0, v1, u1, v1, u1, v0}, 0, 1, 0, light);
	}

	/**
	 * Which level draws which tile: the near level its own area, the far level the rest of its own;
	 * then each level's faces for the tiles it draws (nearest first while the face table has room).
	 * Tiles no longer drawn give their slots back.
	 */
	private void assign(ClientLevel lvl) {
		for (FoamLevel l : levels) {
			l.drawn.clear();
			for (FoamLevel.Tile t : l.tiles.values()) {
				boolean mine = l == near ? near.covers(t.tx, t.tz) : far.covers(t.tx, t.tz) && !near.covers(t.tx, t.tz);
				if (mine) l.drawn.add(t);
				else freeSlots(t);
			}
			int ptx = Math.floorDiv(playerX, FoamLevel.TILE), ptz = Math.floorDiv(playerZ, FoamLevel.TILE);
			l.drawn.sort(Comparator.comparingInt(t -> Math.max(Math.abs(t.tx - ptx), Math.abs(t.tz - ptz))));
			if (l.region == null) continue;
			List<int[]> band = new ArrayList<>();
			for (FoamLevel.Tile t : l.drawn) band.addAll(t.blocks);
			buildFaces(lvl, l, band);
		}
	}

	/**
	 * Wall and drop faces. Both hang off a shore block's edge: a wall is a neighbour whose top is
	 * higher (the face is the wall's side, washed from its foot), a drop is a neighbour at least two
	 * blocks lower (the face is the shore block's own side, which water pours down). Each face's column
	 * u follows the shore block's texel along the shared edge; the face table tells the bake shader
	 * where that texel is, which way the face points, its kind and its height. The near level's walls
	 * also give the splashes their feet and the sound its sources.
	 */
	private void buildFaces(ClientLevel lvl, FoamLevel l, List<int[]> band) {
		ShoreRegion r = l.region;
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
				if (n >= MAX_FACES) break;
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
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(l.faceDataTexture, data, 0, 0, 0, 0, FACE_COLS * 2, MAX_FACES / FACE_COLS);
			l.faceCount = n;
			l.faceQuads = qs;
			if (l == near) {
				splashes.setRegion(r, feet);
				audio.setSounds(ShoreSounds.build(r, wallRows, dropRows, settings));
			}
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

	private void ensureShared(GpuDevice device) {
		if (atlas != null) return;
		atlas = new FoamAtlas(device);
		noiseTexture = device.createTexture("Foaming Shores noise", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, FoamNoise.SIZE, FoamNoise.SIZE, 1, 1);
		noiseView = device.createTextureView(noiseTexture);
		uploadNoise(device);
		DynamicTexture pixel = new DynamicTexture(() -> "Foaming Shores pixel", 1, 1, false);
		pixel.getPixels().setPixel(0, 0, 0xFFFFFFFF);
		pixel.upload();
		Minecraft.getInstance().getTextureManager().register(PIXEL_ID, pixel);
		try {
			timers = new GpuQueryPool[3];
			for (int i = 0; i < timers.length; i++) timers[i] = device.createTimestampQueryPool(2);
		} catch (RuntimeException e) {
			timersFailed = true;
		}
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

	// ---- Bake ---------------------------------------------------------------------------------------

	/**
	 * Texels per block a tile needs, from how many pixels a block at its nearest point covers on
	 * screen: the coarsest density whose texels are still no bigger than a pixel. A tile goes coarser
	 * only once it is 15% past the point, so one standing near the line does not flip each bake.
	 */
	private static int pageFor(FoamLevel.Tile t, int current, double ex, double ey, double ez, double waterY, double pixelsAtOne) {
		double x0 = t.tx * FoamLevel.TILE, z0 = t.tz * FoamLevel.TILE;
		double dx = Math.max(0, Math.max(x0 - ex, ex - (x0 + FoamLevel.TILE))), dz = Math.max(0, Math.max(z0 - ez, ez - (z0 + FoamLevel.TILE)));
		double dy = ey - waterY, d = Math.max(0.5, Math.sqrt(dx * dx + dy * dy + dz * dz));
		double ppb = pixelsAtOne / d;
		int want = 0;
		while (want < FoamAtlas.DENSITIES.length - 1 && FoamAtlas.DENSITIES[want + 1] >= ppb) want++;
		if (current >= 0 && want > current && FoamAtlas.DENSITIES[current + 1] < ppb * 1.15) want = current;
		return want;
	}

	/** Bakes every drawn tile and face of both levels at the given game tick. */
	private void bake(Minecraft mc, long tick) {
		CompiledRenderPipeline tilesPipeline = bakeFailed ? null : RenderSystem.getCompiledPipelineNullable(FoamPipelines.BAKE_TILES);
		CompiledRenderPipeline facesPipeline = bakeFailed ? null : RenderSystem.getCompiledPipelineNullable(FoamPipelines.BAKE);
		if (tilesPipeline == null || facesPipeline == null) {
			if (!bakeFailed) FoamingShoresClient.LOGGER.error("The foam bake shader did not compile; Foaming Shores will draw nothing");
			bakeFailed = true;
			return;
		}
		GpuDevice device = RenderSystem.getDevice();
		ensureShared(device);
		// Keep times small for float precision: seconds since a recent round epoch.
		long epochTicks = (tick / 24000) * 24000;
		double now = tick / 20.0, epoch = epochTicks / 20.0;
		List<WaveSchedule.Wave> waves = WaveSchedule.recent(now, settings, MAX_WAVES);
		WaterSprites water = waterSprites();

		// Each tile's density, and a slot for it where that changed.
		Vec3 eye = mc.player.getEyePosition();
		double fov = Math.toRadians(mc.options.fov().get());
		double pixelsAtOne = mc.getWindow().getHeight() / (2 * Math.tan(fov / 2));
		java.util.Arrays.fill(tilesAt, 0);
		for (FoamLevel l : levels) {
			if (l.region == null) continue;
			for (FoamLevel.Tile t : l.drawn) {
				int current = t.page != null ? t.page.index : -1;
				int want = pageFor(t, current, eye.x, eye.y, eye.z, l.region.waterY + 1, pixelsAtOne);
				if (t.nextPage == null && current != want) {
					for (int p = want; p < FoamAtlas.DENSITIES.length && t.nextPage == null; p++) {
						int slot = atlas.pages[p].alloc();
						if (slot >= 0) {
							t.nextPage = atlas.pages[p];
							t.nextSlot = slot;
						}
					}
				}
				FoamAtlas.Page target = t.nextPage != null ? t.nextPage : t.page;
				if (target != null) tilesAt[target.index]++;
			}
		}

		CommandEncoder encoder = device.createCommandEncoder();
		GpuQueryPool pool = timers != null && !timersFailed ? timers[timer] : null;
		if (pool != null) {
			readTimer(pool);
			encoder.writeTimestamp(pool, 0);
		}
		long pixels = 0;
		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		for (FoamLevel l : levels) {
			if (l.region == null) continue;
			writeUbo(l, l.ubo, waves, now, epoch, 0, water);
			if (l.faceCount > 0) writeUbo(l, l.faceUbo, waves, now, epoch, 1, water);
			// The tile tables, a page at a time.
			for (FoamAtlas.Page page : atlas.pages) {
				List<FoamLevel.Tile> onPage = new ArrayList<>();
				for (FoamLevel.Tile t : l.drawn) if ((t.nextPage != null ? t.nextPage : t.page) == page && onPage.size() < FoamLevel.TABLE) onPage.add(t);
				if (onPage.isEmpty()) continue;
				ByteBuffer table = MemoryUtil.memCalloc((FoamLevel.TABLE + 1) * 16);
				try {
					table.putFloat(FoamAtlas.PAGE).putFloat(page.slotPx).putFloat(l.region.size * 16).putFloat(0);
					for (FoamLevel.Tile t : onPage) {
						int slot = t.nextPage != null ? t.nextSlot : t.slot;
						table.putFloat(page.slotX(slot)).putFloat(page.slotY(slot))
							.putFloat((t.tx * FoamLevel.TILE - l.region.originX) * 16f).putFloat((t.tz * FoamLevel.TILE - l.region.originZ) * 16f);
					}
					table.position(0);
					encoder.writeToTexture(l.tableTextures[page.index], table, 0, 0, 0, 0, FoamLevel.TABLE + 1, 1);
				} finally {
					MemoryUtil.memFree(table);
				}
				try (RenderPass pass = encoder.createRenderPass(() -> "Foaming Shores bake " + l.spec.name() + " at " + page.density, page.view, Optional.empty())) {
					pass.setPipeline(tilesPipeline);
					bindLevel(pass, l, l.ubo, l.tableViews[page.index], water, nearest, false);
					pass.draw(6 * onPage.size(), 1, 0, 0);
				}
				pixels += (long) onPage.size() * page.slotPx * page.slotPx;
			}
			if (l.faceCount > 0) {
				writeTileIndex(encoder, l);
				try (RenderPass pass = encoder.createRenderPass(() -> "Foaming Shores face bake " + l.spec.name(), l.faceOutput.getTextureView(), Optional.empty())) {
					pass.setPipeline(facesPipeline);
					bindLevel(pass, l, l.faceUbo, l.indexView, water, nearest, true);
					pass.draw(3, 1, 0, 0);
				}
				l.faceUbo.rotate();
			}
			l.ubo.rotate();
		}
		if (pool != null) {
			encoder.writeTimestamp(pool, 1);
			timer = (timer + 1) % timers.length;
		}
		pixelsBaked = pixels;
		// Baked: tiles that moved draw from their new slots now.
		for (FoamLevel l : levels) {
			for (FoamLevel.Tile t : l.drawn) {
				if (t.nextPage == null) continue;
				if (t.page != null) t.page.free(t.slot);
				t.page = t.nextPage;
				t.slot = t.nextSlot;
				t.nextPage = null;
				t.nextSlot = -1;
			}
		}
	}

	private void readTimer(GpuQueryPool pool) {
		OptionalLong[] v = pool.getValues(0, 2);
		if (v[0].isPresent() && v[1].isPresent()) {
			double ms = (v[1].getAsLong() - v[0].getAsLong()) * RenderSystem.getDevice().getDeviceInfo().timestampPeriod() / 1e6;
			bakeGpuMs = bakeGpuMs * 0.9 + ms * 0.1;
		}
	}

	/**
	 * A level's textures for a bake pass; {@code table} goes to TileSampler (the tile table in the tile
	 * passes, the tile index in the face pass). Only the face pass reads the atlas pages: a tile pass
	 * draws into one, so there they are bound to something else.
	 */
	private void bindLevel(RenderPass pass, FoamLevel l, MappableRingBuffer ubo, GpuTextureView table, WaterSprites water, GpuSampler nearest, boolean pages) {
		pass.setUniform("FoamBake", ubo.currentBuffer());
		pass.setUniform("FieldSampler", l.fieldView, nearest);
		pass.setUniform("Field2Sampler", l.field2View, nearest);
		pass.setUniform("NoiseSampler", noiseView, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
		pass.setUniform("FaceSampler", l.faceDataView, nearest);
		pass.setUniform("DipSampler", l.dipView, nearest);
		pass.setUniform("RiverSampler", l.riverView, nearest);
		pass.setUniform("RiverDecorSampler", l.riverDecorView, nearest);
		pass.setUniform("FetchSampler", l.fetchView, nearest);
		pass.setUniform("AtlasSampler", water.atlas(), nearest);
		pass.setUniform("TileSampler", table, nearest);
		for (int i = 0; i < FoamAtlas.DENSITIES.length; i++)
			pass.setUniform("Page" + FoamAtlas.DENSITIES[i] + "Sampler", pages ? atlas.pages[i].view : noiseView, nearest);
	}

	/** Where each of a level's tiles is being baked this time (page 1..3, slot corner), 0 for none. */
	private static void writeTileIndex(CommandEncoder encoder, FoamLevel l) {
		int n = l.spec.tiles(), ox = l.region.originX / FoamLevel.TILE, oz = l.region.originZ / FoamLevel.TILE;
		ByteBuffer index = MemoryUtil.memCalloc(n * n * 16);
		try {
			for (FoamLevel.Tile t : l.drawn) {
				FoamAtlas.Page page = t.nextPage != null ? t.nextPage : t.page;
				int slot = t.nextPage != null ? t.nextSlot : t.slot, i = t.tx - ox, j = t.tz - oz;
				if (page == null || i < 0 || j < 0 || i >= n || j >= n) continue;
				index.position((j * n + i) * 16);
				index.putFloat(page.index + 1).putFloat(page.slotX(slot)).putFloat(page.slotY(slot)).putFloat(0);
			}
			index.position(0);
			encoder.writeToTexture(l.indexTexture, index, 0, 0, 0, 0, n, n);
		} finally {
			MemoryUtil.memFree(index);
		}
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

	/** One level's bake uniforms: mode 0 for its block tops (tile passes), 1 for its faces. */
	private void writeUbo(FoamLevel l, MappableRingBuffer buffer, List<WaveSchedule.Wave> waves, double now, double epoch, int mode, WaterSprites water) {
		FoamSettings s = settings;
		ShoreRegion region = l.region;
		// The far level's outermost drawn tiles dissolve toward its edge (its last ring is never drawn).
		float goneAt = l.spec.fades() ? FoamLevel.TILE * 16 : 0, fadeOver = l.spec.fades() ? FoamLevel.TILE * 16 : 0;
		try (GpuBufferSlice.MappedView view = buffer.currentBuffer().map(false, true)) {
			Std140Builder b = Std140Builder.intoBuffer(view.data())
				.putVec4(region.size * 16, region.res, region.samples, region.decorScale)
				.putVec4(Math.floorMod(region.originX * 16, 256), Math.floorMod(region.originZ * 16, 256), (float) (now - epoch), waves.size())
				.putVec4(s.lipWidth, s.jag, s.offshore, s.laceCover)
				.putVec4(s.laceWet, s.laceDry, s.laceSea, s.drift)
				.putVec4(s.dryTime, s.wetDark, s.sheetAlpha, s.shelter)
				.putVec4(s.approach * 16, s.runup * 16, s.speed * 16, s.stall)
				.putVec4(s.backwash, s.foamTint, s.soak, s.puddleSpread)
				.putVec4(((waterColor >> 16) & 255) / 255f, ((waterColor >> 8) & 255) / 255f, (waterColor & 255) / 255f, 1)
				.putVec4(s.spill, s.fallFade, s.splashHeight, s.pile)
				.putVec4(mode, l.faceCount, FACE_TEX_W, FACE_TEX_H)
				.putVec4(water.stillX, water.stillY, water.flowX, water.flowY)
				.putVec4(s.straight, s.peel, s.peelScale, s.waveVary)
				.putVec4(s.travelFade * 16, s.wrap, s.wrapLag * 16, goneAt);
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
			b.putVec4(s.bugRings, s.riverLenBySpeed, s.riverCountBySpeed, fadeOver);
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
	}

	// ---- Frame: draw the quads ---------------------------------------------------------------------

	public void submit(LevelRenderContext context) {
		frames++;
		if (atlas == null || bakeFailed) return;
		long t0 = System.nanoTime();
		Vec3 cam = context.levelState().cameraRenderState.pos;
		PoseStack poseStack = context.poseStack();
		poseStack.pushPose();
		poseStack.translate(-cam.x, -cam.y, -cam.z);
		for (FoamAtlas.Page page : atlas.pages) {
			// What each tile on this page draws from, as it is now (the bake may move tiles before the
			// geometry is written).
			List<int[]> slots = new ArrayList<>();
			List<FoamLevel.Tile> tiles = new ArrayList<>();
			for (FoamLevel l : levels) {
				for (FoamLevel.Tile t : l.drawn) {
					if (t.page != page || t.quads.isEmpty()) continue;
					tiles.add(t);
					slots.add(new int[] {page.slotX(t.slot), page.slotY(t.slot)});
				}
			}
			if (tiles.isEmpty()) continue;
			float scale = page.slotPx / (float) FoamAtlas.PAGE, inv = 1f / FoamAtlas.PAGE;
			context.submitNodeCollector().submitCustomGeometry(poseStack, FoamPipelines.surface(page.id), (pose, buffer) -> {
				for (int i = 0; i < tiles.size(); i++) {
					float ou = slots.get(i)[0] * inv, ov = slots.get(i)[1] * inv;
					for (Quad q : tiles.get(i).quads) emit(buffer, pose, q, cam, ou, ov, scale);
				}
			});
		}
		for (FoamLevel l : levels) {
			if (!l.faceQuads.isEmpty() && l.faceOutput != null) {
				List<Quad> qs = l.faceQuads;
				context.submitNodeCollector().submitCustomGeometry(poseStack, FoamPipelines.surface(l.faceId), (pose, buffer) -> {
					for (Quad q : qs) emit(buffer, pose, q, cam, 0, 0, 1);
				});
			}
		}
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
		submitMs = submitMs * 0.95 + (System.nanoTime() - t0) / 1e6 * 0.05;
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

	/** One quad, its uv mapped to (offset + uv * scale) of the texture it samples. */
	private static void emit(com.mojang.blaze3d.vertex.VertexConsumer buffer, PoseStack.Pose pose, Quad q, Vec3 cam, float ou, float ov, float scale) {
		float[] p = q.xyz(), uv = q.uv();
		for (int k = 0; k < 4; k++) {
			double dx = p[k * 3] - cam.x, dy = p[k * 3 + 1] - cam.y, dz = p[k * 3 + 2] - cam.z;
			double keep = 1 - EYE_PULL * Math.sqrt(dx * dx + dy * dy + dz * dz);
			buffer.addVertex(pose, (float) (cam.x + dx * keep), (float) (cam.y + dy * keep), (float) (cam.z + dz * keep))
				.setColor(255, 255, 255, 255)
				.setUv(ou + uv[k * 2] * scale, ov + uv[k * 2 + 1] * scale)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(q.light())
				.setNormal(pose, q.nx(), q.ny(), q.nz());
		}
	}

	// ---- F3 readout ----------------------------------------------------------------------------------

	/** Lines for the F3 screen: what each level is, what building and baking it cost. */
	public List<String> debugLines() {
		List<String> out = new ArrayList<>();
		StringBuilder lv = new StringBuilder("Foaming Shores:");
		for (FoamLevel l : levels) {
			if (l.region == null) { lv.append(' ').append(l.spec.name()).append(" -"); continue; }
			lv.append(String.format(" %s %d@%d %d ms (%d tiles, %d faces)", l.spec.name(), l.spec.size(), l.spec.res(), l.buildMs, l.drawn.size(), l.faceCount));
		}
		out.add(lv.toString());
		out.add(String.format("FS bake: tiles %d/%d/%d at 16/8/4, %.2f Mpx, GPU %s; draw %.2f ms/frame; install %.0f ms, far read %.0f ms",
			tilesAt[0], tilesAt[1], tilesAt[2], pixelsBaked / 1e6, timersFailed || timers == null ? "n/a" : String.format("%.2f ms", bakeGpuMs),
			submitMs, installMs, farCaptureMs));
		return out;
	}
}
