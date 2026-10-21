package dev.ekra.foamingshores.shore;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
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
	/**
	 * Whether that topmost block is water. A stream running over land is not: its column's surface
	 * is the land under it (see {@link #surfY}).
	 */
	final boolean[] water;
	/**
	 * Y of the water block whose surface a column shows, or {@link #NO_SURFACE}: the water's own layer
	 * for water at that height, the stream's top block for a stream over land, the pool's top block
	 * for water lower than the body's.
	 */
	final int[] surfY;
	public static final int NO_SURFACE = Integer.MIN_VALUE;
	/** Whether a column's water at the body's layer lies in a river biome, or an ocean or beach biome. */
	final boolean[] river, ocean;
	/** How deep a column's water at the body's layer is, in blocks (1 to 4, 4 meaning 4 or more). */
	final byte[] depth;
	/** The water surface the waves belong to: the most common surface height among water columns. */
	int waterY;
	/**
	 * Where a column has a water surface, the heights (0..1 above surfY) of its corners as vanilla draws it,
	 * four per column in the order north-west, south-west, south-east, north-east: the same corners in
	 * the same order make the same two triangles, so whatever lies on them lies on the water.
	 */
	final float[] corners;
	/**
	 * Chunks (as {@link ChunkPos#pack}) that were not loaded yet; their columns count as water (so
	 * the sea stays open) but not as ocean, so no waves start there until they arrive.
	 */
	public final Set<Long> missingChunks = new HashSet<>();

	private ShoreSnapshot(int originX, int originZ, int size) {
		this.originX = originX;
		this.originZ = originZ;
		this.size = size;
		this.topY = new int[size * size];
		this.water = new boolean[size * size];
		this.surfY = new int[size * size];
		this.river = new boolean[size * size];
		this.ocean = new boolean[size * size];
		this.depth = new byte[size * size];
		this.corners = new float[size * size * 4];
	}

	/**
	 * Reads the columns; a column in a chunk that has not arrived yet counts as open water so the sea
	 * stays open. The client's {@code hasChunk} always answers true and hands out an empty placeholder
	 * chunk, whose surface reads as the bottom of the world, so ask the chunk cache without creating one.
	 */
	public static ShoreSnapshot capture(Level level, int originX, int originZ, int size) {
		Capture c = new Capture(originX, originZ, size, Integer.MIN_VALUE);
		c.step(level, Integer.MAX_VALUE);
		return c.snapshot;
	}

	/**
	 * A capture done a few rows at a time (a wide square reads too many blocks for one tick): five
	 * passes over the rows, the water's height worked out after the first unless it is given (a finer
	 * level takes its parent's, so both agree on what is water and what is land).
	 */
	public static final class Capture {
		private final ShoreSnapshot s;
		private final ShoreSnapshot snapshot;
		private final int forcedWaterY;
		private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		private int pass, row;

		/** {@code waterY}: the water's height to use, or Integer.MIN_VALUE to find it. */
		public Capture(int originX, int originZ, int size, int waterY) {
			this.s = this.snapshot = new ShoreSnapshot(originX, originZ, size);
			this.forcedWaterY = waterY;
		}

		/** Reads up to {@code rows} rows (of all passes together); true once the snapshot is complete. */
		public boolean step(Level level, int rows) {
			while (pass < 5 && rows > 0) {
				readRow(level, pass, row);
				rows--;
				if (++row < s.size) continue;
				row = 0;
				if (pass == 0) findWaterY(level);
				pass++;
			}
			return pass >= 5;
		}

		public ShoreSnapshot snapshot() {
			return pass >= 5 ? snapshot : null;
		}

		private void findWaterY(Level level) {
			int seaTop = level.getSeaLevel() - 1;
			if (forcedWaterY != Integer.MIN_VALUE) {
				s.waterY = forcedWaterY;
				return;
			}
			Map<Integer, Integer> counts = new HashMap<>();
			for (int i = 0; i < s.size * s.size; i++) if (s.water[i]) counts.merge(s.topY[i], 1, Integer::sum);
			s.waterY = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(seaTop);
		}

		private void readRow(Level level, int pass, int dz) {
			int size = s.size, originX = s.originX, originZ = s.originZ;
			int layer = s.waterY;
			switch (pass) {
				case 0 -> {
					int seaTop = level.getSeaLevel() - 1;
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
				case 1 -> {
					// A column whose top is above the water is a wall only if something blocks movement in the
					// water's own layer. Otherwise whatever is above floats (a block over the water, an overhang
					// over the beach): look under it for the surface the water actually meets (at most 4 blocks
					// down, which is as deep as the shore fields look).
					var blocks = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque();
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
				case 2 -> {
					// Water standing above the body's level on land (a stream, a spilled source): the waves see
					// the land under it, and their foam lies on the stream's surface.
					for (int dx = 0; dx < size; dx++) {
						int x = originX + dx, z = originZ + dz, i = dz * size + dx;
						s.surfY[i] = s.water[i] ? (s.topY[i] > layer ? s.topY[i] : Math.min(s.topY[i], layer)) : NO_SURFACE;
						if (!s.water[i] || s.topY[i] <= layer || s.missingChunks.contains(ChunkPos.pack(x >> 4, z >> 4))) continue;
						int y = s.topY[i];
						while (y > layer && level.getFluidState(pos.set(x, y, z)).is(FluidTags.WATER)) y--;
						s.topY[i] = y;
						s.water[i] = level.getFluidState(pos.set(x, y, z)).is(FluidTags.WATER);
						if (s.water[i]) s.surfY[i] = y; // water all the way down to the body's layer: the body's own
					}
				}
				case 3 -> {
					// Biomes say which water is river and which is sea (the rest is still water: lakes, ponds, a
					// river's side pockets); the shallows are where it is a block or two deep.
					for (int dx = 0; dx < size; dx++) {
						int x = originX + dx, z = originZ + dz, i = dz * size + dx;
						if (!s.water[i] || s.topY[i] != layer || s.missingChunks.contains(ChunkPos.pack(x >> 4, z >> 4))) continue;
						var biome = level.getBiome(pos.set(x, layer, z));
						s.river[i] = biome.is(BiomeTags.IS_RIVER);
						s.ocean[i] = biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_BEACH);
						int d = 1;
						while (d < 4 && level.getFluidState(pos.set(x, layer - d, z)).is(FluidTags.WATER)) d++;
						s.depth[i] = (byte) d;
					}
				}
				default -> {
					for (int dx = 0; dx < size; dx++) {
						int i = dz * size + dx;
						if (s.surfY[i] == NO_SURFACE || s.missingChunks.contains(ChunkPos.pack((originX + dx) >> 4, (originZ + dz) >> 4))) {
							Arrays.fill(s.corners, i * 4, i * 4 + 4, 8f / 9f);
							continue;
						}
						surfaceCorners(level, pos.set(originX + dx, s.surfY[i], originZ + dz), s.corners, i * 4);
					}
				}
			}
		}
	}

	/**
	 * A made-up shore (a sea along one side, a river winding in from it, a lake beside the river)
	 * for building once at start-up, so the first real build in a world runs compiled code rather
	 * than the interpreter.
	 */
	public static ShoreSnapshot warmUp(int size) {
		ShoreSnapshot s = new ShoreSnapshot(0, 0, size);
		int waterY = 62;
		s.waterY = waterY;
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				int i = z * size + x;
				double riverX = size * 0.5 + Math.sin(z * 0.08) * size * 0.15;
				boolean sea = z < size / 4 + (int) (Math.sin(x * 0.1) * 3);
				boolean river = Math.abs(x - riverX) < 4 + z % 3;
				boolean lake = Math.hypot(x - size * 0.8, z - size * 0.7) < size * 0.1;
				boolean water = sea || river || lake;
				s.water[i] = water;
				s.topY[i] = water ? waterY : waterY + 1 + (x * 7 + z * 3) % 2;
				s.surfY[i] = water ? waterY : NO_SURFACE;
				s.ocean[i] = sea;
				s.river[i] = river && !sea;
				s.depth[i] = (byte) (water ? Math.min(4, 1 + (x + z) % 4) : 0);
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
