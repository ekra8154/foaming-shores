package dev.ekra.foamingshores.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

import java.util.BitSet;

/**
 * Where baked block tops live: one page per density (16, 8 or 4 texels per block), each a square
 * texture of equal slots, one 16x16 block tile per slot. A tile far enough off that a texel is
 * smaller than a pixel on screen goes to a coarser page: fewer pixels to bake, and nothing to see
 * where it changes. Every page is held twice: the world draws from one buffer while the next
 * animation step is baked into the other a share at a time, and the two swap when that step comes.
 * Each buffer keeps its own slots.
 */
final class FoamAtlas {
	/** Texels per block of each page, finest first. */
	static final int[] DENSITIES = {16, 8, 4};
	static final int PAGE = 2048;

	final Page[] pages = new Page[DENSITIES.length];

	FoamAtlas(GpuDevice device) {
		for (int i = 0; i < DENSITIES.length; i++) pages[i] = new Page(device, i, DENSITIES[i]);
	}

	static final class Page {
		final int index, density, slotPx, perRow;
		final Identifier[] ids = new Identifier[2];
		final GpuTexture[] textures = new GpuTexture[2];
		final GpuTextureView[] views = new GpuTextureView[2];
		private final BitSet[] used = {new BitSet(), new BitSet()};

		Page(GpuDevice device, int index, int density) {
			this.index = index;
			this.density = density;
			this.slotPx = density * 16;
			this.perRow = PAGE / slotPx;
			for (int b = 0; b < 2; b++) {
				ids[b] = Identifier.fromNamespaceAndPath("foamingshores", "foam_tiles_" + density + "_" + b);
				textures[b] = device.createTexture("Foaming Shores tiles at " + density + " (" + b + ")", GpuTexture.USAGE_RENDER_ATTACHMENT
					| GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, PAGE, PAGE, 1, 1);
				views[b] = device.createTextureView(textures[b]);
				Minecraft.getInstance().getTextureManager().register(ids[b], new PageTexture(textures[b], views[b]));
			}
		}

		int slots() {
			return perRow * perRow;
		}

		/** A free slot in buffer {@code b}, or -1 when that buffer's page is full. */
		int alloc(int b) {
			int s = used[b].nextClearBit(0);
			if (s >= slots()) return -1;
			used[b].set(s);
			return s;
		}

		void free(int b, int slot) {
			if (slot >= 0) used[b].clear(slot);
		}

		int slotX(int slot) {
			return (slot % perRow) * slotPx;
		}

		int slotY(int slot) {
			return (slot / perRow) * slotPx;
		}
	}

	/** A page buffer, registered so vanilla render types can sample it. */
	private static final class PageTexture extends AbstractTexture {
		PageTexture(GpuTexture texture, GpuTextureView view) {
			this.texture = texture;
			this.textureView = view;
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		}
	}
}
