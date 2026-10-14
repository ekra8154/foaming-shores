package dev.ekra.foamingshores;

/**
 * Wave and foam settings. Defaults are the playground's (foaming-shores-playground); a config file
 * comes later. Distances are in blocks unless noted, times in seconds.
 */
public final class FoamSettings {
	public float runup = 1.6f;
	public float approach = 3f;
	public float speed = 1.8f;
	public float stall = 0.35f;
	public float backwash = 2.6f;
	public float period = 6.5f;
	public int setSize = 4;
	public float lull = 12f;
	public float shelter = 0.75f;

	public float jag = 3f;          // texels
	public float lipWidth = 2f;     // texels
	public float offshore = 0.25f;
	public int smoothing = 6;       // texels

	public float laceCover = 0.42f;
	public float laceWet = 3.5f;
	public float laceDry = 2f;
	public float laceSea = 1f;
	public float drift = 10f;       // texels per second

	public float wetDark = 0.28f;
	public float dryTime = 7f;
	public float sheetAlpha = 0.85f;
	public float foamTint = 0.08f;

	public int minBody = 30;        // blocks
	public float openWater = 2f;
	public float downhill = 1.6f;

	public static final FoamSettings INSTANCE = new FoamSettings();

	/** Run-up time for a wave of the given amplitude: a coast uphill that slows evenly to a stop. */
	public float runupTime(float amp) {
		return 2 * runup * amp / speed;
	}

	/** How long a wave of the given amplitude takes from setting off to the end of its backwash. */
	public float waveDuration(float amp) {
		return approach / speed + runupTime(amp) + stall + backwash;
	}
}
