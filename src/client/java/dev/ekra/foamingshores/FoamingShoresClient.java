package dev.ekra.foamingshores;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FoamingShoresClient implements ClientModInitializer {
	public static final String MOD_ID = "foamingshores";
	public static final Logger LOGGER = LoggerFactory.getLogger("Foaming Shores");

	@Override
	public void onInitializeClient() {
		LOGGER.info("Foaming Shores loaded");
	}
}
