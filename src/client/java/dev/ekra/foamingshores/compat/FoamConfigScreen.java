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
		new Group("swell", List.of(
			new Opt("straight", 0, 1, 0.05f, "none"),
			new Opt("farSmooth", 1, 24, 1, "blocks"),
			new Opt("peel", 0, 4, 0.1f, "seconds"),
			new Opt("peelScale", 4, 64, 1, "blocks"),
			new Opt("waveVary", 0, 1, 0.05f, "none"),
			new Opt("travelFade", 1, 24, 0.5f, "blocks"),
			new Opt("wrap", 0, 1, 0.05f, "none"),
			new Opt("wrapLag", 1, 12, 0.5f, "blocks"),
			new Opt("wrapAngle", 30, 170, 5, "degrees"))),
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
		new Group("rivers", List.of(
			new Opt("riverSpeed", 0.2f, 4, 0.1f, "blocks_per_second"),
			new Opt("riverStreaks", 0, 1, 0.05f, "none"),
			new Opt("riverLen", 2, 24, 1, "texels"),
			new Opt("riverShallow", 0, 1, 0.05f, "none"),
			new Opt("riverBank", 0, 1, 0.05f, "none"),
			new Opt("riverRock", 0, 1, 0.05f, "none"),
			new Opt("riverWake", 0, 8, 0.5f, "blocks"),
			new Opt("riverTrail", 0, 12, 0.5f, "blocks"))),
		new Group("still", List.of(
			new Opt("backwater", 0.5f, 8, 0.5f, "blocks"),
			new Opt("windDir", 0, 355, 5, "degrees"),
			new Opt("windShift", 0, 90, 5, "degrees"),
			new Opt("windStrength", 0, 1, 0.05f, "none"),
			new Opt("chop", 0, 1, 0.05f, "none"),
			new Opt("chopPeriod", 0.8f, 5, 0.1f, "seconds"),
			new Opt("fetchFull", 2, 32, 1, "blocks"),
			new Opt("chopRunup", 0, 8, 0.5f, "texels"),
			new Opt("rainRings", 0, 1, 0.05f, "none"),
			new Opt("glints", 0, 1, 0.05f, "none"),
			new Opt("paws", 0, 1, 0.05f, "none"),
			new Opt("rises", 0, 1, 0.05f, "none"),
			new Opt("edgeRings", 0, 1, 0.05f, "none"))),
		new Group("wet", List.of(
			new Opt("wetDark", 0, 0.6f, 0.01f, "none"),
			new Opt("dryTime", 0.5f, 20, 0.5f, "seconds"))),
		new Group("look", List.of(
			new Opt("ticks", 1, 4, 1, "ticks"),
			new Opt("sheetAlpha", 0, 1, 0.05f, "none"),
			new Opt("foamTint", 0, 0.5f, 0.01f, "none"))),
		new Group("bodies", List.of(
			new Opt("minIsland", 1, 60, 1, "blocks"),
			new Opt("minBody", 1, 200, 1, "blocks"),
			new Opt("openWater", 0.5f, 4, 0.25f, "blocks_from_land"))),
		new Group("sound", List.of(
			new Opt("volume", 0f, 1.5f, 0.05f, "none"),
			new Opt("hearing", 4f, 48f, 1f, "blocks"))),
		new Group("sound_levels", List.of(
			new Opt("sndCrash", 0f, 2f, 0.05f, "none"),
			new Opt("sndWash", 0f, 2f, 0.05f, "none"),
			new Opt("sndFizz", 0f, 2f, 0.05f, "none"),
			new Opt("sndSlap", 0f, 2f, 0.05f, "none"),
			new Opt("sndTrickle", 0f, 2f, 0.05f, "none"),
			new Opt("sndBed", 0f, 2f, 0.05f, "none"))),
		new Group("sound_crash", List.of(
			new Opt("crAttack", 0.01f, 0.8f, 0.01f, "seconds"),
			new Opt("crDecay", 0.2f, 5f, 0.05f, "seconds"),
			new Opt("crHigh", 500f, 12000f, 100f, "hertz"),
			new Opt("crLow", 100f, 4000f, 50f, "hertz"),
			new Opt("crNoise", 0f, 3f, 0.05f, "none"),
			new Opt("crCrumble", 0f, 1f, 0.05f, "none"),
			new Opt("crThump", 0f, 2f, 0.05f, "none"),
			new Opt("crBubVol", 0f, 2f, 0.01f, "none"),
			new Opt("crBubbles", 0f, 3f, 0.05f, "times"),
			new Opt("crBubMin", 0.2f, 5f, 0.1f, "millimetres"),
			new Opt("crBubMax", 0.3f, 20f, 0.1f, "millimetres"),
			new Opt("crBig", 0f, 1f, 0.05f, "none"),
			new Opt("crSpread", 0f, 5f, 0.1f, "none"))),
		new Group("sound_wash", List.of(
			new Opt("washLow", 100f, 4000f, 50f, "hertz"),
			new Opt("washSpan", 0f, 8000f, 100f, "hertz"),
			new Opt("washQ", 0.2f, 4f, 0.05f, "none"),
			new Opt("washRough", 0f, 1f, 0.05f, "none"),
			new Opt("washNoise", 0f, 3f, 0.05f, "none"),
			new Opt("washBubbles", 0f, 2f, 0.01f, "none"),
			new Opt("washRate", 0f, 100000f, 1000f, "per_second"),
			new Opt("washBubMin", 0.2f, 5f, 0.1f, "millimetres"),
			new Opt("washBubMax", 0.3f, 20f, 0.1f, "millimetres"),
			new Opt("washBig", 0f, 1f, 0.05f, "none"),
			new Opt("washSpread", 0f, 5f, 0.1f, "none"))),
		new Group("sound_fizz", List.of(
			new Opt("fizzLevel", 0f, 2f, 0.01f, "none"),
			new Opt("fizzRate", 0f, 200000f, 1000f, "per_second"),
			new Opt("fizzThr", 0f, 0.5f, 0.01f, "none"),
			new Opt("fizzHiss", 0f, 3f, 0.05f, "none"),
			new Opt("fizzBubMin", 0.2f, 5f, 0.1f, "millimetres"),
			new Opt("fizzBubMax", 0.3f, 20f, 0.1f, "millimetres"),
			new Opt("fizzBig", 0f, 1f, 0.05f, "none"),
			new Opt("fizzSpread", 0f, 5f, 0.1f, "none"))),
		new Group("sound_slap", List.of(
			new Opt("slapFreq", 200f, 6000f, 50f, "hertz"),
			new Opt("slapDecay", 0.03f, 1f, 0.01f, "seconds"),
			new Opt("slapKnock", 0f, 3f, 0.05f, "none"),
			new Opt("slapThump", 0f, 3f, 0.05f, "none"),
			new Opt("slapSprayVol", 0f, 2f, 0.01f, "none"),
			new Opt("slapSpray", 0f, 3f, 0.05f, "times"),
			new Opt("slapSprayMax", 0.3f, 20f, 0.1f, "millimetres"),
			new Opt("slapSprayBig", 0f, 1f, 0.05f, "none"),
			new Opt("slapSprayTime", 0.1f, 2f, 0.05f, "seconds"),
			new Opt("slapFar", 0f, 1f, 0.05f, "none"))),
		new Group("sound_pour", List.of(
			new Opt("trLevel", 0f, 2f, 0.01f, "none"),
			new Opt("trRate", 0f, 50000f, 500f, "per_second"),
			new Opt("trBubMin", 0.2f, 5f, 0.1f, "millimetres"),
			new Opt("trBubMax", 0.3f, 20f, 0.1f, "millimetres"),
			new Opt("trBig", 0f, 1f, 0.05f, "none"),
			new Opt("trSpread", 0f, 5f, 0.1f, "none"),
			new Opt("soakLevel", 0f, 2f, 0.01f, "none"),
			new Opt("soakRate", 0f, 20000f, 250f, "per_second"))),
		new Group("sound_bed", List.of(
			new Opt("bedLow", 60f, 1500f, 10f, "hertz"),
			new Opt("bedHigh", 0f, 2f, 0.05f, "none"),
			new Opt("bedSwell", 0f, 1f, 0.05f, "none"))),
		new Group("sound_bubbles", List.of(
			new Opt("bubAlpha", 0f, 3f, 0.05f, "none"),
			new Opt("bubDepth", 1f, 30f, 0.5f, "none"),
			new Opt("bubRise", 0f, 1f, 0.01f, "none"),
			new Opt("riseCut", 0.3f, 0.99f, 0.01f, "none"))),
		new Group("sound_air", List.of(
			new Opt("verbMix", 0f, 2f, 0.05f, "none"),
			new Opt("verbSize", 0.3f, 0.95f, 0.01f, "none"),
			new Opt("verbDamp", 0f, 0.95f, 0.05f, "none")))
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
			if (group.key().equals("sound")) {
				category.addEntry(entries.startBooleanToggle(Component.translatable("option.foamingshores.soundOn"), settings.soundOn)
					.setDefaultValue(defaults.soundOn)
					.setTooltip(Component.translatable("tooltip.foamingshores.soundOn"))
					.setSaveConsumer(v -> settings.soundOn = v)
					.build());
			}
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
