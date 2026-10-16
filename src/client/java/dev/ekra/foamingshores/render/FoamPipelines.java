package dev.ekra.foamingshores.render;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** The off-screen pass that bakes a region's foam into a texture: one full-screen triangle. */
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
		.withUniform("AtlasSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("RegionSampler", UniformType.COMBINED_IMAGE_SAMPLER)
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

	/** Loads the class so the pipeline is registered before shaders are first loaded. */
	public static void init() {
	}
}
