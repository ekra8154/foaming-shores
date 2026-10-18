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

	// Swell
	public float straight = 0.8f;
	public float farSmooth = 10f;   // blocks
	public float peel = 1.5f;       // seconds
	public float peelScale = 18f;   // blocks
	public float waveVary = 0.4f;
	public float travelFade = 8f;   // blocks
	public float wrap = 0.8f;
	public float wrapLag = 5f;      // blocks
	public float wrapAngle = 110f;  // degrees

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
	public float puddleSpread = 4f; // texels
	public float spill = 0.5f;
	public float fallFade = 12f;    // texels

	// Rivers
	public float riverSpeed = 1.2f;  // blocks per second
	public float riverStreaks = 0.6f;
	public float riverSparkle = 0.5f;
	public float riverLen = 9f;      // texels
	public float riverShallow = 0.45f;
	public float riverBank = 0.5f;
	public float riverRock = 0.7f;
	public float riverWake = 3f;     // blocks
	public float riverTrail = 4f;    // blocks

	// Lakes and still water
	public float backwater = 2f;     // blocks
	public float windDir = 120f;     // degrees the wind blows toward, from east toward south
	public float windShift = 30f;    // degrees
	public float windStrength = 0.35f;
	public float chop = 0.8f;
	public float chopPeriod = 2.2f;  // seconds
	public float fetchFull = 8f;     // blocks
	public float chopRunup = 3f;     // texels
	public float rainRings = 0.7f;
	public float glints = 0.4f;
	public float paws = 0.5f;
	public float rises = 0.3f;
	public float edgeRings = 0.6f;

	// Wet sand
	public float wetDark = 0.28f;
	public float dryTime = 7f;

	// Look
	public int ticks = 2;           // animation step, in game ticks
	public float sheetAlpha = 1f;    // times vanilla water's own opacity
	public float foamTint = 0.08f;

	// Water bodies
	public int minIsland = 10;      // blocks
	public int minBody = 30;        // blocks
	public float openWater = 4f;

	// Sound: listen
	public boolean soundOn = true;
	/** Whether river and lake water is drawn in its own shades (lighter shallows, a darker middle), or left to vanilla wherever nothing is drawn on it. */
	public boolean recolorWater = true;
	public float volume = 0.7f;
	public float hearing = 16f;
	// Sound: layer levels
	public float sndCrash = 1f;
	public float sndWash = 1f;
	public float sndFizz = 1f;
	public float sndSlap = 1f;
	public float sndTrickle = 1f;
	public float sndBed = 1f;
	// Sound: breaking waves
	public float crAttack = 0.2f;
	public float crDecay = 2f;
	public float crHigh = 5000f;
	public float crLow = 900f;
	public float crNoise = 1f;
	public float crCrumble = 0.4f;
	public float crThump = 0.2f;
	public float crBubVol = 0.3f;
	public float crBubbles = 1f;
	public float crBubMin = 0.2f;
	public float crBubMax = 0.3f;
	public float crBig = 0.05f;
	public float crSpread = 2.5f;
	// Sound: run-up and backwash
	public float washLow = 800f;
	public float washSpan = 2600f;
	public float washQ = 0.6f;
	public float washRough = 0.3f;
	public float washNoise = 1f;
	public float washBubbles = 0.35f;
	public float washRate = 20000f;
	public float washBubMin = 0.3f;
	public float washBubMax = 0.3f;
	public float washBig = 0f;
	public float washSpread = 2.5f;
	// Sound: foam fizz
	public float fizzLevel = 0.7f;
	public float fizzRate = 60000f;
	public float fizzThr = 0.06f;
	public float fizzHiss = 1f;
	public float fizzBubMin = 0.5f;
	public float fizzBubMax = 0.3f;
	public float fizzBig = 0.05f;
	public float fizzSpread = 3f;
	// Sound: wall slaps
	public float slapFreq = 1100f;
	public float slapDecay = 0.2f;
	public float slapKnock = 1f;
	public float slapThump = 1f;
	public float slapSprayVol = 0.3f;
	public float slapSpray = 1f;
	public float slapSprayMax = 2.2f;
	public float slapSprayBig = 0f;
	public float slapSprayTime = 0.6f;
	public float slapFar = 0.6f;
	// Sound: pouring and puddles
	public float trLevel = 0.5f;
	public float trRate = 9000f;
	public float trBubMin = 0.4f;
	public float trBubMax = 1.5f;
	public float trBig = 0.05f;
	public float trSpread = 1.5f;
	public float soakLevel = 0.03f;
	public float soakRate = 4000f;
	// Sound: distant surf
	public float bedLow = 300f;
	public float bedHigh = 0.3f;
	public float bedSwell = 0.35f;
	// Sound: all bubbles
	public float bubAlpha = 1.5f;
	public float bubDepth = 10f;
	public float bubRise = 0.1f;
	public float riseCut = 0.9f;
	// Sound: air
	public float verbMix = 0.5f;
	public float verbSize = 0.8f;
	public float verbDamp = 0.4f;

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
