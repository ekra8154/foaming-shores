package dev.ekra.foamingshores.compat;

import dev.ekra.foamingshores.FoamSettings;
import dev.ekra.foamingshores.FoamingShoresClient;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;

/**
 * The settings screen: the playground's groups, ranges and steps. Cloth has no float slider, so
 * each setting is an int slider over value / step that shows the real value and unit.
 */
public final class FoamConfigScreen {
	private FoamConfigScreen() {
	}

	private record Opt(String key, float min, float max, float step, String unit) {
	}

	private record Group(String key, List<Opt> opts) {
	}

	private static final List<Group> GROUPS = List.of(
		new Group("waves", List.of(
			new Opt("runup", 0.25f, 4, 0.05f, "blocks"),
			new Opt("approach", 0.5f, 6, 0.25f, "blocks"),
			new Opt("speed", 0.5f, 5, 0.1f, "blocks_per_second"),
			new Opt("stall", 0, 2, 0.05f, "seconds"),
			new Opt("backwash", 0.5f, 6, 0.1f, "seconds"),
			new Opt("period", 2, 16, 0.5f, "seconds"),
			new Opt("setSize", 1, 8, 1, "none"),
			new Opt("lull", 0, 40, 1, "seconds"),
			new Opt("shelter", 0, 1, 0.05f, "none"))),
		new Group("edge", List.of(
			new Opt("jag", 0, 10, 0.5f, "texels"),
			new Opt("jagScale", 3, 40, 1, "texels"),
			new Opt("lipWidth", 1, 5, 0.5f, "texels"),
			new Opt("offshore", 0, 1, 0.05f, "none"),
			new Opt("smoothing", 0, 24, 1, "texels"))),
		new Group("lace", List.of(
			new Opt("laceCell", 3, 14, 0.5f, "texels"),
			new Opt("laceCover", 0, 1, 0.02f, "none"),
			new Opt("laceWet", 0.5f, 10, 0.1f, "half_life"),
			new Opt("laceDry", 0.3f, 8, 0.1f, "half_life"),
			new Opt("laceSea", 0.2f, 5, 0.1f, "half_life"),
			new Opt("drift", 0, 30, 1, "texels_per_second"))),
		new Group("terrain", List.of(
			new Opt("pile", 0, 1, 0.05f, "none"),
			new Opt("splash", 0, 3, 0.05f, "none"),
			new Opt("splashHeight", 4, 48, 1, "texels"),
			new Opt("downhill", 1, 3, 0.1f, "times"),
			new Opt("soak", 1, 30, 0.5f, "seconds"),
			new Opt("puddleSpread", 0, 16, 1, "texels"),
			new Opt("spill", 0, 1, 0.05f, "none"),
			new Opt("fallFade", 2, 48, 1, "texels"))),
		new Group("wet", List.of(
			new Opt("wetDark", 0, 0.6f, 0.01f, "none"),
			new Opt("dryTime", 0.5f, 20, 0.5f, "seconds"))),
		new Group("look", List.of(
			new Opt("ticks", 1, 4, 1, "ticks"),
			new Opt("sheetAlpha", 0, 1, 0.05f, "none"),
			new Opt("foamTint", 0, 0.5f, 0.01f, "none"))),
		new Group("bodies", List.of(
			new Opt("minBody", 1, 200, 1, "blocks"),
			new Opt("openWater", 0.5f, 4, 0.25f, "blocks_from_land")))
	);

	public static Screen create(Screen parent) {
		FoamSettings settings = FoamSettings.INSTANCE, defaults = new FoamSettings();
		ConfigBuilder builder = ConfigBuilder.create()
			.setParentScreen(parent)
			.setTitle(Component.translatable("title.foamingshores.config"))
			.setSavingRunnable(() -> {
				settings.save();
				FoamingShoresClient.renderer().settingsChanged();
			});
		ConfigEntryBuilder entries = builder.entryBuilder();
		for (Group group : GROUPS) {
			ConfigCategory category = builder.getOrCreateCategory(Component.translatable("category.foamingshores." + group.key()));
			for (Opt opt : group.opts()) {
				Field field = field(opt.key());
				int lo = Math.round(opt.min() / opt.step()), hi = Math.round(opt.max() / opt.step());
				int current = Math.max(lo, Math.min(hi, Math.round(get(field, settings) / opt.step())));
				int fallback = Math.round(get(field, defaults) / opt.step());
				int decimals = opt.step() >= 1 ? 0 : opt.step() >= 0.1f && Math.abs(opt.step() * 10 - Math.round(opt.step() * 10)) < 1e-4 ? 1 : 2;
				category.addEntry(entries.startIntSlider(Component.translatable("option.foamingshores." + opt.key()), current, lo, hi)
					.setDefaultValue(fallback)
					.setTextGetter(i -> Component.translatable("unit.foamingshores." + opt.unit(),
						String.format(Locale.ROOT, "%." + decimals + "f", i * opt.step())))
					.setTooltip(Component.translatable("tooltip.foamingshores." + opt.key()))
					.setSaveConsumer(i -> set(field, settings, i * opt.step()))
					.build());
			}
		}
		return builder.build();
	}

	private static Field field(String key) {
		try {
			return FoamSettings.class.getField(key);
		} catch (NoSuchFieldException e) {
			throw new IllegalStateException("No setting " + key, e);
		}
	}

	private static float get(Field f, FoamSettings s) {
		try {
			return f.getType() == int.class ? f.getInt(s) : f.getFloat(s);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void set(Field f, FoamSettings s, float value) {
		try {
			if (f.getType() == int.class) f.setInt(s, Math.round(value));
			else f.setFloat(s, value);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}
}
