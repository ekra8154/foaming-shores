package dev.ekra.foamingshores.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ekra.foamingshores.FoamSettings;
import dev.ekra.foamingshores.shore.ShoreRegion;
import dev.ekra.foamingshores.wave.WaveMath;
import dev.ekra.foamingshores.wave.WaveSchedule;
import net.minecraft.client.multiplayer.ClientLevel;
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
		for (Foot f : feet) {
			float[] fv = WaveMath.fieldsAt(r, f.tx(), f.tz());
			if (fv == null) continue;
			int wx = r.originX * 16 + f.tx(), wz = r.originZ * 16 + f.tz();
			// Measured from where this foot's water came ashore, with each wave as it is here.
			float xl = fv[0] - fv[2];
			float[] c = WaveMath.crossAt(r, f.tx(), f.tz(), s);
			for (WaveSchedule.Wave w : waves) {
				WaveMath.Local l = WaveMath.local(w, r.originX + f.tx() / 16.0, r.originZ + f.tz() / 16.0, fv[2], s);
				WaveMath.Times t = WaveMath.times(w, l, wx, wz, xl, fv[1], s, noise);
				if (t != null && t.tCross() > from && t.tCross() <= to)
					splash(f, r, Math.sqrt(Math.max(0, Math.min(1, (xl + t.Re()) / t.Re()))), s);
				// The crossing front hits the wall too, weaker the further it has fallen behind.
				if (c != null) {
					WaveMath.Local l2 = WaveMath.local(w, r.originX + f.tx() / 16.0, r.originZ + f.tz() / 16.0, c[1], s);
					WaveMath.Times t2 = WaveMath.times(w, l2, wx, wz, c[0] - c[1], fv[1], s, noise);
					if (t2 != null && t2.tCross() > from && t2.tCross() <= to)
						splash(f, r, Math.sqrt(Math.max(0, Math.min(1, (c[0] - c[1] + t2.Re()) / t2.Re())) * Math.min(1, c[2])), s);
				}
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
				// A square turned to face the camera: the corners rotated by the camera's orientation,
				// counter-clockwise as seen from the camera like vanilla particles (the particle
				// pipeline culls back faces).
				camera.transform(a.set(h, -h, 0));
				camera.transform(b.set(h, h, 0));
				camera.transform(c.set(-h, h, 0));
				camera.transform(dd.set(-h, -h, 0));
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
		// The particle vertex format: position, uv, colour, light.
		buffer.addVertex(pose, x, y, z).setUv(u, v).setColor(r, g, b, a).setLight(light);
	}
}
