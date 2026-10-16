package dev.ekra.foamingshores.shore;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

import java.util.Arrays;
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
	 * ignored), or, where the block in the water's own layer does not block movement, the surface
	 * beneath whatever floats above it: water runs under floating blocks as if they were not there,
	 * and only something standing in the water's layer is a wall.
	 */
	final int[] topY;
	/** Whether that topmost block is water. */
	final boolean[] water;
	/** The water surface the waves belong to: the most common surface height among water columns. */
	int waterY;
	/**
	 * For water columns, the heights (0..1 above topY) of the surface's corners as vanilla draws it,
	 * four per column in the order north-west, south-west, south-east, north-east: the same corners in
	 * the same order make the same two triangles, so whatever lies on them lies on the water.
	 */
	final float[] corners;
	/** Chunks (as {@link ChunkPos#pack}) that were not loaded yet; their columns count as open water. */
	public final Set<Long> missingChunks = new HashSet<>();

	private ShoreSnapshot(int originX, int originZ, int size) {
		this.originX = originX;
		this.originZ = originZ;
		this.size = size;
		this.topY = new int[size * size];
		this.water = new boolean[size * size];
		this.corners = new float[size * size * 4];
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

		// A column whose top is above the water is a wall only if something blocks movement in the
		// water's own layer. Otherwise whatever is above floats (a block over the water, an overhang
		// over the beach): look under it for the surface the water actually meets (at most 4 blocks
		// down, which is as deep as the shore fields look).
		var blocks = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque();
		int layer = s.waterY;
		for (int dz = 0; dz < size; dz++) {
			for (int dx = 0; dx < size; dx++) {
				int x = originX + dx, z = originZ + dz, i = dz * size + dx;
				if (s.topY[i] <= layer || s.missingChunks.contains(ChunkPos.pack(x >> 4, z >> 4))) continue;
				if (level.getBlockState(pos.set(x, layer, z)).is(BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP_NO_LEAVES)) continue;
				int y = layer;
				while (y > s.waterY - 4 && !blocks.test(level.getBlockState(pos.set(x, y, z)))) y--;
				pos.set(x, y, z);
				s.topY[i] = y;
				s.water[i] = level.getFluidState(pos).is(FluidTags.WATER);
			}
		}

		for (int dz = 0; dz < size; dz++) {
			for (int dx = 0; dx < size; dx++) {
				int i = dz * size + dx;
				if (!s.water[i] || s.missingChunks.contains(ChunkPos.pack((originX + dx) >> 4, (originZ + dz) >> 4))) {
					Arrays.fill(s.corners, i * 4, i * 4 + 4, 8f / 9f);
					continue;
				}
				surfaceCorners(level, pos.set(originX + dx, s.topY[i], originZ + dz), s.corners, i * 4);
			}
		}
		return s;
	}

	/** The corner heights of the water surface at {@code pos}, worked out as vanilla's FluidRenderer does. */
	private static void surfaceCorners(Level level, BlockPos pos, float[] out, int o) {
		Fluid type = level.getFluidState(pos).getType();
		float self = height(level, type, pos);
		if (self >= 1) {
			Arrays.fill(out, o, o + 4, 1f);
			return;
		}
		float n = height(level, type, pos.north()), sth = height(level, type, pos.south());
		float e = height(level, type, pos.east()), w = height(level, type, pos.west());
		out[o] = corner(level, type, self, n, w, pos.north().west());
		out[o + 1] = corner(level, type, self, sth, w, pos.south().west());
		out[o + 2] = corner(level, type, self, sth, e, pos.south().east());
		out[o + 3] = corner(level, type, self, n, e, pos.north().east());
	}

	private static float corner(Level level, Fluid type, float self, float a, float b, BlockPos cornerPos) {
		if (a >= 1 || b >= 1) return 1;
		float[] wh = new float[2];
		if (a > 0 || b > 0) {
			float c = height(level, type, cornerPos);
			if (c >= 1) return 1;
			weigh(wh, c);
		}
		weigh(wh, self);
		weigh(wh, b);
		weigh(wh, a);
		return wh[0] / wh[1];
	}

	private static void weigh(float[] wh, float h) {
		if (h >= 0.8f) {
			wh[0] += h * 10;
			wh[1] += 10;
		} else if (h >= 0) {
			wh[0] += h;
			wh[1]++;
		}
	}

	private static float height(Level level, Fluid type, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		FluidState fluid = state.getFluidState();
		if (type.isSame(fluid.getType())) return type.isSame(level.getFluidState(pos.above()).getType()) ? 1 : fluid.getOwnHeight();
		return !state.isSolid() ? 0 : -1;
	}
}
