package dev.ekra.foamingshores.sound;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.concurrent.CompletableFuture;

/**
 * The one long-running shore sound: a stereo stream played relative to the listener with no
 * attenuation (the synth places every source in the stereo field itself), under Ambient. Its sound
 * event points at Fabric's empty stream; Fabric's sound API then asks this instance for the stream.
 */
final class SurfSoundInstance extends AbstractTickableSoundInstance {
	static final Identifier ID = Identifier.fromNamespaceAndPath("foamingshores", "surf");
	private final ShoreAudio audio;

	SurfSoundInstance(ShoreAudio audio) {
		super(SoundEvent.createVariableRangeEvent(ID), SoundSource.AMBIENT, RandomSource.create());
		this.audio = audio;
		this.relative = true;
		this.attenuation = SoundInstance.Attenuation.NONE;
		this.looping = false;
		this.volume = 1;
		this.x = 0;
		this.y = 0;
		this.z = 0;
	}

	@Override
	public void tick() {
	}

	void finish() {
		stop();
	}

	/** Start even with Ambient at zero, so turning it up later is heard at once. */
	@Override
	public boolean canStartSilent() {
		return true;
	}

	@Override
	public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, Identifier id, boolean repeatInstantly) {
		return CompletableFuture.completedFuture(new SurfStream(audio));
	}
}
