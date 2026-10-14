package dev.ekra.foamingshores.shore;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The surface of a square of columns, copied from the world on the client thread so the shore
 * fields can be built on a worker thread without touching the level.
 */
public final class ShoreSnapshot {
	public final int originX, originZ, size;
	/** Y of the topmost solid-or-fluid block in each column (leaves ignored), row-major by z then x. */
	final int[] topY;
	/** Whether that topmost block is water. */
	final boolean[] water;

	private ShoreSnapshot(int originX, int originZ, int size) {
		this.originX = originX;
		this.originZ = originZ;
		this.size = size;
		this.topY = new int[size * size];
		this.water = new boolean[size * size];
	}

	/** Reads the columns; a column in an unloaded chunk counts as open water so the sea stays open. */
	public static ShoreSnapshot capture(Level level, int originX, int originZ, int size) {
		ShoreSnapshot s = new ShoreSnapshot(originX, originZ, size);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int seaTop = level.getSeaLevel() - 1;
		for (int dz = 0; dz < size; dz++) {
			for (int dx = 0; dx < size; dx++) {
				int x = originX + dx, z = originZ + dz, i = dz * size + dx;
				if (!level.hasChunk(x >> 4, z >> 4)) {
					s.topY[i] = seaTop;
					s.water[i] = true;
					continue;
				}
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				pos.set(x, y, z);
				s.topY[i] = y;
				s.water[i] = level.getFluidState(pos).is(FluidTags.WATER);
			}
		}
		return s;
	}
}
