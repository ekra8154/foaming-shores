package dev.ekra.foamingshores.shore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which way each river runs, remembered per world (and dimension) so it never changes between
 * visits or sessions: one direction per chunk a river crosses, taken at the river's point nearest
 * the chunk's middle, and whether it was worked out from the sea (sure) or only picked because the
 * sea was out of sight (a guess, which the sea may correct once). A text file of
 * "chunkX chunkZ dirX dirZ sure" lines.
 */
public final class RiverMemory {
	private final Path file;
	private final Map<Long, float[]> dirs = new HashMap<>();
	private boolean dirty;

	/** Remembers in {@code file} (created when first needed); null remembers nothing. */
	public RiverMemory(Path file) {
		this.file = file;
		if (file == null || !Files.isRegularFile(file)) return;
		try {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String[] f = line.trim().split("\\s+");
				if (f.length < 4) continue;
				try {
					float sure = f.length > 4 ? Float.parseFloat(f[4]) : 0;
					dirs.put(key(Integer.parseInt(f[0]), Integer.parseInt(f[1])), new float[] {Float.parseFloat(f[2]), Float.parseFloat(f[3]), sure});
				} catch (NumberFormatException ignored) {
					// a damaged line: forget it
				}
			}
		} catch (IOException ignored) {
			// unreadable: start afresh
		}
	}

	static long key(int cx, int cz) {
		return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
	}

	/** (dirX, dirZ, sure: 1 or 0), or null. */
	synchronized float[] get(int cx, int cz) {
		return dirs.get(key(cx, cz));
	}

	synchronized void put(int cx, int cz, float dx, float dz, boolean sure) {
		float[] old = dirs.get(key(cx, cz));
		if (old != null && old[0] * dx + old[1] * dz > 0.98f && (old[2] > 0.5f) == sure) return; // the same, near enough
		dirs.put(key(cx, cz), new float[] {dx, dz, sure ? 1 : 0});
		dirty = true;
	}

	/** Writes the file if anything changed (atomically, so a crash never leaves half a file). */
	public synchronized void save() {
		if (!dirty || file == null) return;
		List<String> lines = new ArrayList<>(dirs.size());
		for (Map.Entry<Long, float[]> e : dirs.entrySet()) {
			int cx = (int) (e.getKey() >> 32), cz = (int) (e.getKey() & 0xFFFFFFFFL);
			lines.add(cx + " " + cz + " " + e.getValue()[0] + " " + e.getValue()[1] + " " + (int) e.getValue()[2]);
		}
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.write(tmp, lines, StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException ignored) {
			// try again after the next build
		}
	}
}
