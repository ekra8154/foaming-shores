package dev.ekra.foamingshores.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ekra.foamingshores.FoamSettings;
import dev.ekra.foamingshores.shore.ShoreRegion;
import dev.ekra.foamingshores.wave.WaveSchedule;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Splash droplets at walls, and the foam flecks they leave where they land. The only things in the
 * effect that are simulated rather than computed: a wall's foot texel throws a splash at the moment a
 * wave's front reaches it, which is solved in closed form exactly as the bake shader does. Droplets
 * fly as one-texel camera-facing billboards (like vanilla particles); a droplet that lands on dry
 * ground becomes a fleck lying flat on it.
 */
final class Splashes {
	private static final double G = 10; // blocks/s^2: 9.8 m/s^2
	private static final float FLECK_LIFE = 1.2f, PIXEL = 1 / 16f;
	private static final int MAX = 3000;

	/** A wall's foot texel: region texel coordinates, the direction toward the wall, the surface height. */
	record Foot(int tx, int tz, int dx, int dz, float surface) {
	}

	private static final class Drop {
		double x, y, z, px, py, pz, vx, vy, vz;
		float age;
		boolean fleck;
	}

	private final List<Drop> drops = new ArrayList<>();
	private final RandomSource random = RandomSource.create();
	private ShoreRegion region;
	private List<Foot> feet = List.of();
	private byte[] noise;
	private double lastTime = Double.NaN;

	void setRegion(ShoreRegion r, List<Foot> f) {
		region = r;
		feet = f;
		lastTime = Double.NaN;
	}

	void setNoise(byte[] rgba) {
		noise = rgba;
	}

	void clear() {
		drops.clear();
		region = null;
		feet = List.of();
	}

	// ---- Simulation (client ticks) ---------------------------------------------------------------

	void tick(double now, List<WaveSchedule.Wave> waves, FoamSettings s) {
		ShoreRegion r = region;
		if (r == null) return;
		if (!Double.isNaN(lastTime) && now > lastTime && noise != null && s.splash > 0) spawn(r, lastTime, now, waves, s);
		lastTime = now;

		double dt = 0.05;
		for (int i = drops.size() - 1; i >= 0; i--) {
			Drop d = drops.get(i);
			d.px = d.x;
			d.py = d.y;
			d.pz = d.z;
			d.age += (float) dt;
			if (d.fleck) {
				if (d.age >= FLECK_LIFE) drops.remove(i);
				continue;
			}
			d.vy -= G * dt;
			d.x += d.vx * dt;
			d.y += d.vy * dt;
			d.z += d.vz * dt;
			if (d.age > 4) { drops.remove(i); continue; }
			int bx = (int) Math.floor(d.x) - r.originX, bz = (int) Math.floor(d.z) - r.originZ;
			if (bx < 0 || bz < 0 || bx >= r.size || bz >= r.size) { drops.remove(i); continue; }
			int bi = bz * r.size + bx;
			boolean water = r.water[bi];
			double ground = water ? r.waterY + 8.0 / 9.0 : r.topY[bi] + 1;
			if (d.y < ground) {
				// Came down on dry ground: a fleck on it, snapped to its texel. Into water, or into a
				// wall's side on the way up: gone.
				if (!water && d.vy < 0 && d.py >= ground - 1e-3) {
					d.fleck = true;
					d.age = 0;
					d.x = d.px = Math.floor(d.x * 16) / 16 + PIXEL / 2;
					d.z = d.pz = Math.floor(d.z * 16) / 16 + PIXEL / 2;
					d.y = d.py = ground + 0.003;
				} else {
					drops.remove(i);
				}
			}
		}
	}

	/** Splashes from every wall foot whose wave front arrived during (from, to]. */
	private void spawn(ShoreRegion r, double from, double to, List<WaveSchedule.Wave> waves, FoamSettings s) {
		float runup = s.runup * 16, speed = s.speed * 16, approach = s.approach * 16;
		for (Foot f : feet) {
			float[] fv = fieldsAt(r, f.tx(), f.tz());
			if (fv == null) continue;
			float dp = fv[0], e = 1 + (fv[1] - 1) * s.shelter;
			int wx = Math.floorMod(r.originX * 16 + f.tx(), 256), wz = Math.floorMod(r.originZ * 16 + f.tz(), 256);
			for (WaveSchedule.Wave w : waves) {
				float jv = ((noise[(((wz + w.oy()) & 255) * 256 + ((wx + w.ox()) & 255)) * 4] & 255) / 255f - 0.5f) * 2 * s.jag;
				float Re = runup * w.amp() * e;
				double Ta = approach / speed, Tu = 2 * runup * w.amp() / speed, TuE = Tu * Math.min(1, e);
				double tR0 = w.t0() + Ta;
				float uR = (jv - dp) / Re;
				if (uR > 1) continue;
				double tCross = uR <= 0 ? tR0 : tR0 + TuE * (1 - Math.sqrt(1 - uR));
				if (tCross > from && tCross <= to) splash(f, r, Math.sqrt(Math.max(0, Math.min(1, (dp + Re) / Re))), s);
			}
		}
	}

	/** Throws droplets off one foot texel; faster waves throw more and higher, most bounce back off the wall. */
	private void splash(Foot f, ShoreRegion r, double energy, FoamSettings s) {
		int n = (int) Math.floor(s.splash * (0.25 + 1.25 * energy) + random.nextDouble());
		for (int i = 0; i < n && drops.size() < MAX; i++) {
			Drop d = new Drop();
			d.x = d.px = (r.originX * 16 + f.tx() + 0.5) / 16;
			d.z = d.pz = (r.originZ * 16 + f.tz() + 0.5) / 16;
			d.y = d.py = f.surface() + 0.03;
			double up = Math.sqrt(2 * G * s.splashHeight / 16) * (0.25 + 0.75 * energy) * (0.45 + 0.6 * random.nextDouble());
			double away = random.nextDouble() < 0.25 ? 1 : -1, sp = (3 + 12 * random.nextDouble()) / 16, side = (random.nextDouble() - 0.5) * 14 / 16;
			d.vx = f.dx() * away * sp + (f.dz() != 0 ? side : 0);
			d.vz = f.dz() * away * sp + (f.dx() != 0 ? side : 0);
			d.vy = up;
			drops.add(d);
		}
	}

	/** Arrival and exposure at a region texel, blended between field samples like the shader does. */
	private static float[] fieldsAt(ShoreRegion r, int tx, int tz) {
		float per = ShoreRegion.TEXELS_PER_SAMPLE;
		float sx = (tx + 0.5f) / per - 0.5f, sz = (tz + 0.5f) / per - 0.5f;
		int S = r.samples;
		int ix = Math.max(0, Math.min(S - 2, (int) Math.floor(sx))), iz = Math.max(0, Math.min(S - 2, (int) Math.floor(sz)));
		float fx = Math.max(0, Math.min(1, sx - ix)), fz = Math.max(0, Math.min(1, sz - iz));
		float arr = 0, exp = 0, wsum = 0;
		for (int k = 0; k < 4; k++) {
			int ox = k & 1, oz = k >> 1, p = (iz + oz) * S + ix + ox;
			float w = (ox == 1 ? fx : 1 - fx) * (oz == 1 ? fz : 1 - fz);
			if (r.arrival[p] <= -900) continue;
			arr += r.arrival[p] * w;
			exp += r.exposure[p] * w;
			wsum += w;
		}
		return wsum > 0 ? new float[] {arr / wsum, exp / wsum} : null;
	}

	// ---- Drawing (frames) ---------------------------------------------------------------------------

	boolean isEmpty() {
		return drops.isEmpty();
	}

	void render(PoseStack.Pose pose, VertexConsumer buffer, ClientLevel level, Quaternionf camera, float partial, int foamRgb) {
		int fr = (foamRgb >> 16) & 255, fg = (foamRgb >> 8) & 255, fb = foamRgb & 255;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f(), dd = new Vector3f();
		float h = PIXEL / 2;
		for (Drop d : drops) {
			double x = d.px + (d.x - d.px) * partial, y = d.py + (d.y - d.py) * partial, z = d.pz + (d.z - d.pz) * partial;
			pos.set(Math.floor(x), Math.floor(y), Math.floor(z));
			int light = LightCoordsUtil.getLightCoords(level, pos);
			if (d.fleck) {
				int alpha = Math.max(0, Math.min(255, Math.round(255 * (1 - (d.age + partial * 0.05f) / FLECK_LIFE))));
				quad(buffer, pose, (float) x - h, (float) y, (float) z - h, (float) x - h, (float) y, (float) z + h,
					(float) x + h, (float) y, (float) z + h, (float) x + h, (float) y, (float) z - h, fr, fg, fb, alpha, light);
			} else {
				// A square turned to face the camera: the corners rotated by the camera's orientation.
				camera.transform(a.set(-h, -h, 0));
				camera.transform(b.set(-h, h, 0));
				camera.transform(c.set(h, h, 0));
				camera.transform(dd.set(h, -h, 0));
				float px = (float) x, py = (float) y, pz = (float) z;
				quad(buffer, pose, px + a.x, py + a.y, pz + a.z, px + b.x, py + b.y, pz + b.z,
					px + c.x, py + c.y, pz + c.z, px + dd.x, py + dd.y, pz + dd.z, fr, fg, fb, 255, light);
			}
		}
	}

	private static void quad(VertexConsumer buffer, PoseStack.Pose pose,
		float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3,
		int r, int g, int b, int a, int light) {
		vertex(buffer, pose, x0, y0, z0, 0, 0, r, g, b, a, light);
		vertex(buffer, pose, x1, y1, z1, 0, 1, r, g, b, a, light);
		vertex(buffer, pose, x2, y2, z2, 1, 1, r, g, b, a, light);
		vertex(buffer, pose, x3, y3, z3, 1, 0, r, g, b, a, light);
	}

	private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, float u, float v,
		int r, int g, int b, int a, int light) {
		buffer.addVertex(pose, x, y, z).setColor(r, g, b, a).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 1, 0);
	}
}
