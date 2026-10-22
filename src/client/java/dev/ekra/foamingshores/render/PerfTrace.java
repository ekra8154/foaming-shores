package dev.ekra.foamingshores.render;

import dev.ekra.foamingshores.FoamingShoresClient;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * What the mod costs, added up and written to the log every few seconds (the perfLog setting):
 * frame rate and the worst frame, the mod's own time on the render thread per frame by stage, the
 * quads drawn, the GPU time of the bakes, the builds and the CPU their threads used, and the
 * garbage collector's pauses over the same stretch (they stop the render thread too).
 */
final class PerfTrace {
	private static final long REPORT_NANOS = 5_000_000_000L;

	private long start = System.nanoTime(), lastFrame;
	private int frames, ticks;
	private long worstFrame;
	long tickNanos, captureNanos, installNanos, bakeNanos, collectNanos, drawNanos, quads;
	private double gpuMs;
	private int gpuSamples;
	private int nearBuilds, farBuilds;
	private long nearBuildMs, farBuildMs;
	private long threadCpuAt = -1, gcMsAt = -1, gcCountAt = -1;

	void frame() {
		long now = System.nanoTime();
		if (lastFrame != 0) worstFrame = Math.max(worstFrame, now - lastFrame);
		lastFrame = now;
		frames++;
	}

	void tick() {
		ticks++;
	}

	void gpu(double ms) {
		gpuMs += ms;
		gpuSamples++;
	}

	void built(boolean far, long ms) {
		if (far) {
			farBuilds++;
			farBuildMs += ms;
		} else {
			nearBuilds++;
			nearBuildMs += ms;
		}
	}

	/** Writes the line once the stretch is up (when {@code log}), and starts the next. */
	void maybeReport(boolean log) {
		long now = System.nanoTime();
		if (now - start < REPORT_NANOS) return;
		long threadCpu = shoreThreadCpu(), gcMs = 0, gcCount = 0;
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			gcMs += Math.max(0, gc.getCollectionTime());
			gcCount += Math.max(0, gc.getCollectionCount());
		}
		if (log && threadCpuAt >= 0 && frames > 0) {
			double secs = (now - start) / 1e9, f = frames;
			FoamingShoresClient.LOGGER.info(String.format(
				"perf %.1fs: %.0f fps (worst frame %.0f ms) | ours per frame: tick %.2f ms (capture %.2f, install %.2f, bake %.2f), "
					+ "collect %.2f ms, vertices %.2f ms, %.0f quads | GPU bake %s/tick | builds: near %d (avg %s), far %d (avg %s), "
					+ "build threads %.2f cores | GC %d pauses, %d ms",
				secs, f / secs, worstFrame / 1e6, tickNanos / 1e6 / f, captureNanos / 1e6 / f, installNanos / 1e6 / f, bakeNanos / 1e6 / f,
				collectNanos / 1e6 / f, drawNanos / 1e6 / f, quads / f,
				gpuSamples > 0 ? String.format("%.2f ms", gpuMs / gpuSamples) : "n/a",
				nearBuilds, nearBuilds > 0 ? nearBuildMs / nearBuilds + " ms" : "-", farBuilds, farBuilds > 0 ? farBuildMs / farBuilds + " ms" : "-",
				Math.max(0, threadCpu - threadCpuAt) / 1e9 / secs, gcCount - gcCountAt, gcMs - gcMsAt));
		}
		threadCpuAt = threadCpu;
		gcMsAt = gcMs;
		gcCountAt = gcCount;
		start = now;
		frames = ticks = 0;
		worstFrame = 0;
		tickNanos = captureNanos = installNanos = bakeNanos = collectNanos = drawNanos = quads = 0;
		gpuMs = 0;
		gpuSamples = 0;
		nearBuilds = farBuilds = 0;
		nearBuildMs = farBuildMs = 0;
	}

	/** CPU time used so far by the mod's own threads (the field builds). */
	private static long shoreThreadCpu() {
		ThreadMXBean mx = ManagementFactory.getThreadMXBean();
		if (!mx.isThreadCpuTimeSupported()) return 0;
		long sum = 0;
		for (ThreadInfo info : mx.getThreadInfo(mx.getAllThreadIds())) {
			if (info == null || !info.getThreadName().startsWith("Foaming Shores")) continue;
			long t = mx.getThreadCpuTime(info.getThreadId());
			if (t > 0) sum += t;
		}
		return sum;
	}
}
