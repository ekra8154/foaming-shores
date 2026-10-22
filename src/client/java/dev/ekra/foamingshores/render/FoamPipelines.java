package dev.ekra.foamingshores.render;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import dev.ekra.foamingshores.FoamingShoresClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.util.function.Function;

/**
 * The off-screen passes that bake foam (block tops a tile at a time into the atlas pages, faces in
 * one full-screen triangle), and the pipeline that draws the baked foam onto the world.
 */
public final class FoamPipelines {
	private FoamPipelines() {
	}

	public static final BindGroupLayout BAKE_LAYOUT = BindGroupLayout.builder()
		.withUniform("FoamBake", UniformType.UNIFORM_BUFFER)
		.withUniform("FieldSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("Field2Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("NoiseSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("FaceSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("DipSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("RiverSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("RiverDecorSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("FetchSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("AtlasSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("TileSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("Page16Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("Page8Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("Page4Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("FadeSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.build();

	// Optional: if the shader fails to compile, vanilla logs a warning instead of failing the reload.
	public static final RenderPipeline BAKE = RenderPipelines.registerOptional(
		RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("foamingshores", "pipeline/foam_bake"))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath("foamingshores", "core/foam_bake"))
			.withBindGroupLayout(BAKE_LAYOUT)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withColorTargetState(ColorTargetState.DEFAULT)
			.build()
	);

	/** Block tops: the same bake, placed a tile at a time by foam_tile.vsh. */
	public static final RenderPipeline BAKE_TILES = RenderPipelines.registerOptional(
		RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("foamingshores", "pipeline/foam_bake_tiles"))
			.withVertexShader(Identifier.fromNamespaceAndPath("foamingshores", "core/foam_tile"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("foamingshores", "core/foam_bake"))
			.withBindGroupLayout(BAKE_LAYOUT)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withColorTargetState(ColorTargetState.DEFAULT)
			.build()
	);

	/**
	 * The foam lying on blocks: vanilla's translucent entity pipeline under our own name, so a
	 * shaderpack can be told to draw it as block-entity geometry (see {@link #init}).
	 */
	public static final RenderPipeline SURFACE = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("foamingshores", "pipeline/foam_surface"))
			.withShaderDefine("ALPHA_CUTOUT", 0.1F)
			.withShaderDefine("PER_FACE_LIGHTING")
			.withBindGroupLayout(BindGroupLayouts.SAMPLER1)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withCull(false)
			.build()
	);

	private static final Function<Identifier, RenderType> SURFACE_TYPE = Util.memoize(texture -> RenderType.create("foamingshores_surface",
		RenderSetup.builder(SURFACE)
			.setOitPipelines(RenderPipelines.OIT_ENTITY)
			.withTexture("Sampler0", texture)
			.useLightmap()
			.useOverlay()
			// No sortOnUpload: sorting every foam quad by distance each frame cost more than it gave
			// (foam quads hardly overlap one another).
			.createRenderSetup()));

	/** The render type for foam drawn from {@code texture} onto blocks. */
	public static RenderType surface(Identifier texture) {
		return SURFACE_TYPE.apply(texture);
	}

	/**
	 * Loads the class so the pipelines are registered before shaders are first loaded, and hands
	 * the surface pipeline to Iris's translucent block-entity program. Packs with TAA (Complementary)
	 * shift terrain and block entities by a fraction of a pixel each frame but leave entities still,
	 * so foam drawn as an entity drifts in and out of the sand it lies 0.002 blocks above, which at a
	 * grazing angle is a large depth difference: the foam flashes out past ~20 blocks. Block
	 * entities sit flush on terrain, so packs move them with it. Reflection keeps Iris optional.
	 */
	public static void init() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) return;
		try {
			Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Class<?> program = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
			Object iris = api.getMethod("getInstance").invoke(null);
			@SuppressWarnings({"unchecked", "rawtypes"})
			Object blockTranslucent = Enum.valueOf((Class) program, "BLOCK_TRANSLUCENT");
			api.getMethod("assignPipeline", RenderPipeline.class, program).invoke(iris, SURFACE, blockTranslucent);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			FoamingShoresClient.LOGGER.warn("Could not assign the foam pipeline to Iris's block program; foam may flicker with TAA shaders", e);
		}
	}
}
