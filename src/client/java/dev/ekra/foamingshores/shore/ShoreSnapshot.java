package dev.ekra.foamingshores.shore;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The surface of a square of columns, copied from the world on the client thread so the shore
 * fields can be built on a worker thread without touching the level.
 */
public final class ShoreSnapshot {
	public final int originX, originZ, size;
	/**
	 * Y of each column's surface, row-major by z then x: the topmost solid-or-fluid block (leaves
	 * ignored), or, under an overhang that leaves the space just above the water open, the surface
	 * beneath it, so waves run under floating blocks and only things at water level stop them.
	 */
	final int[] topY;
	/** Whether that topmost block is water. */
	final boolean[] water;
	/** The water surface the waves belong to: the most common surface height among water columns. */
	int waterY;
	/** Chunks (as {@link ChunkPos#pack}) that were not loaded yet; their columns count as open water. */
	public final Set<Long> missingChunks = new HashSet<>();

	private ShoreSnapshot(int originX, int originZ, int size) {
		this.originX = originX;
		this.originZ = originZ;
		this.size = size;
		this.topY = new int[size * size];
		this.water = new boolean[size * size];
	}

	/**
	 * Reads the columns; a column in a chunk that has not arrived yet counts as open water so the sea
	 * stays open. The client's {@code hasChunk} always answers true and hands out an empty placeholder
	 * chunk, whose surface reads as the bottom of the world, so ask the chunk cache without creating one.
	 */
	public static ShoreSnapshot capture(Level level, int originX, int originZ, int size) {
		ShoreSnapshot s = new ShoreSnapshot(originX, originZ, size);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int seaTop = level.getSeaLevel() - 1;
		for (int dz = 0; dz < size; dz++) {
			for (int dx = 0; dx < size; dx++) {
				int x = originX + dx, z = originZ + dz, i = dz * size + dx;
				if (level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) {
					s.topY[i] = seaTop;
					s.water[i] = true;
					s.missingChunks.add(ChunkPos.pack(x >> 4, z >> 4));
					continue;
				}
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				pos.set(x, y, z);
				s.topY[i] = y;
				s.water[i] = level.getFluidState(pos).is(FluidTags.WATER);
			}
		}

		Map<Integer, Integer> counts = new HashMap<>();
		for (int i = 0; i < size * size; i++) if (s.water[i]) counts.merge(s.topY[i], 1, Integer::sum);
		s.waterY = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(seaTop);

		// A column whose top is above the water but open just above the water's surface is an
		// overhang: look under it for the surface the water actually meets (at most 4 blocks down,
		// which is as deep as the shore fields look).
		var blocks = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque();
		int open = s.waterY + 1;
		for (int dz = 0; dz < size; dz++) {
			for (int dx = 0; dx < size; dx++) {
				int x = originX + dx, z = originZ + dz, i = dz * size + dx;
				if (s.topY[i] <= open || s.missingChunks.contains(ChunkPos.pack(x >> 4, z >> 4))) continue;
				if (blocks.test(level.getBlockState(pos.set(x, open, z)))) continue;
				int y = open - 1;
				while (y > s.waterY - 4 && !blocks.test(level.getBlockState(pos.set(x, y, z)))) y--;
				pos.set(x, y, z);
				s.topY[i] = y;
				s.water[i] = level.getFluidState(pos).is(FluidTags.WATER);
			}
		}
		return s;
	}
}
