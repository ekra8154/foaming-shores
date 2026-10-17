package dev.ekra.foamingshores.shore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
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
 * sea was out of sight (a guess, which the sea may correct once). Kept as JSON:
 * {@code {"version": 1, "chunks": [{"x", "z", "dirX", "dirZ", "sure"}, ...]}}.
 */
public final class RiverMemory {
	private static final int VERSION = 1;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** The file's shape. */
	private static final class Stored {
		int version = VERSION;
		List<Chunk> chunks = new ArrayList<>();
	}

	private static final class Chunk {
		int x, z;
		float dirX, dirZ;
		boolean sure;
	}

	private final Path file;
	private final Map<Long, float[]> dirs = new HashMap<>();
	private boolean dirty;

	/** Remembers in {@code file} (created when first needed); null remembers nothing. */
	public RiverMemory(Path file) {
		this.file = file;
		if (file == null || !Files.isRegularFile(file)) return;
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Stored stored = GSON.fromJson(in, Stored.class);
			if (stored == null || stored.chunks == null) return;
			for (Chunk c : stored.chunks) if (c != null) dirs.put(key(c.x, c.z), new float[] {c.dirX, c.dirZ, c.sure ? 1 : 0});
		} catch (IOException | JsonParseException ignored) {
			// unreadable or damaged: start afresh
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
		Stored stored = new Stored();
		for (Map.Entry<Long, float[]> e : dirs.entrySet()) {
			Chunk c = new Chunk();
			c.x = (int) (e.getKey() >> 32);
			c.z = (int) (e.getKey() & 0xFFFFFFFFL);
			c.dirX = Math.round(e.getValue()[0] * 1000) / 1000f; // three places are plenty for a direction
			c.dirZ = Math.round(e.getValue()[1] * 1000) / 1000f;
			c.sure = e.getValue()[2] > 0.5f;
			stored.chunks.add(c);
		}
		stored.chunks.sort((a, b) -> a.x != b.x ? Integer.compare(a.x, b.x) : Integer.compare(a.z, b.z));
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(stored, out);
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException ignored) {
			// try again after the next build
		}
	}
}
