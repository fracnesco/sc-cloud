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
//   c.grains = 40; c.grainSize = 0.12; c.pitchScatter = 7; c.quantize = \fifth;
//   c.freezeOn = true;
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
		// labels, sections, sizes and hints are the window's (sc-fx-ui, 2026-11-09);
		// the order and the keys are the nodeMaps' - append, never reorder
		^[
			this.knob(\grains, 0.5, 200, \exp, 24, "/s", "grains",
				"how many grains a second start - or drag the dot in the display's grains map up and down",
				section: \grains, size: \big),
			this.knob(\grainSize, 0.005, 1, \exp, 0.14, "s", "size",
				"how long a grain lasts - or drag the dot in the grains map along",
				box: \ms, section: \grains, size: \big),
			this.knob(\sizeScatter, 0, 3, \lin, 0, "oct", "vary",
				"how far a grain's length may stray, in octaves either way - or the wheel on the grains map",
				section: \grains, size: \small, floorText: "off"),
			this.knob(\chaos, 0, 1, \lin, 0.5, "", "chaos",
				"0 starts the grains like clockwork, which gives the cloud a pitch of its own; 1 at random",
				box: \pct, section: \grains, size: \small),
			this.knob(\position, 0, 3.5, \lin, 0.2, "s", "position",
				"how long ago the grains are read from - or drag the box in the display along",
				box: \ms, section: \where),
			this.knob(\scatter, 0, 3.5, \lin, 0.3, "s", "spray",
				"how much further back a grain may start - or drag the box's left edge, or the wheel in the display",
				box: \ms, section: \where, size: \small),
			this.toggle(\freezeOn, false, "freeze",
				"stop listening: the buffer holds what is in it and the grains go on reading it - hold the pad for a moment's freeze",
				\buffer).put(\size, \big),

			this.knob(\pitch, -24, 24, \lin, 0, "st", "pitch",
				"every grain, in semitones - or drag the box in the display up and down",
				lag: 0, section: \pitch, dec: 1),
			this.knob(\pitchScatter, 0, 24, \lin, 0, "st", "spread",
				"how far a grain's pitch may stray either way - or drag the box's top or bottom edge",
				lag: 0, section: \pitch, dec: 1, size: \small),
			this.menu(\quantize, #[\free, \semitone, \fifth, \octave], \free, "steps",
				"hold each grain's pitch (and the pitch itself) to steps of a semitone, a fifth or an octave", \pitch),
			this.knob(\backwards, 0, 1, \lin, 0, "", "backwards",
				"how many grains play backwards - drawn hollow in the display",
				box: \pct, section: \pitch, size: \small),
			this.knob(\feedback, 0, 0.98, \lin, 0, "", "feedback",
				"how much of the cloud goes back into the buffer, so it grows out of itself - nothing while frozen",
				box: \pct, section: \buffer),
			this.knob(\damp, 200, 20000, \exp, 16000, "Hz", "damp", "a low pass on the cloud (and on what feedback puts back)",
				section: \output, dec: 0),
			this.knob(\width, 0, 2, \lin, 1, "", "width", "how far apart the sides are: 0 mono, 100 % as it is, 200 % wider",
				box: \pct, section: \output, bipolar: false),
			this.mixParams(\output, 0.5)
		]
	}

	*presetList {
		^[
			\default, (),
			'fine mist', (grains: 80, grainSize: 0.05, scatter: 0.8, mix: 0.5),
			'shimmer haze', (grains: 40, grainSize: 0.3, pitch: 12, pitchScatter: 12, quantize: \octave, feedback: 0.5, damp: 9000, mix: 0.5),
			'stone rain', (grains: 12, grainSize: 0.02, chaos: 1, scatter: 2, pitchScatter: 24, mix: 0.6),
			'backwards', (grains: 16, grainSize: 0.5, backwards: 1, position: 0.6, scatter: 1, mix: 0.7),
			'frozen pad', (grains: 40, grainSize: 0.5, freezeOn: true, position: 1, scatter: 2, mix: 1, width: 1.5),
			'fifths tower', (grains: 30, grainSize: 0.18, pitchScatter: 14, quantize: \fifth, feedback: 0.35, mix: 0.6),
			'clockwork', (grains: 16, grainSize: 0.06, chaos: 0, position: 0.25, scatter: 0),
			'octave down', (grains: 30, grainSize: 0.25, pitch: -12, scatter: 0.5, damp: 3000, mix: 0.7)
		]
	}

	// the steps quantize rounds to; free is as good as none
	*quantizeSteps { ^#[0.001, 1, 7, 12] }

	*process { |sig, p, ctx|
		var n = sig.size, frozen = Lag.kr(p[\freezeOn], 0.02);
		var loop = FxLoop(n, maxTime + 0.1, 1 - p[\freezeOn]);
		var frames = loop.frames, sr = SampleRate.ir;
		var duration = p[\grainSize];
		var density = p[\grains].min(maxGrains * 0.7 / duration);
		var step = Select.kr(p[\quantize], this.quantizeSteps), written;
		var wet = n.collect { |i|
			var trig = Dust.ar(density * p[\chaos]) + Impulse.ar(density * (1 - p[\chaos]), i / n);
			var dur = duration * (2 ** TRand.ar(p[\sizeScatter].neg, p[\sizeScatter], trig));
			var semis = (p[\pitch] + TRand.ar(p[\pitchScatter].neg, p[\pitchScatter], trig)).round(step);
			var speed = 2 ** (semis / 12);
			var back = TRand.ar(0, 1, trig) < p[\backwards];
			// a forward grain faster than the head must start far enough back
			// never to catch it up; a backward one moves away from it
			var margin = ((speed - 1).max(0) * dur) + 0.002;
			var ago = (p[\position] + TRand.ar(0, p[\scatter], trig) + margin).min(maxTime - (dur * speed));
			var head = (loop.writePhase - (ago * sr)).wrap(0, frames);
			GrainBuf.ar(1, trig, dur, loop.buffers[i], speed * (1 - (back * 2)), head / frames, 2, 0, -1, maxGrains)
		};
		wet = LPF.ar(LeakDC.ar(wet), p[\damp]);
		written = sig + (wet * p[\feedback]).tanh;
		loop.write(
			(written * (1 - frozen))
			+ (n.collect { |i| BufRd.ar(1, loop.buffers[i], loop.writePhase, 1, 1) } * frozen)
		);
		// for the display only: frozen, and the peak of what goes into the buffer
		// since the last meter reply (the input and the feedback)
		ctx[\extras] = [p[\freezeOn], Peak.ar(written, Impulse.kr(FxParamUnit.meterRate)).reduce(\max)];
		^FxKit.width(wet * 0.7, p[\width])
	}
}
