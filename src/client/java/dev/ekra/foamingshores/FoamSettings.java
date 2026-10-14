package dev.ekra.foamingshores;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wave and foam settings, saved at {@code config/foamingshores.json}. The keys are the browser
 * playground's (foaming-shores-playground), so its "Copy settings" JSON can be pasted straight
 * into the file: unknown keys are ignored and values are coerced to each field's type. Distances
 * are in blocks unless noted, times in seconds.
 */
public final class FoamSettings {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// Waves
	public float runup = 1.6f;
	public float approach = 3f;
	public float speed = 1.8f;
	public float stall = 0.35f;
	public float backwash = 2.6f;
	public float period = 6.5f;
	public int setSize = 4;
	public float lull = 12f;
	public float shelter = 0.75f;

	// Wave edge
	public float jag = 3f;          // texels
	public int jagScale = 9;        // texels
	public float lipWidth = 2f;     // texels
	public float offshore = 0.25f;
	public int smoothing = 6;       // texels

	// Foam lace
	public float laceCell = 6f;     // texels
	public float laceCover = 0.42f;
	public float laceWet = 3.5f;
	public float laceDry = 2f;
	public float laceSea = 1f;
	public float drift = 10f;       // texels per second

	// Walls and drops
	public float splash = 1f;
	public float splashHeight = 20f; // texels
	public float pile = 0.7f;
	public float downhill = 1.6f;
	public float soak = 8f;
	public float spill = 0.5f;
	public float fallFade = 12f;    // texels

	// Wet sand
	public float wetDark = 0.28f;
	public float dryTime = 7f;

	// Look
	public int ticks = 2;           // animation step, in game ticks
	public float sheetAlpha = 1f;    // times vanilla water's own opacity
	public float foamTint = 0.08f;

	// Water bodies
	public int minBody = 30;        // blocks
	public float openWater = 2f;

	public static final FoamSettings INSTANCE = load();

	/** Run-up time for a wave of the given amplitude: a coast uphill that slows evenly to a stop. */
	public float runupTime(float amp) {
		return 2 * runup * amp / speed;
	}

	/** How long a wave of the given amplitude takes from setting off to the end of its backwash. */
	public float waveDuration(float amp) {
		return approach / speed + runupTime(amp) + stall + backwash;
	}

	public FoamSettings copy() {
		FoamSettings c = new FoamSettings();
		for (Field f : fields()) {
			try {
				f.set(c, f.get(this));
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}
		return c;
	}

	/** Whether any of the named settings differ between this and {@code other}. */
	public boolean differs(FoamSettings other, String... keys) {
		for (String key : keys) {
			try {
				Field f = FoamSettings.class.getField(key);
				if (!f.get(this).equals(f.get(other))) return true;
			} catch (ReflectiveOperationException e) {
				throw new IllegalArgumentException(key, e);
			}
		}
		return false;
	}

	private static Field[] fields() {
		return java.util.Arrays.stream(FoamSettings.class.getFields())
			.filter(f -> !Modifier.isStatic(f.getModifiers()))
			.toArray(Field[]::new);
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("foamingshores.json");
	}

	private static FoamSettings load() {
		FoamSettings s = new FoamSettings();
		Path file = path();
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				JsonElement root = JsonParser.parseReader(reader);
				if (root.isJsonObject()) s.read(root.getAsJsonObject());
			} catch (IOException | RuntimeException e) {
				FoamingShoresClient.LOGGER.warn("Could not read {}, using defaults", file, e);
			}
		}
		s.save();
		return s;
	}

	private void read(JsonObject json) {
		for (Field f : fields()) {
			JsonElement v = json.get(f.getName());
			if (v == null || !v.isJsonPrimitive()) continue;
			try {
				if (f.getType() == float.class) f.setFloat(this, v.getAsFloat());
				else if (f.getType() == int.class) f.setInt(this, Math.round(v.getAsFloat()));
				else if (f.getType() == boolean.class) f.setBoolean(this, v.getAsBoolean());
			} catch (NumberFormatException | IllegalAccessException e) {
				FoamingShoresClient.LOGGER.warn("Ignoring setting {}: {}", f.getName(), v);
			}
		}
	}

	public void save() {
		Map<String, Object> out = new LinkedHashMap<>();
		for (Field f : fields()) {
			try {
				out.put(f.getName(), f.get(this));
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}
		Path file = path();
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(out, writer);
			}
		} catch (IOException e) {
			FoamingShoresClient.LOGGER.warn("Could not save {}", file, e);
		}
	}
}
