// =====================================================================
// CloudView - Cloud's display: where the grains come from, and at what pitch
//
// Time goes from now, on the right, back to 4 seconds ago on the left; pitch goes
// up. The box is where the settings let grains fall, and the dots in it are
// grains drawn from the same numbers: it says what the cloud is doing, it does
// not report each grain the server makes.
//
//   drag left and right    the position
//   drag up and down       the pitch
//   the wheel              the scatter in time
// =====================================================================

CloudView : FxParamView {
	var grains, lastTime, dragging = false;

	*minHeight { ^170 }

	prXOfAgo { |ago| ^ago.linlin(0, Cloud.maxTime, bw - 2, 2, \minmax) }
	prYOfPitch { |st| ^st.linlin(-36, 36, bh - 2, 2, \minmax) }

	prSpawn { |now|
		var steps = Cloud.quantizeSteps, step = steps[#[\free, \semitone, \fifth, \octave].indexOf(unit.quantize)];
		var dt = (now - (lastTime ? now)).clip(0, 0.2);
		var count = (unit.density * dt).round.asInteger.clip(0, 12);
		lastTime = now;
		grains = (grains ? []).select { |g| (now - g[\born]) < g[\life] };
		count.do {
			grains = grains.add((
				born: now,
				life: unit.grainSize.clip(0.05, 1),
				ago: unit.position + (unit.scatter.rand),
				st: (unit.pitch + unit.pitchScatter.rand2).round(step),
				back: unit.reverse.coin
			));
		};
	}

	prDraw {
		var now = Main.elapsedTime, x1, x2, y1, y2;
		var frozen = unit.frozen;
		this.prSpawn(now);
		Pen.fillColor = FxPalette.bg;
		Pen.fillRect(Rect(0, 0, bw, bh));
		x1 = this.prXOfAgo(unit.position + unit.scatter);
		x2 = this.prXOfAgo(unit.position);
		y1 = this.prYOfPitch(unit.pitch + unit.pitchScatter);
		y2 = this.prYOfPitch(unit.pitch - unit.pitchScatter);
		Pen.fillColor = (if (frozen) { FxPalette.accent } { FxPalette.wave }).copy.alpha_(0.12);
		Pen.fillRect(Rect(x1, y1 - 3, (x2 - x1).max(2), (y2 - y1).max(0) + 6));
		Pen.strokeColor = FxPalette.waveDim;
		Pen.line(0 @ this.prYOfPitch(0), bw @ this.prYOfPitch(0));
		Pen.stroke;
		grains.do { |g|
			var age = (now - g[\born]) / g[\life];
			var alpha = (1 - age).clip(0, 1);
			var size = unit.grainSize.explin(0.005, 1, 4, 16) * (0.6 + (age * 0.4));
			var x = this.prXOfAgo(g[\ago]), y = this.prYOfPitch(g[\st]);
			Pen.fillColor = (if (g[\back]) { FxPalette.eqBand(5) } { if (frozen) { FxPalette.accent } { FxPalette.wave } }).copy.alpha_(alpha);
			Pen.fillOval(Rect(x - (size * 0.5), y - (size * 0.5), size, size));
		};
		Pen.fillColor = if (frozen) { FxPalette.dim } { FxPalette.good };
		Pen.fillRect(Rect(bw - 2, 0, 2, bh));
		this.prCaption(if (frozen) { "frozen" } { "now" }, bw - 44, 4);
		this.prCaption("% grains/s, % ms".format(unit.density.round(0.1), (unit.grainSize * 1000).round(1)));
	}

	prMouseDown { |x, y, mod, count|
		dragging = true;
		this.prMouseMove(x, y, mod);
	}

	prMouseMove { |x, y, mod|
		if (dragging) {
			unit.position = x.linlin(2, bw - 2, Cloud.maxTime, 0, \minmax);
			unit.pitch = y.linlin(2, bh - 2, 36, -36, \minmax).round(if (mod.isShift) { 1 } { 0.1 });
		};
	}

	prMouseUp { dragging = false }

	prWheel { |x, y, delta| unit.scatter = unit.scatter + (delta.sign * 0.02) }

	hint { ^"the grains: now on the right, 4 seconds ago on the left, pitch up - drag for the position and the pitch, the wheel for the scatter" }
}
