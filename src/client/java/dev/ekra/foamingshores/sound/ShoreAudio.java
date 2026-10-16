package dev.ekra.foamingshores.sound;

import com.mojang.blaze3d.audio.ListenerTransform;
import dev.ekra.foamingshores.FoamSettings;
import dev.ekra.foamingshores.FoamingShoresClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps the shore sound playing and hands the audio thread what it needs: the region's sound
 * sources, the noise, a copy of the settings, where the listener is and what game time it is.
 * Everything here is written on the client thread and read by {@link SurfStream} on the sound
 * engine's thread, so each piece is an immutable value behind a volatile field.
 */
public final class ShoreAudio {
	/** Game time (seconds) at the last client tick, and System.nanoTime() then. */
	private record Clock(double gameSeconds, long nanos) {
	}

	private volatile ShoreSounds sounds;
	private volatile byte[] noise;
	private volatile FoamSettings settings = FoamSettings.INSTANCE.copy();
	private volatile int settingsVersion;
	private volatile ShoreSounds.Ear ear = new ShoreSounds.Ear(0, 0, 0, 1, 0, 0);
	private volatile Clock clock = new Clock(0, System.nanoTime());

	private SurfSoundInstance playing;
	private int retryIn;

	public void setSounds(ShoreSounds s) {
		sounds = s;
	}

	public void setNoise(byte[] rgba) {
		noise = rgba;
	}

	public void settingsChanged() {
		settings = FoamSettings.INSTANCE.copy();
		settingsVersion++;
	}

	public void clear() {
		sounds = null;
	}

	ShoreSounds sounds() {
		return sounds;
	}

	byte[] noise() {
		return noise;
	}

	FoamSettings settings() {
		return settings;
	}

	int settingsVersion() {
		return settingsVersion;
	}

	ShoreSounds.Ear ear() {
		return ear;
	}

	/** The game time now, as best the audio thread can tell: the last tick plus the time since (up to a tick). */
	double gameSecondsNow() {
		Clock c = clock;
		return c.gameSeconds() + Math.min(0.05, (System.nanoTime() - c.nanos()) / 1e9);
	}

	/** Client tick: note the time and the listener, and keep one shore sound playing while there is a level. */
	public void tick(Minecraft mc, double gameSeconds) {
		clock = new Clock(gameSeconds, System.nanoTime());
		SoundManager sm = mc.getSoundManager();
		ListenerTransform lt = sm.getListenerTransform();
		Vec3 pos = lt.position(), right = lt.right();
		double len = right.length();
		ear = len > 1e-6
			? new ShoreSounds.Ear(pos.x, pos.y, pos.z, right.x / len, right.y / len, right.z / len)
			: new ShoreSounds.Ear(pos.x, pos.y, pos.z, 1, 0, 0);

		boolean want = settings.soundOn && mc.level != null && sounds != null;
		if (!want) {
			if (playing != null) {
				playing.finish();
				playing = null;
			}
			return;
		}
		// The engine drops a stream that ran dry (a long hitch) or was stopped (a reload): start another.
		if (playing != null && !playing.isStopped() && sm.isActive(playing)) return;
		if (retryIn > 0) {
			retryIn--;
			return;
		}
		if (playing != null) playing.finish();
		playing = new SurfSoundInstance(this);
		sm.play(playing);
		retryIn = 20;
		FoamingShoresClient.LOGGER.debug("Started the shore sound");
	}
}
