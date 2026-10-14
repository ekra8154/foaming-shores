package dev.ekra.foamingshores.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

public final class FoamingShoresModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		// Cloth builds the screen; without it Mod Menu simply shows no config button.
		if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
			return null;
		}
		return FoamConfigScreen::create;
	}
}
