"""Boilerplate SFX synthesis: a pneumatic tube thunk, and a three-part gantry motor.

The gantry's three clips have to splice sample-accurately, so everything steady is built to be
*exactly* periodic over the loop length:

  - tonal partials are integer multiples of a fundamental chosen so the loop holds a whole number
    of cycles, which makes the loop's end phase identical to its start phase;
  - the noise beds are synthesised in the frequency domain from integer-index bins only, so the
    buffer is periodic by construction rather than crossfaded into looking that way.

`start` then ends on that same phase and on the loop's own preceding noise samples, and `end`
begins on both - so start->loop, loop->loop and loop->end are all continuous.
"""
import numpy as np

SR = 44100
rng = np.random.default_rng(0xB01)


def periodic_noise(n, lo, hi, rolloff=0.0, seed=0):
	"""Noise of exactly `n` samples that tiles seamlessly: only integer-index bins are populated."""
	gen = np.random.default_rng(seed)
	bins = n // 2 + 1
	freqs = np.arange(bins) * SR / n
	mag = np.zeros(bins)
	band = (freqs >= lo) & (freqs <= hi)
	mag[band] = 1.0
	if rolloff:  # gentle tilt across the band, in dB per octave
		with np.errstate(divide="ignore"):
			octaves = np.log2(np.maximum(freqs, 1.0) / max(lo, 1.0))
		mag *= 10 ** (rolloff * octaves / 20.0)
	phase = gen.uniform(0, 2 * np.pi, bins)
	phase[0] = 0.0
	spec = mag * np.exp(1j * phase)
	out = np.fft.irfft(spec, n)
	peak = np.max(np.abs(out))
	return out / peak if peak > 0 else out


def env(n, attack, decay, curve=2.0):
	"""Percussive envelope: a short linear attack into an exponential decay."""
	a = max(1, int(attack * SR))
	e = np.ones(n)
	e[:a] = np.linspace(0, 1, a)
	t = np.arange(n - a) / SR
	e[a:] = np.exp(-t / decay) ** curve
	return e


def thunk(f0, decay, seed, bright=1.0):
	"""One tube impact: a click transient, the tube's own damped modes, and a little air."""
	n = int(0.22 * SR)
	t = np.arange(n) / SR
	out = np.zeros(n)

	# The tube body. A tube open at both ends resonates on whole harmonics; the upper modes are
	# quieter and die faster, which is what makes it read as hollow rather than as a drum.
	for k, (amp, dmul) in enumerate([(1.0, 1.0), (0.42, 0.62), (0.2, 0.4), (0.09, 0.28)], start=1):
		out += amp * np.sin(2 * np.pi * f0 * k * t + rng.uniform(0, 6.28)) * env(n, 0.0006, decay * dmul)

	# The attack itself - a few milliseconds of band-passed noise. Without this the impact has no
	# edge and reads as a hum rather than a hit.
	click = periodic_noise(n, 900, 7000 * bright, rolloff=-3, seed=seed + 1)
	out += 0.55 * click * env(n, 0.0002, 0.006, curve=1.4)

	# Pneumatic air, brief and quiet - the sense that the tube moved something rather than was hit.
	air = periodic_noise(n, 400, 3200, rolloff=-6, seed=seed + 2)
	out += 0.13 * air * env(n, 0.004, 0.045, curve=1.0)

	return out / np.max(np.abs(out)) * 0.85


# ── the gantry ────────────────────────────────────────────────────────────────────────────────
LOOP_SECONDS = 1.0
LOOP_N = int(LOOP_SECONDS * SR)
F0 = 112.0          # whole cycles per loop, so the wrap is phase-continuous
AM_HZ = 7.0         # also whole cycles per loop - the rail passing over sleepers

MOTOR_PARTIALS = [(1, 0.55), (2, 0.30), (3, 0.16), (4, 0.09), (6, 0.05), (8, 0.03)]


def motor_tone(phase):
	"""The motor's harmonic stack at an arbitrary instantaneous phase (radians of the fundamental)."""
	return sum(a * np.sin(k * phase) for k, a in MOTOR_PARTIALS)


def mix(tone, rumble, hiss):
	"""The one place the three beds are balanced, so every clip is built from the same recipe."""
	return 0.62 * tone + 0.5 * rumble + 0.1 * hiss


def gantry_loop():
	t = np.arange(LOOP_N) / SR
	phase = 2 * np.pi * F0 * t
	am = 1.0 + 0.12 * np.sin(2 * np.pi * AM_HZ * t)
	tone = motor_tone(phase) * am
	rumble = periodic_noise(LOOP_N, 40, 260, rolloff=-4, seed=11) * (1.0 + 0.25 * np.sin(2 * np.pi * AM_HZ * t))
	hiss = periodic_noise(LOOP_N, 2200, 11000, rolloff=-7, seed=12)
	raw = mix(tone, rumble, hiss)
	# The gain is measured here and reused verbatim by the spin-up and spin-down. Deriving it
	# twice is what put the three clips on different levels and left an audible step at the
	# loop->stop splice, which is the one seam a listener would actually hear.
	gain = 0.72 / np.max(np.abs(raw))
	return raw * gain, rumble, hiss, gain


LOOP, LOOP_RUMBLE, LOOP_HISS, LOOP_GAIN = gantry_loop()


def gantry_start(seconds=0.30):
	"""Spin-up. Ends on the loop's own opening phase and on the noise samples that precede it.

	Six client ticks, not twelve: a gantry crossing a short warehouse is done in well under a second,
	and a spin-up longer than the trip means the motor is still winding up as the head arrives. The
	sweep covers the same range in half the time, which is the steepness - and the exponent is lower
	as well, so most of the rise happens in the first third rather than being spread evenly.
	"""
	n = int(seconds * SR)
	t = np.arange(n) / SR
	ramp = (t / t[-1]) ** 0.5
	freq = F0 * (0.32 + 0.68 * ramp)
	phase = 2 * np.pi * np.cumsum(freq) / SR
	phase -= phase[-1]  # land on the loop's phase 0; a constant offset keeps it continuous
	am = 1.0 + 0.12 * np.sin(2 * np.pi * AM_HZ * t)
	tone = motor_tone(phase) * am * ramp

	# The noise beds are the loop's own, taken from the samples immediately *before* its origin -
	# which, the buffer being periodic, is its tail. Splices into the loop with no seam.
	rumble = np.roll(LOOP_RUMBLE, n)[:n] if n <= LOOP_N else np.resize(LOOP_RUMBLE, n)
	hiss = np.roll(LOOP_HISS, n)[:n] if n <= LOOP_N else np.resize(LOOP_HISS, n)

	out = mix(tone, rumble * ramp, hiss * ramp)
	# The clutch engaging - the same impact model as the pipe, pitched down and damped harder.
	# Truncated to the clip rather than assumed to fit: the impact is a fixed 0.22s and the clip is
	# now shorter than that was written to assume.
	engage = thunk(96.0, 0.075, seed=90, bright=0.7) * 0.5
	k = min(len(engage), n)
	out[:k] += engage[:k]
	return out * LOOP_GAIN


def gantry_end(seconds=0.35):
	"""Spin-down. Begins on the loop's opening phase and noise, so it follows a whole iteration.

	Seven client ticks, down from fifteen, and a steeper curve within them - see [gantry_start] for
	why. A stop that outlasts the move it belongs to is worse than a short one: the head is already
	parked and loading while the motor is still audibly winding down.
	"""
	n = int(seconds * SR)
	t = np.arange(n) / SR
	fall = (1 - t / t[-1]) ** 1.9
	freq = F0 * (0.22 + 0.78 * fall)
	phase = 2 * np.pi * np.cumsum(freq) / SR
	am = 1.0 + 0.12 * np.sin(2 * np.pi * AM_HZ * t)
	tone = motor_tone(phase) * am * fall

	rumble = np.resize(LOOP_RUMBLE, n) * fall
	hiss = np.resize(LOOP_HISS, n) * fall
	out = mix(tone, rumble, hiss) * LOOP_GAIN
	# Settling into the stop, once the motor is most of the way down. Damped harder and placed
	# earlier than it was, so that it still has room to decay inside a clip this short - a settle
	# truncated mid-ring reads as the sound being cut off rather than as the machine stopping.
	settle = thunk(84.0, 0.05, seed=93, bright=0.55) * 0.42
	at = int(n * 0.5)
	out[at:at + len(settle)] += settle[:n - at]
	return out


def write(path, data, fade_tail=True):
	"""Writes 16-bit mono.

	`fade_tail` guards against a click on truncation, so it is only for clips that already end in
	silence. The loop must keep its last sample to tile, and the spin-up must keep its last sample
	because that sample *is* the handoff into the loop - fading either one manufactures the very
	seam this whole construction exists to avoid.
	"""
	from scipy.io import wavfile
	d = np.clip(data, -1.0, 1.0).copy()
	if fade_tail:
		f = min(int(0.008 * SR), len(d) // 4)
		d[-f:] *= np.linspace(1, 0, f)
	wavfile.write(path, SR, (d * 32767).astype(np.int16))
	return len(d) / SR


import sys, os
out = sys.argv[1]
os.makedirs(out, exist_ok=True)
report = []
for i, (f0, dec, seed) in enumerate([(232.0, 0.085, 1), (214.0, 0.10, 21), (251.0, 0.072, 41)], start=1):
	report.append((f"pipe_thunk{i}", write(f"{out}/pipe_thunk{i}.wav", thunk(f0, dec, seed))))
report.append(("gantry_start", write(f"{out}/gantry_start.wav", gantry_start(), fade_tail=False)))
report.append(("gantry_loop", write(f"{out}/gantry_loop.wav", LOOP, fade_tail=False)))
report.append(("gantry_stop", write(f"{out}/gantry_stop.wav", gantry_end())))

# Seam check: the loop wrapping onto itself, and the two splices.
seam = abs(LOOP[-1] - LOOP[0])
s, e = gantry_start(), gantry_end()
print(f"loop wrap discontinuity : {seam:.5f}   (0 = sample-accurate)")
print(f"start->loop step        : {abs(s[-1] - LOOP[0]):.5f}")
print(f"loop->stop step         : {abs(LOOP[-1] - e[0]):.5f}")
for name, dur in report:
	print(f"  {name:<14} {dur:.3f}s")
