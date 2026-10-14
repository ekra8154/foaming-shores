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
import dev.ekra.foamingshores.wave.WaveSchedule;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Prototype renderer for one region around the player:
 * <ol>
 * <li>every so often, snapshot the surface and build the shore fields on a worker thread;</li>
 * <li>every 2 ticks, bake the region's foam into a texture on the GPU (stateless, so a bake needs
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
	private static final int UBO_SIZE = new Std140SizeCalculator()
		.putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
		.get() + 16 * 2 * MAX_WAVES;

	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Foaming Shores fields");
		t.setDaemon(true);
		return t;
	});
	private final FoamSettings settings = FoamSettings.INSTANCE;

	private ClientLevel level;
	private ShoreRegion region;
	private List<Quad> quads = List.of();
	private CompletableFuture<Built> building;

	private GpuTexture fieldTexture, noiseTexture;
	private GpuTextureView fieldView, noiseView;
	private RegionTexture output;
	private MappableRingBuffer ubo;
	private long lastBakeStep = Long.MIN_VALUE;
	private boolean bakeFailed;
	private int waterColor = 0x3F76E4;

	/** A quad over one block's top, with its light and its place in the region texture. */
	private record Quad(float x, float y, float z, float u0, float v0, float u1, float v1, int light) {
	}

	private record Built(ShoreRegion region, List<int[]> bandBlocks) {
	}

	// ---- Tick: keep the region current and bake ---------------------------------------------

	public void tick(Minecraft mc) {
		ClientLevel lvl = mc.level;
		if (lvl != level) {
			level = lvl;
			region = null;
			quads = List.of();
			building = null;
		}
		if (lvl == null || mc.player == null) return;

		if (building != null && building.isDone()) {
			Built built = building.join();
			building = null;
			if (built != null) install(lvl, built);
		}
		int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
		boolean stale = region == null
			|| Math.abs(px - (region.originX + REGION / 2)) > REBUILD_DISTANCE
			|| Math.abs(pz - (region.originZ + REGION / 2)) > REBUILD_DISTANCE;
		if (stale && building == null) {
			ShoreSnapshot snap = ShoreSnapshot.capture(lvl, px - REGION / 2, pz - REGION / 2, REGION);
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

		long step = lvl.getGameTime() / 2; // the animation moves in 2-tick steps, like vanilla water
		if (region != null && step != lastBakeStep) {
			lastBakeStep = step;
			bake(step * 2);
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
		} finally {
			MemoryUtil.memFree(buf);
		}

		List<Quad> qs = new ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int[] b : built.bandBlocks()) {
			int bx = b[0], bz = b[1], bi = bz * r.size + bx;
			int wx = r.originX + bx, wz = r.originZ + bz;
			boolean water = isWaterColumn(r, bi);
			float y = water ? r.waterY + 8f / 9f + 0.01f : r.waterY + 1 + r.level[bi] + 0.002f;
			if (!water && r.level[bi] > 0) continue; // walls: tops get no foam (faces come later)
			pos.set(wx, water ? r.waterY : r.waterY + 1 + r.level[bi], wz);
			int light = LightCoordsUtil.getLightCoords(lvl, pos);
			qs.add(new Quad(wx, y, wz,
				bx * 16f / TEXELS, bz * 16f / TEXELS, (bx + 1) * 16f / TEXELS, (bz + 1) * 16f / TEXELS, light));
		}
		region = r;
		quads = qs;
		lastBakeStep = Long.MIN_VALUE;
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
		noiseTexture = device.createTexture("Foaming Shores noise", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, FoamNoise.SIZE, FoamNoise.SIZE, 1, 1);
		noiseView = device.createTextureView(noiseTexture);
		byte[] noise = FoamNoise.build(9, 6f);
		ByteBuffer nb = MemoryUtil.memAlloc(noise.length);
		try {
			nb.put(noise).flip();
			device.createCommandEncoder().writeToTexture(noiseTexture, nb, 0, 0, 0, 0, FoamNoise.SIZE, FoamNoise.SIZE);
		} finally {
			MemoryUtil.memFree(nb);
		}
		GpuTexture out = device.createTexture("Foaming Shores foam", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, TEXELS, TEXELS, 1, 1);
		output = new RegionTexture(out, device.createTextureView(out));
		Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, output);
		ubo = new MappableRingBuffer(() -> "Foaming Shores bake UBO", 130, UBO_SIZE);
	}

	/** Draws the region's foam at the given game tick into the region texture. */
	private void bake(long tick) {
		// Keep times small for float precision: seconds since a recent round epoch.
		long epochTicks = (tick / 24000) * 24000;
		double now = tick / 20.0, epoch = epochTicks / 20.0;
		List<WaveSchedule.Wave> waves = WaveSchedule.recent(now, settings, MAX_WAVES);
		FoamSettings s = settings;
		try (GpuBufferSlice.MappedView view = ubo.currentBuffer().map(false, true)) {
			Std140Builder b = Std140Builder.intoBuffer(view.data())
				.putVec4(TEXELS, ShoreRegion.RES, REGION * ShoreRegion.RES, 0)
				.putVec4(Math.floorMod(region.originX * 16, 256), Math.floorMod(region.originZ * 16, 256), (float) (now - epoch), waves.size())
				.putVec4(s.lipWidth, s.jag, s.offshore, s.laceCover)
				.putVec4(s.laceWet, s.laceDry, s.laceSea, s.drift)
				.putVec4(s.dryTime, s.wetDark, s.sheetAlpha, s.shelter)
				.putVec4(s.approach * 16, s.runup * 16, s.speed * 16, s.stall)
				.putVec4(s.backwash, s.foamTint, 0, 0)
				.putVec4(((waterColor >> 16) & 255) / 255f, ((waterColor >> 8) & 255) / 255f, (waterColor & 255) / 255f, 1);
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
		CompiledRenderPipeline pipeline = bakeFailed ? null : RenderSystem.getCompiledPipelineNullable(FoamPipelines.BAKE);
		if (pipeline == null) {
			if (!bakeFailed) FoamingShoresClient.LOGGER.error("The foam bake shader did not compile; Foaming Shores will draw nothing");
			bakeFailed = true;
			return;
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		try (RenderPass pass = encoder.createRenderPass(() -> "Foaming Shores bake", output.getTextureView(), Optional.empty())) {
			pass.setPipeline(pipeline);
			pass.setUniform("FoamBake", ubo.currentBuffer());
			pass.setUniform("FieldSampler", fieldView, nearest);
			pass.setUniform("NoiseSampler", noiseView, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
		ubo.rotate();
	}

	// ---- Frame: draw the quads ---------------------------------------------------------------------

	public void submit(LevelRenderContext context) {
		if (region == null || quads.isEmpty() || output == null || bakeFailed) return;
		Vec3 cam = context.levelState().cameraRenderState.pos;
		PoseStack poseStack = context.poseStack();
		poseStack.pushPose();
		poseStack.translate(-cam.x, -cam.y, -cam.z);
		RenderType type = RenderTypes.entityTranslucent(TEXTURE_ID, false);
		List<Quad> qs = quads;
		context.submitNodeCollector().submitCustomGeometry(poseStack, type, (pose, buffer) -> {
			for (Quad q : qs) {
				vertex(buffer, pose, q.x(), q.y(), q.z(), q.u0(), q.v0(), q.light());
				vertex(buffer, pose, q.x(), q.y(), q.z() + 1, q.u0(), q.v1(), q.light());
				vertex(buffer, pose, q.x() + 1, q.y(), q.z() + 1, q.u1(), q.v1(), q.light());
				vertex(buffer, pose, q.x() + 1, q.y(), q.z(), q.u1(), q.v0(), q.light());
			}
		});
		poseStack.popPose();
	}

	private static void vertex(com.mojang.blaze3d.vertex.VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, float u, float v, int light) {
		buffer.addVertex(pose, x, y, z)
			.setColor(255, 255, 255, 255)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(light)
			.setNormal(pose, 0, 1, 0);
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
