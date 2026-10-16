package dev.ekra.foamingshores.sound;

import dev.ekra.foamingshores.FoamSettings;
import dev.ekra.foamingshores.wave.WaveSchedule;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * The synth as an endless audio stream, read by the sound engine on its own thread. The engine asks
 * for a second at a time and queues four, which would put the sound four seconds behind the picture,
 * so each read returns only {@link #BUFFER} seconds; and because the wave model is a pure function
 * of time, each block is computed for the moment it will actually be heard (a few buffers from now),
 * keeping breaks and slaps on the beat with what is on screen.
 */
final class SurfStream implements AudioStream {
	static final int RATE = 44100;
	/** Seconds per buffer handed to the engine; it keeps four queued and refills every client tick. */
	private static final double BUFFER = 0.1;
	/** Seconds per control step: the wave model is sampled this often. */
	private static final double STEP = 0.05;
	/** How far ahead of now a fresh buffer is heard: three queued buffers, plus half a tick of refill. */
	private static final double LEAD = 3 * BUFFER + 0.025;
	private static final int MAX_WAVES = 12;

	private final ShoreAudio audio;
	private final AudioFormat format = new AudioFormat(RATE, 16, 2, true, false);
	private final SurfSynth synth = new SurfSynth(RATE);
	private final ShoreSounds.State state = new ShoreSounds.State();
	private final int stepFrames = (int) Math.round(STEP * RATE);
	private final float[] left = new float[stepFrames], right = new float[stepFrames];
	private int settingsVersion = -1;
	/** Game time (seconds) of the next sample this stream will produce; NaN until the first read. */
	private double audioTime = Double.NaN;
	/** The latest moment already accounted for: nudging the clock back never replays an event. */
	private double doneUntil = Double.NEGATIVE_INFINITY;
	private int stepFill = stepFrames;
	private static final double[][] SILENT = new double[SurfSynth.LAYERS][3];

	SurfStream(ShoreAudio audio) {
		this.audio = audio;
	}

	@Override
	public AudioFormat getFormat() {
		return format;
	}

	@Override
	public ByteBuffer read(int expectedSize) {
		int frames = (int) Math.round(BUFFER * RATE);
		ByteBuffer out = BufferUtils.createByteBuffer(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
		// Keep this stream's clock on time: start at the present (the first buffers play at once),
		// then drift toward "now plus the queue ahead of us", snapping after a jump (a teleport in
		// time, a long pause).
		double now = audio.gameSecondsNow();
		if (Double.isNaN(audioTime)) audioTime = now;
		double ahead = audioTime - (now + LEAD);
		if (Math.abs(ahead) > 1) audioTime = now + LEAD;
		else if (ahead > 0.02 || ahead < -0.4) audioTime -= ahead * 0.1;
		for (int i = 0; i < frames; i++) {
			if (stepFill >= stepFrames) nextStep();
			out.putShort(pcm(left[stepFill]));
			out.putShort(pcm(right[stepFill]));
			stepFill++;
		}
		out.flip();
		return out;
	}

	/** Renders the next control step: the scene as it will be at the end of the step, then the audio. */
	private void nextStep() {
		FoamSettings s = audio.settings();
		int version = audio.settingsVersion();
		if (version != settingsVersion) {
			settingsVersion = version;
			synth.setParams(s);
		}
		audioTime += STEP;
		double dt = Math.max(0, Math.min(0.2, audioTime - doneUntil));
		doneUntil = Math.max(doneUntil, audioTime);
		ShoreSounds sounds = audio.sounds();
		byte[] noise = audio.noise();
		if (sounds != null && noise != null) {
			List<WaveSchedule.Wave> waves = WaveSchedule.recent(audioTime, s, MAX_WAVES);
			ShoreSounds.Control c = sounds.compute(state, audioTime, dt, waves, s, noise, audio.ear());
			synth.control(c.layers(), c.events(), s.volume);
		} else {
			synth.control(SILENT, List.of(), s.volume);
		}
		synth.render(left, right, stepFrames);
		stepFill = 0;
	}

	private static short pcm(float v) {
		return (short) Math.round(Math.max(-1, Math.min(1, v)) * 32767);
	}

	@Override
	public void close() {
	}
}
