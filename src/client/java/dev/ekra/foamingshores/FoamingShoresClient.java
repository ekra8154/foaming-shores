package dev.ekra.foamingshores;

import dev.ekra.foamingshores.render.FoamPipelines;
import dev.ekra.foamingshores.render.FoamRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FoamingShoresClient implements ClientModInitializer {
	public static final String MOD_ID = "foamingshores";
	public static final Logger LOGGER = LoggerFactory.getLogger("Foaming Shores");
	private static FoamRenderer renderer;

	public static FoamRenderer renderer() {
		return renderer;
	}

	@Override
	public void onInitializeClient() {
		FoamPipelines.init();
		renderer = new FoamRenderer();
		ClientTickEvents.END_CLIENT_TICK.register(renderer::tick);
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> renderer.chunkLoaded(level, chunk.getPos()));
		LevelRenderEvents.COLLECT_SUBMITS.register(renderer::submit);
		// What the levels of detail cost, for the F3 screen (turned on in its debug options).
		DebugScreenEntries.register(Identifier.fromNamespaceAndPath(MOD_ID, "stats"),
			(displayer, level, clientChunk, serverChunk) -> renderer.debugLines().forEach(displayer::addLine));
		LOGGER.info("Foaming Shores loaded");
	}
}
