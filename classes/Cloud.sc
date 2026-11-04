// =====================================================================
// Cloud - a live granulator: what you play turns into a cloud of itself
//
// The input goes into a few seconds of buffer, and grains of it are read back:
// from `position` seconds ago, give or take `scatter`; at a pitch, give or take
// `pitchScatter`, optionally held to a scale; some of them backwards. Feedback
// puts the grains back in the buffer, so a cloud can grow out of itself. Freeze
// stops writing, and what is in there goes on being read.
//
//   Ndef(\gtr, { Cloud.ar(Pluck.ar(WhiteNoise.ar(0.2 ! 2), Impulse.kr(1), 0.02, 0.005, 2), \cloud) }).play;
//   c = Cloud(Ndef(\gtr), \cloud);
//   c.gui;
//   c.density = 40; c.grainSize = 0.12; c.pitchScatter = 7; c.quantize = \fifth;
//   c.frozen = true;
//   c.preset = 'shimmer haze';
//
// Each channel has its buffer and its own grains, so a stereo cloud is wide.
// Up to 256 grains at once a channel: density gives way if the grains are too
// long for that. 4 seconds of stereo is 1.5 MB of the server's memory.
// =====================================================================

Cloud : FxParamUnit {
	classvar <>maxTime = 4, <>maxGrains = 256;

	*controlName { ^\cloud }
	*hasTail { ^true }
	*displayClass { ^CloudView }
	*windowSize { ^Point(860, 500) }

	*params {
		^[
			this.knob(\density, 0.5, 200, \exp, 24, "/s", "density", "how many grains a second start", section: \grains),
			this.knob(\grainSize, 0.005, 1, \exp, 0.14, "s", "size", "how long a grain lasts", box: \ms, section: \grains),
			this.knob(\sizeScatter, 0, 3, \lin, 0, "oct", "vary", "how far a grain's length may stray, in octaves", section: \grains),
			this.knob(\chaos, 0, 1, \lin, 0.5, "", "chaos", "0 starts them like clockwork, a pitch of its own; 1 at random", box: \pct, section: \grains),
			this.knob(\position, 0, 3.5, \lin, 0.2, "s", "position", "how long ago the grains are read from", box: \ms, section: \grains),
			this.knob(\scatter, 0, 3.5, \lin, 0.3, "s", "scatter", "how much further back they may stray", box: \ms, section: \grains),
			this.toggle(\frozen, false, "freeze", "stop listening: go on reading what is in the buffer", \grains),

			this.knob(\pitch, -24, 24, \lin, 0, "st", "pitch", "every grain, in semitones", lag: 0, section: \pitch, dec: 1),
			this.knob(\pitchScatter, 0, 24, \lin, 0, "st", "scatter", "how far a grain's pitch may stray", lag: 0, section: \pitch, dec: 1),
			this.menu(\quantize, #[\free, \semitone, \fifth, \octave], \free, "scale", "hold each grain's pitch to steps of this", \pitch),
			this.knob(\reverse, 0, 1, \lin, 0, "", "reverse", "how many grains play backwards", box: \pct, section: \pitch),
			this.knob(\feedback, 0, 0.98, \lin, 0, "", "feedback", "how much of the cloud goes back in the buffer", box: \pct, section: \pitch),
			this.knob(\damp, 200, 20000, \exp, 16000, "Hz", "damp", "a low pass on the cloud", section: \pitch, dec: 0),
			this.knob(\width, 0, 2, \lin, 1, "", "width", "how far apart the sides are", box: \pct, section: \pitch),
			this.mixParams(\pitch, 0.5)
		]
	}

	*presetList {
		^[
			\default, (),
			'fine mist', (density: 80, grainSize: 0.05, scatter: 0.8, mix: 0.5),
			'shimmer haze', (density: 40, grainSize: 0.3, pitch: 12, pitchScatter: 12, quantize: \octave, feedback: 0.5, damp: 9000, mix: 0.5),
			'stone rain', (density: 12, grainSize: 0.02, chaos: 1, scatter: 2, pitchScatter: 24, mix: 0.6),
			'backwards', (density: 16, grainSize: 0.5, reverse: 1, position: 0.6, scatter: 1, mix: 0.7),
			'frozen pad', (density: 40, grainSize: 0.5, frozen: true, position: 1, scatter: 2, mix: 1, width: 1.5),
			'fifths tower', (density: 30, grainSize: 0.18, pitchScatter: 14, quantize: \fifth, feedback: 0.35, mix: 0.6),
			'clockwork', (density: 16, grainSize: 0.06, chaos: 0, position: 0.25, scatter: 0),
			'octave down', (density: 30, grainSize: 0.25, pitch: -12, scatter: 0.5, damp: 3000, mix: 0.7)
		]
	}

	// the steps quantize rounds to; free is as good as none
	*quantizeSteps { ^#[0.001, 1, 7, 12] }

	*process { |sig, p, ctx|
		var n = sig.size, frozen = Lag.kr(p[\frozen], 0.02);
		var loop = FxLoop(n, maxTime + 0.1, 1 - p[\frozen]);
		var frames = loop.frames, sr = SampleRate.ir;
		var duration = p[\grainSize];
		var density = p[\density].min(maxGrains * 0.7 / duration);
		var step = Select.kr(p[\quantize], this.quantizeSteps);
		var wet = n.collect { |i|
			var trig = Dust.ar(density * p[\chaos]) + Impulse.ar(density * (1 - p[\chaos]), i / n);
			var dur = duration * (2 ** TRand.ar(p[\sizeScatter].neg, p[\sizeScatter], trig));
			var semis = (p[\pitch] + TRand.ar(p[\pitchScatter].neg, p[\pitchScatter], trig)).round(step);
			var speed = 2 ** (semis / 12);
			var back = TRand.ar(0, 1, trig) < p[\reverse];
			// a forward grain faster than the head must start far enough back
			// never to catch it up; a backward one moves away from it
			var margin = ((speed - 1).max(0) * dur) + 0.002;
			var ago = (p[\position] + TRand.ar(0, p[\scatter], trig) + margin).min(maxTime - (dur * speed));
			var head = (loop.writePhase - (ago * sr)).wrap(0, frames);
			GrainBuf.ar(1, trig, dur, loop.buffers[i], speed * (1 - (back * 2)), head / frames, 2, 0, -1, maxGrains)
		};
		wet = LPF.ar(LeakDC.ar(wet), p[\damp]);
		loop.write(
			((sig + (wet * p[\feedback]).tanh) * (1 - frozen))
			+ (n.collect { |i| BufRd.ar(1, loop.buffers[i], loop.writePhase, 1, 1) } * frozen)
		);
		ctx[\extras] = [p[\frozen]];
		^FxKit.width(wet * 0.7, p[\width])
	}
}
