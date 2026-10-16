package dev.ekra.foamingshores.sound;

import dev.ekra.foamingshores.FoamSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * The shore sound synthesiser, ported from the browser playground's SurfSynth (tuned there by ear).
 * Nothing is recorded: every layer is noise and bubbles shaped live. It only takes control values:
 * how loud each layer is in each ear (from {@link ShoreSounds}) and one-off events (a wave
 * breaking, a wall being hit) with how far into the next block they happened. Runs on the sound
 * engine's thread; one instance per stream.
 */
final class SurfSynth {
	/** Layers with a continuous level: distant surf, run-up wash, foam fizz, pouring, puddle soak. */
	static final int BED = 0, WASH = 1, FIZZ = 2, TRICKLE = 3, SOAK = 4, LAYERS = 5;
	/** Seconds each layer takes to follow its control: the distant surf swells slowly. */
	private static final double[] GLIDE = {0.9, 0.12, 0.2, 0.2, 0.5};

	/** A one-off sound: a wave breaking ({@code crash}) or slapping a wall, with gains per ear. */
	record Event(boolean crash, double delay, double amp, double bright, double l, double r) {
	}

	private final double sr;
	private int seed = 0x2545F491;
	/** Per layer: gain in the left ear, gain in the right ear, brightness 0..1. */
	private final double[][] target = new double[LAYERS][3], cur = new double[LAYERS][3];
	private final List<Pending> pending = new ArrayList<>();
	private final List<Voice> voices = new ArrayList<>();
	private final List<Chirp> chirps = new ArrayList<>();
	private double gain = 0.7;
	private FoamSettings p;
	private double p0, p1, p2, br;
	private final Filter bedLo = new Filter(), bedHi = new Filter(), washF = new Filter(), airF = new Filter();
	private final Lfo slow = new Lfo(0.3), rough = new Lfo(9), crumble = new Lfo(30);
	private final Bank bank;
	private final Verb verb;
	private Bank.Spec spWash, spFizz, spTrickle, spSoak, spCrash, spSpray;

	SurfSynth(double sampleRate) {
		sr = sampleRate;
		bank = new Bank(sr);
		verb = new Verb(sr);
	}

	private double rand() {
		int x = seed;
		x ^= x << 13;
		x ^= x >>> 17;
		x ^= x << 5;
		seed = x;
		return (x & 0xFFFFFFFFL) / 4294967296.0;
	}

	/** Smooth random wobble in 0..1, a new value {@code rate} times a second. */
	private final class Lfo {
		final double rate;
		double ph, a = 0.5, b = 0.5;

		Lfo(double rate) {
			this.rate = rate;
		}

		double tick() {
			ph += rate / sr;
			if (ph >= 1) {
				ph -= 1;
				a = b;
				b = rand();
			}
			double s = ph * ph * (3 - 2 * ph);
			return a + (b - a) * s;
		}
	}

	/** A new set of sliders: rebuilds the bubble populations. */
	void setParams(FoamSettings s) {
		p = s;
		bank.setAlpha(s.bubAlpha);
		// Bubble populations: sizes in mm, how strongly small ones outnumber big ones, rising or not.
		spWash = bank.spec(s.washBubMin, s.washBubMax, s.washSpread, false, s.washBig);
		spFizz = bank.spec(s.fizzBubMin, s.fizzBubMax, s.fizzSpread, false, s.fizzBig);
		spTrickle = bank.spec(s.trBubMin, s.trBubMax, s.trSpread, true, s.trBig);
		spSoak = bank.spec(0.2, 1, 3, false, 1);
		spCrash = bank.spec(s.crBubMin, s.crBubMax, s.crSpread, true, s.crBig);
		spSpray = bank.spec(0.2, s.slapSprayMax, 2, false, s.slapSprayBig);
		verb.fb = s.verbSize;
		verb.damp = s.verbDamp;
	}

	/** New layer levels ({@code layers[layer] = {left, right, brightness}}) and events for the next block. */
	void control(double[][] layers, List<Event> events, double masterGain) {
		for (int k = 0; k < LAYERS; k++) System.arraycopy(layers[k], 0, target[k], 0, 3);
		for (Event e : events) pending.add(new Pending(e, Math.round(e.delay() * sr)));
		gain = masterGain;
	}

	// ---- Bubbles ------------------------------------------------------------------------------

	/** {@code rate} bubbles a second from population {@code spec}, this sample, with gains per ear. */
	private void bubbles(Bank.Spec spec, double rate, double gl, double gr) {
		double q = rate / sr;
		while (q > 0) {
			if (q >= 1 || rand() < q) {
				int k = bank.pick(spec, rand());
				double D = Math.pow(rand(), p.bubDepth), a = D * bank.loud[k] * spec.g[k];
				if (spec.rise && D > p.riseCut && p.bubRise > 0 && chirps.size() < 48) chirp(k, a, gl, gr);
				else {
					bank.xL[k] += a * gl;
					bank.xR[k] += a * gr;
				}
			}
			q -= 1;
		}
	}

	/** A bubble close under the surface: its pitch rises as it rings. */
	private void chirp(int k, double a, double gl, double gr) {
		double r = bank.r[k], f0 = 3 / r, d = 0.13 / r + 0.0072 * Math.pow(r, -1.5);
		chirps.add(new Chirp(2 * Math.PI * f0 / sr, 2 * Math.PI * f0 * p.bubRise * d / (sr * sr), Math.exp(-d / sr), a, gl, gr));
	}

	private static final class Chirp {
		double ph, w, a;
		final double dw, k, l, r;

		Chirp(double w, double dw, double k, double a, double l, double r) {
			this.w = w;
			this.dw = dw;
			this.k = k;
			this.a = a;
			this.l = l;
			this.r = r;
		}
	}

	// ---- Events -------------------------------------------------------------------------------

	private static final class Pending {
		final Event e;
		long at;

		Pending(Event e, long at) {
			this.e = e;
			this.at = at;
		}
	}

	private static final class Voice {
		boolean crash;
		long n, att, spray;
		double level = 1, boom = 1, k, kb, l, r, amp, bright;
		final Filter f = new Filter(), lo = new Filter();
	}

	private void start(Event e) {
		if (voices.size() >= 16) return;
		Voice v = new Voice();
		v.crash = e.crash();
		v.l = e.l();
		v.r = e.r();
		v.amp = e.amp();
		v.bright = e.bright();
		if (e.crash()) {
			// A wave breaking: the lip curls over (a swelling rush), then crumbles and settles as it
			// dies, over a thump, while the foam it makes fizzes with bubbles.
			v.att = Math.max(1, Math.round(p.crAttack * (0.75 + 0.5 * rand()) * sr));
			v.k = Math.exp(-1 / (p.crDecay * (0.45 + 0.55 * e.amp()) * sr));
			v.kb = Math.exp(-1 / (0.45 * sr));
			v.lo.set(180, 0.8, sr);
		} else {
			// A wave slapping a wall: a knock, then spray falling back.
			v.att = Math.round(0.004 * sr);
			v.k = Math.exp(-1 / (p.slapDecay * sr));
			v.kb = Math.exp(-1 / (0.1 * sr));
			v.f.set(p.slapFreq, 0.8, sr);
			v.lo.set(220, 0.9, sr);
			v.spray = Math.round(p.slapSprayTime * sr);
		}
		voices.add(v);
	}

	// ---- Render -------------------------------------------------------------------------------

	/** Renders {@code n} stereo samples into {@code outL}/{@code outR}, each in -1..1. */
	void render(float[] outL, float[] outR, int n) {
		if (p == null) {
			java.util.Arrays.fill(outL, 0, n, 0);
			java.util.Arrays.fill(outR, 0, n, 0);
			return;
		}
		for (int k = 0; k < LAYERS; k++) {
			double a = 1 - Math.exp(-n / (sr * GLIDE[k]));
			for (int c = 0; c < 3; c++) cur[k][c] += (target[k][c] - cur[k][c]) * a;
		}
		double[] bed = cur[BED], wash = cur[WASH], fizz = cur[FIZZ], tr = cur[TRICKLE], soak = cur[SOAK];
		bedLo.set(p.bedLow, 0.6, sr);
		bedHi.set(1100, 0.5, sr);
		washF.set(p.washLow + p.washSpan * wash[2], p.washQ, sr);
		airF.set(3800, 0.7, sr);
		for (int i = pending.size() - 1; i >= 0; i--) {
			Pending e = pending.get(i);
			e.at -= n;
			if (e.at <= 0) {
				pending.remove(i);
				start(e.e);
			}
		}
		for (Voice v : voices) {
			if (v.crash) v.f.set(p.crLow + (p.crHigh - p.crLow) * (0.3 + 0.7 * v.bright) * Math.pow(v.level, 0.6), 0.5, sr);
		}
		// Each bubble layer: as many bubbles a second as there is water doing that (above a
		// threshold), each a little louder too, so its loudness follows its control while its grain
		// stays fine.
		Layer bw = layer(wash, p.washRate, 0.25 * p.washBubbles, 0.01), bf = layer(fizz, p.fizzRate, 0.6 * p.fizzLevel, p.fizzThr);
		Layer bt = layer(tr, p.trRate, 0.1 * p.trLevel, 0.01), bs = layer(soak, p.soakRate, 3 * p.soakLevel, 0.01);
		double crumbleD = p.crCrumble, roughD = p.washRough, swellD = p.bedSwell;
		for (int i = 0; i < n; i++) {
			double w = rand() * 2 - 1;
			p0 = 0.99765 * p0 + w * 0.0990460;
			p1 = 0.963 * p1 + w * 0.2965164;
			p2 = 0.57 * p2 + w * 1.0526913;
			double pink = (p0 + p1 + p2 + w * 0.1848) * 0.2;
			br = (br + 0.02 * w) / 1.02;
			double brown = br * 3.5;
			double slowV = slow.tick(), roughV = rough.tick(), crumbleV = crumble.tick();
			double L = 0, R = 0;
			// Distant surf: a low roar that swells and fades with the sets.
			double bedS = (bedLo.run(brown) + p.bedHigh * bedHi.run(pink)) * (1 - swellD + swellD * slowV);
			L += bedS * bed[0];
			R += bedS * bed[1];
			// Run-up and backwash: a rush of water over sand (noise, brighter the faster it runs)
			// full of bubbles being dragged along.
			washF.run(pink);
			airF.run(w);
			double washS = (washF.bp * 1.1 * (1 - roughD + roughD * roughV) + airF.hp * 0.06 * wash[2]) * p.washNoise;
			L += washS * wash[0];
			R += washS * wash[1];
			if (bw != null) bubbles(spWash, bw.rate, bw.l, bw.r);
			// Foam and draining backwash: a dense fizz of tiny bubbles over a faint hiss.
			double hiss = airF.hp * 0.04 * p.fizzHiss * (0.5 + 0.5 * crumbleV);
			L += hiss * fizz[0];
			R += hiss * fizz[1];
			if (bf != null) bubbles(spFizz, bf.rate, bf.l, bf.r);
			if (bt != null) bubbles(spTrickle, bt.rate, bt.l, bt.r);
			if (bs != null) bubbles(spSoak, bs.rate, bs.l, bs.r);

			for (int j = 0; j < voices.size(); j++) {
				Voice v = voices.get(j);
				double env;
				if (v.n < v.att) {
					double x = (double) v.n / v.att;
					env = x * x;
				} else {
					v.level *= v.k;
					v.boom *= v.kb;
					env = v.level;
				}
				double thump = v.n < v.att ? env : v.boom;
				v.n++;
				double s;
				if (v.crash) {
					v.f.run(pink);
					s = v.f.bp * env * (1 - crumbleD + crumbleD * crumbleV) * 4 * p.crNoise + v.lo.run(brown) * thump * p.crThump;
					if (p.crBubbles > 0 && p.crBubVol > 0) {
						double g = 0.6 * p.crBubVol;
						bubbles(spCrash, 50000 * p.crBubbles * env * v.amp, v.l * g, v.r * g);
					}
				} else {
					v.f.run(w);
					s = v.f.bp * env * 0.7 * p.slapKnock + v.lo.run(brown) * thump * 1.1 * p.slapThump;
					if (v.n < v.spray && p.slapSpray > 0 && p.slapSprayVol > 0) {
						double f = 1 - (double) v.n / v.spray, g = 0.6 * p.slapSprayVol;
						bubbles(spSpray, 30000 * p.slapSpray * Math.min(1, v.amp) * f * f, v.l * g, v.r * g);
					}
				}
				L += s * v.l;
				R += s * v.r;
			}
			for (int j = 0; j < chirps.size(); j++) {
				Chirp c = chirps.get(j);
				c.ph += c.w;
				c.w += c.dw;
				c.a *= c.k;
				double s = c.a * Math.sin(c.ph);
				L += s * c.l;
				R += s * c.r;
			}
			bank.run();
			L += bank.l;
			R += bank.rr;
			// The air gives a little of it back.
			verb.run((L + R) * 0.012);
			L += verb.l * p.verbMix;
			R += verb.r * p.verbMix;
			outL[i] = (float) Math.tanh(L * gain);
			outR[i] = (float) Math.tanh(R * gain);
		}
		voices.removeIf(v -> v.n >= v.att && v.level <= 1e-4);
		chirps.removeIf(c -> c.a <= 1e-5);
		bank.flushDenormals();
	}

	private record Layer(double rate, double l, double r) {
	}

	private static Layer layer(double[] c, double perSec, double g, double thr) {
		double m = Math.hypot(c[0], c[1]), e = m - thr;
		if (!(e > 0 && m > 0)) return null;
		double s = g * Math.sqrt(m);
		return new Layer(e * perSec, c[0] / m * s, c[1] / m * s);
	}

	// ---- Building blocks -------------------------------------------------------------------------

	/** A state-variable filter (topology-preserving transform): lowpass, bandpass and highpass at once. */
	static final class Filter {
		private double s1, s2, a1 = 1, a2, a3, k = 1;
		double lp, bp, hp;

		void set(double f, double q, double sr) {
			double g = Math.tan(Math.PI * Math.min(Math.max(f, 10), sr * 0.45) / sr);
			k = 1 / q;
			a1 = 1 / (1 + g * (g + k));
			a2 = g * a1;
			a3 = g * a2;
		}

		double run(double x) {
			double v3 = x - s2, v1 = a1 * s1 + a2 * v3, v2 = s2 + a2 * s1 + a3 * v3;
			s1 = 2 * v1 - s1;
			s2 = 2 * v2 - s2;
			lp = v2;
			bp = v1;
			hp = x - k * v1 - v2;
			return v2;
		}
	}

	/**
	 * A population of bubbles, after van den Doel, "Physically-based Models for Liquid Sounds" (2005).
	 * Nearly every sound water makes is bubbles ringing as they form: a bubble of radius r (metres)
	 * rings at f = 3 / r Hz (Minnaert) and dies away at d = 0.13 / r + 0.0072 r^-1.5 per second.
	 * Radii are quantised to 50 classes from 0.2 mm to 20 mm, each a resonator per ear, and a bubble
	 * is an impulse into its class, so any number of bubbles a second costs the same. A bubble's
	 * loudness is D r^alpha with a depth factor D = rnd^beta, so most are faint and a few stand out:
	 * that is what blends tens of thousands a second into one wet texture instead of a crackle.
	 */
	static final class Bank {
		static final int N = 50;
		final double[] r = new double[N], a1 = new double[N], a2 = new double[N], b0 = new double[N], loud = new double[N];
		final double[] xL = new double[N], xR = new double[N];
		private final double[] yL1 = new double[N], yL2 = new double[N], yR1 = new double[N], yR2 = new double[N];
		double l, rr;

		/** Which radii a source makes (cumulative weights), per-class gain, and whether the loudest rise. */
		record Spec(double[] cdf, boolean rise, double[] g) {
		}

		Bank(double sr) {
			double rMin = 0.0002, rMax = 0.02;
			for (int k = 0; k < N; k++) {
				double rad = rMin * Math.pow(rMax / rMin, k / (double) (N - 1)), f = 3 / rad, d = 0.13 / rad + 0.0072 * Math.pow(rad, -1.5);
				double R = Math.exp(-d / sr), w = 2 * Math.PI * Math.min(f, sr * 0.45) / sr;
				// y[n] = b0 x[n] + a1 y[n-1] - a2 y[n-2]: an impulse a rings as a R^n sin((n + 1) w).
				r[k] = rad;
				a1[k] = 2 * R * Math.cos(w);
				a2[k] = R * R;
				b0[k] = Math.sin(w);
			}
			setAlpha(1.5);
		}

		/** Loudness against size, relative to a 2 mm bubble: bigger bubbles carry more energy. */
		void setAlpha(double alpha) {
			for (int k = 0; k < N; k++) loud[k] = Math.pow(r[k] / 0.002, alpha);
		}

		/**
		 * Classes within [lo, hi] millimetres, weighted 1 / r^gamma. Bubbles over 1 mm are turned down
		 * toward {@code big} (reached at 4 mm and up): they ring lower and longer and give the
		 * trickling, streaming sound, so they can stay in the mix quietly.
		 */
		Spec spec(double lo, double hi, double gamma, boolean rise, double big) {
			double rLo = Math.min(lo, hi) / 1000, rHi = Math.max(lo, hi) / 1000;
			double[] cdf = new double[N];
			double s = 0;
			for (int k = 0; k < N; k++) {
				if (r[k] >= rLo * 0.97 && r[k] <= rHi * 1.03) s += Math.pow(r[k] / rLo, -gamma);
				cdf[k] = s;
			}
			if (s <= 0) { // a range narrower than one class: the nearest class
				int best = 0;
				for (int k = 0; k < N; k++) if (Math.abs(r[k] - rLo) < Math.abs(r[best] - rLo)) best = k;
				for (int k = 0; k < N; k++) cdf[k] = k >= best ? 1 : 0;
			} else for (int k = 0; k < N; k++) cdf[k] /= s;
			double[] g = new double[N];
			for (int k = 0; k < N; k++) g[k] = Math.pow(big, Math.min(1, Math.max(0, Math.log(r[k] / 0.001) / Math.log(4))));
			return new Spec(cdf, rise, g);
		}

		int pick(Spec spec, double u) {
			double[] c = spec.cdf();
			int lo = 0, hi = N - 1;
			while (lo < hi) {
				int m = (lo + hi) >>> 1;
				if (c[m] < u) lo = m + 1;
				else hi = m;
			}
			return lo;
		}

		void run() {
			double L = 0, R = 0;
			for (int k = 0; k < N; k++) {
				double yl = b0[k] * xL[k] + a1[k] * yL1[k] - a2[k] * yL2[k];
				double yr = b0[k] * xR[k] + a1[k] * yR1[k] - a2[k] * yR2[k];
				yL2[k] = yL1[k];
				yL1[k] = yl;
				yR2[k] = yR1[k];
				yR1[k] = yr;
				xL[k] = 0;
				xR[k] = 0;
				L += yl;
				R += yr;
			}
			l = L;
			rr = R;
		}

		/** Resonators ringing down toward zero end in denormals, which are slow on the CPU: clear them. */
		void flushDenormals() {
			for (int k = 0; k < N; k++) {
				if (Math.abs(yL1[k]) < 1e-15 && Math.abs(yL2[k]) < 1e-15) yL1[k] = yL2[k] = 0;
				if (Math.abs(yR1[k]) < 1e-15 && Math.abs(yR2[k]) < 1e-15) yR1[k] = yR2[k] = 0;
			}
		}
	}

	/** A small Schroeder reverb (four damped combs, two allpasses per ear), so it is not bone dry. */
	static final class Verb {
		private final double[][] cL, cR, aL, aR;
		private final int[] icL = new int[4], icR = new int[4], iaL = new int[2], iaR = new int[2];
		private final double[] fL = new double[4], fR = new double[4];
		double fb = 0.8, damp = 0.4, l, r;

		Verb(double sr) {
			double s = sr / 44100;
			cL = bufs(s, 1116, 1188, 1277, 1356);
			cR = bufs(s, 1139, 1211, 1300, 1379);
			aL = bufs(s, 556, 441);
			aR = bufs(s, 579, 464);
		}

		private static double[][] bufs(double s, int... n) {
			double[][] out = new double[n.length][];
			for (int i = 0; i < n.length; i++) out[i] = new double[Math.max(1, (int) Math.round(n[i] * s))];
			return out;
		}

		private double comb(double[] buf, int[] idx, double[] f, int j, double x) {
			int i = idx[j];
			double y = buf[i];
			f[j] = y * (1 - damp) + f[j] * damp;
			buf[i] = x + f[j] * fb;
			idx[j] = i + 1 >= buf.length ? 0 : i + 1;
			return y;
		}

		private static double allpass(double[] buf, int[] idx, int j, double x) {
			int i = idx[j];
			double b = buf[i], y = b - x;
			buf[i] = x + b * 0.5;
			idx[j] = i + 1 >= buf.length ? 0 : i + 1;
			return y;
		}

		void run(double x) {
			double ol = 0, or = 0;
			for (int j = 0; j < 4; j++) {
				ol += comb(cL[j], icL, fL, j, x);
				or += comb(cR[j], icR, fR, j, x);
			}
			for (int j = 0; j < 2; j++) {
				ol = allpass(aL[j], iaL, j, ol);
				or = allpass(aR[j], iaR, j, or);
			}
			l = ol;
			r = or;
		}
	}
}
