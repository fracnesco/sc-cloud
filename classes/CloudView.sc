// =====================================================================
// CloudView - Cloud's display: the buffer, where the grains are read from
// and at what pitch, and the grains themselves on a map of size and rate
//
// Two panes, each titled:
//
//   cloud    time goes from now, on the right, back to 4 seconds ago on the
//            left (the recent past wider); pitch goes up, in semitones. The
//            box is where the settings let grains fall (position to
//            position + spray, pitch ± spread), with the steps they are held
//            to; the dots are grains drawn from the same numbers, each with a
//            streak from where it started to where it reads now - hollow
//            ones play backwards, and a grain reading silence is dim. Along
//            the bottom the buffer: what went in while the window was open
//            (the input and the feedback), the write head at now - it stops,
//            orange, while frozen. A dashed line carries the feedback back.
//   grains   grain size along, grains a second up, both on log scales: the
//            dot is the setting, its bar how far vary lets the size stray,
//            the diagonals how many grains sound at once (under 1 there are
//            gaps), the shaded corner where the server holds the rate down.
//
// It says what the cloud is doing; it does not report each grain the
// server makes.
//
//   drag the box               along: the position, up and down: the pitch
//   drag its left edge         the spray
//   drag its top or bottom     the spread
//   the wheel                  the spray (alt, or on the top or bottom edge: the spread)
//   drag the dot (grains)      along: the size, up and down: grains a second
//   the wheel there            the vary
//   shift                      fine
//   double click               back to the default of what is under it
// =====================================================================

CloudView : FxParamView {
	var <>panel, <hot, hover;
	// the buffer as it was written: a level per cell, the head's time, the last reply
	var hist, histT = 0, lastArrive;
	// a drag going on, and the modifiers of the last wheel
	var drag, wheelMod = 0;
	// the panes as last laid out
	var px0 = 34, px1 = 100, py0 = 24, py1 = 100, sy0 = 100, sy1 = 120, gx0 = 200, gx1 = 300, gy0 = 24, gy1 = 100, split = 150;

	*minHeight { ^230 }

	// 4 s in cells of 20 ms
	*histCells { ^200 }
	*cellDur { ^Cloud.maxTime / this.histCells }
	// the time axis is log(ago + this): the last second gets most of the width
	*timeOffset { ^0.08 }
	*pitchRange { ^36 }
	*sizeRange { ^#[0.005, 1] }
	*rateRange { ^#[0.5, 200] }

	// the parameters the display draws: hovering their knobs lights their part here
	*hotKeys { ^#[\position, \scatter, \pitch, \pitchScatter, \quantize, \backwards, \grains, \grainSize, \sizeScatter, \chaos, \feedback, \freezeOn] }

	init { |argUnit, parent|
		super.init(argUnit, parent);
		// the base drops the modifiers of a wheel: alt-wheel is the spread here
		view.mouseWheelAction = { |v, x, y, mod, xd, yd|
			this.prMeasure;
			wheelMod = mod;
			if (unit.notNil) { this.prWheel(x, y, yd) };
			true
		};
		view.mouseLeaveAction = { this.prHover(nil) };
		^this
	}

	hot_ { |key| if (hot != key) { hot = key; this.refresh } }

	// ------------------------------------------------------------ the axes

	prLayout {
		var sh;
		this.prMeasure;
		split = ((bw - 14) * 0.68).round.max(120);
		px0 = 34;
		px1 = (split - 10).max(px0 + 40);
		py0 = 26;
		sy1 = bh - 18;
		sh = ((bh - 50) * 0.2).round.clip(26, 64);
		sy0 = sy1 - sh;
		py1 = (sy0 - 8).max(py0 + 30);
		gx0 = split + 14 + 30;
		gx1 = (bw - 10).max(gx0 + 30);
		gy0 = 26;
		gy1 = bh - 18;
	}

	prTimeFrac { |ago|
		var o = this.class.timeOffset;
		^(log(ago.clip(0, Cloud.maxTime) + o) - log(o)) / (log(Cloud.maxTime + o) - log(o))
	}

	prAgoOfFrac { |f|
		var o = this.class.timeOffset;
		^(exp(log(o) + (f.clip(0, 1) * (log(Cloud.maxTime + o) - log(o)))) - o).max(0)
	}

	prX { |ago| ^px1 - (this.prTimeFrac(ago) * (px1 - px0)) }
	prAgo { |x| ^this.prAgoOfFrac((px1 - x) / (px1 - px0).max(1)) }
	prY { |st| var r = this.class.pitchRange; ^st.linlin(r.neg, r, py1, py0, \minmax) }
	prSizeX { |s| var r = this.class.sizeRange; ^s.explin(r[0], r[1], gx0, gx1, \minmax) }
	prRateY { |g| var r = this.class.rateRange; ^g.explin(r[0], r[1], gy1, gy0, \minmax) }

	// the rate the server really runs: it gives way when the grains are too long
	*effectiveRate { |unit| ^unit.get(\grains).min(Cloud.maxGrains * 0.7 / unit.get(\grainSize)) }

	prStep { ^Cloud.quantizeSteps[#[\free, \semitone, \fifth, \octave].indexOf(unit.get(\quantize)) ? 0] }

	prFrozen { ^unit.get(\freezeOn) == true }

	// the box: [left, top, right, bottom]
	prBox {
		var pos = unit.get(\position), sc = unit.get(\scatter), p = unit.get(\pitch), sp = unit.get(\pitchScatter);
		var x1 = this.prX(pos + sc), x2 = this.prX(pos), y1 = this.prY(p + sp), y2 = this.prY(p - sp);
		if ((x2 - x1) < 3) { x1 = x2 - 3 };
		if ((y2 - y1) < 4) { y1 = ((y1 + y2) * 0.5) - 2; y2 = y1 + 4 };
		^[x1, y1, x2, y2]
	}

	// ------------------------------------------------------- the live part

	// on every meter reply: the head moves on (unless frozen) and every cell
	// it passed gets the peak of what was written since the last reply (now:
	// the reply's time, Main.elapsedTime unless a check says otherwise)
	levelsArrived { |now|
		var ex = unit !? { unit.extras } ? [], cells = this.class.histCells;
		var cell = this.class.cellDur, level, frozen, dt, i0, i1;
		if (unit.isNil) { ^this };
		now = now ? Main.elapsedTime;
		hist = hist ?? { Array.fill(cells, 0) };
		level = (ex[1] ? unit.inLevel ? 0).max(0);
		frozen = if (ex.size > 0) { ex[0] > 0.5 } { this.prFrozen };
		dt = (now - (lastArrive ? now)).clip(0, 0.25);
		lastArrive = now;
		if (frozen.not) {
			i0 = (histT / cell).floor.asInteger;
			histT = histT + dt;
			i1 = (histT / cell).floor.asInteger;
			(i1 - i0).clip(0, cells).do { |j| hist[(i0 + 1 + j) % cells] = level };
			hist[i1 % cells] = hist[i1 % cells].max(level);
		};
	}

	// what the buffer holds at this many seconds ago (an amplitude), or nil before any reply
	levelAt { |ago|
		var i;
		if (hist.isNil) { ^nil };
		i = ((histT - ago) / this.class.cellDur).floor;
		if (i < 0) { ^0 };
		^hist[i.asInteger % this.class.histCells]
	}

	// ------------------------------------------------------------ the grains

	// A cheap hash: the same grain gets the same numbers on every frame
	*hash { |k, j| ^(sin((k * 12.9898) + (j * 78.233)) * 43758.5453).frac }

	// The grains sounding now, drawn from the same numbers as the server's: a
	// pure function of the time, so a still picture shows a steady cloud and a
	// running one moves. Two channels, each its own grains.
	prGrains { |now, frozen|
		var g = [], rate = this.class.effectiveRate(unit), size = unit.get(\grainSize), vary = unit.get(\sizeScatter);
		var chaos = unit.get(\chaos), pitch = unit.get(\pitch), spread = unit.get(\pitchScatter), step = this.prStep;
		var back = unit.get(\backwards), pos = unit.get(\position), sc = unit.get(\scatter);
		var glow = 0.25, longest = (size * (2 ** vary)).max(0.08) + glow, chans = 2;
		var k1 = (now * rate).floor, k0 = k1 - (rate * longest).ceil - 1, stride;
		stride = (((k1 - k0 + 1) * chans) / 240).ceil.max(1);
		forBy(k0, k1 + 1, stride) { |k|
			chans.do { |ch|
				var h = { |j| this.class.hash(k + (ch * 0.5), j) };
				var born = (k + (ch / chans) + (chaos * (h.(0) - 0.5))) / rate;
				var age = now - born, dur, life, semis, b, speed, margin, ago0, drift, ago;
				if (age >= 0) {
					dur = size * (2 ** ((h.(1) * 2 - 1) * vary));
					life = dur.max(0.08);
					if (age < (life + glow)) {
						semis = (pitch + ((h.(2) * 2 - 1) * spread)).round(step);
						b = h.(3) < back;
						speed = 2 ** (semis / 12);
						margin = ((speed - 1).max(0) * dur) + 0.002;
						ago0 = (pos + (h.(4) * sc) + margin).min(Cloud.maxTime - (dur * speed));
						drift = (if (frozen) { 0 } { 1 }) - (speed * (if (b) { -1 } { 1 }));
						ago = (ago0 + (age.min(dur) * drift)).clip(0, Cloud.maxTime);
						g = g.add((ago0: ago0, ago: ago, st: semis, back: b, dur: dur,
							fade: if (age < life) { 1 } { 1 - ((age - life) / glow) }));
					};
				};
			};
		};
		^g
	}

	// ------------------------------------------------------------ drawing

	prDraw {
		var now = Main.elapsedTime, frozen = this.prFrozen, id = unit.class.identityColour ? FxPalette.accent;
		var lit = this.prLitParts, box;
		this.prLayout;
		box = this.prBox;
		this.prDrawCloudGrid(frozen);
		this.prDrawBuffer(frozen, id, box, lit);
		this.prDrawBox(id, box, lit);
		this.prDrawGrains(now, frozen, id);
		this.prDrawFeedback(frozen, id, box, lit);
		this.prDrawHead(frozen, lit);
		this.prDrawCloudCaption(frozen);
		// the line between the panes
		Pen.fillColor = FxPalette.waveDim;
		Pen.fillRect(Rect(split + 6, 8, 1, bh - 16));
		this.prDrawMap(id, lit);
	}

	// what is lit: the part under the pointer, and the part a hovered knob draws
	prLitParts {
		var parts = [];
		hover !? { |h| parts = parts.add(if ((h == \spreadUp) or: { h == \spreadDown }) { \spread } { h }) };
		drag !? { |d| parts = parts.add(if ((d[\part] == \spreadUp) or: { d[\part] == \spreadDown }) { \spread } { d[\part] }) };
		hot !? { |k|
			parts = parts ++ switch (k,
				\position, { [\move] }, \pitch, { [\move] }, \scatter, { [\spray] }, \pitchScatter, { [\spread] },
				\quantize, { [\steps] }, \backwards, { [\back] },
				\grains, { [\map] }, \grainSize, { [\map] }, \sizeScatter, { [\vary] }, \chaos, { [\map] },
				\feedback, { [\feedback] }, \freezeOn, { [\head] },
				{ [] })
		};
		^parts
	}

	prDrawCloudGrid { |frozen|
		var small = FxPalette.smallFont, r = this.class.pitchRange;
		// pitch, every octave
		(r.neg, r.neg + 12 .. r).do { |st|
			var y = this.prY(st);
			Pen.fillColor = if (st == 0) { FxPalette.fade(FxPalette.dim, 0.45) } { FxPalette.fade(FxPalette.waveDim, 0.55) };
			Pen.fillRect(Rect(px0, y.round - 0.5, px1 - px0, 1));
			Pen.stringRightJustIn(case { st == 0 } { "0 st" } { st > 0 } { "+" ++ st } { st.asString },
				Rect(0, y - 7, px0 - 6, 14), small, FxPalette.dim);
		};
		// time
		#[4, 2, 1, 0.5, 0.25, 0.1, 0].do { |a, i|
			var x = this.prX(a), str = case
				{ a == 0 } { if (frozen) { "frozen" } { "now" } }
				{ a >= 1 } { if (a == 4) { "4 s ago" } { "% s".format(a.asInteger) } }
				{ true } { "% ms".format((a * 1000).round.asInteger) };
			if (a > 0) {
				Pen.fillColor = FxPalette.fade(FxPalette.waveDim, 0.45);
				Pen.fillRect(Rect(x.round - 0.5, py0, 1, sy1 - py0));
			};
			Pen.stringCenteredIn(str, Rect(case { a == 4 } { x - 2 } { a == 0 } { x - 58 } { true } { x - 30 }, sy1 + 3, 60, 12),
				small, if ((a == 0) and: { frozen }) { FxPalette.accent } { FxPalette.dim });
		};
	}

	prDrawBuffer { |frozen, id, box, lit|
		var r = Rect(px0, sy0, px1 - px0, sy1 - sy0), mid = sy0 + ((sy1 - sy0) * 0.5), half = (sy1 - sy0) * 0.5 - 2;
		var pts = [], has = hist.notNil;
		Pen.fillColor = FxPalette.bg;
		Pen.fillRect(r);
		// the part of the buffer the grains read
		Pen.fillColor = FxPalette.fade(id, if (lit.includes(\move) or: { lit.includes(\buffer) }) { 0.3 } { 0.18 });
		Pen.fillRect(Rect(box[0], sy0, box[2] - box[0], sy1 - sy0));
		if (has) {
			((px1 - px0) / 2).floor.asInteger.do { |i|
				var x = px0 + (i * 2), lv = this.levelAt(this.prAgo(x)) ? 0;
				pts = pts.add(x @ (lv.max(1e-5).ampdb.linlin(-60, 0, 0, half, \minmax)));
			};
			Pen.fillColor = FxPalette.fade(FxPalette.wave, 0.45);
			Pen.moveTo(px0 @ mid);
			pts.do { |p| Pen.lineTo(p.x @ (mid - p.y)) };
			pts.reverseDo { |p| Pen.lineTo(p.x @ (mid + p.y)) };
			Pen.fill;
			Pen.strokeColor = FxPalette.fade(FxPalette.wave, 0.8);
			Pen.width = 1;
			pts.do { |p, i| if (i == 0) { Pen.moveTo(p.x @ (mid - p.y)) } { Pen.lineTo(p.x @ (mid - p.y)) } };
			Pen.stroke;
		} {
			Pen.fillColor = FxPalette.fade(FxPalette.waveDim, 0.8);
			Pen.fillRect(Rect(px0, mid.round, px1 - px0, 1));
			Pen.stringCenteredIn("what goes into the buffer shows here while it plays", r, FxPalette.smallFont, FxPalette.dim);
		};
		Pen.fillColor = FxPalette.fade(FxPalette.bg, 0.75);
		Pen.fillRect(Rect(px0, sy0, 40, 14));
		Pen.stringAtPoint("buffer", (px0 + 4) @ (sy0 + 1), FxPalette.smallFont, FxPalette.dim);
	}

	prDrawBox { |id, box, lit|
		var x1 = box[0], y1 = box[1], x2 = box[2], y2 = box[3], step = this.prStep;
		var p = unit.get(\pitch), sp = unit.get(\pitchScatter), pxSt = (py1 - py0) / (this.class.pitchRange * 2);
		var moving = lit.includes(\move), small = FxPalette.smallFont;
		Pen.fillColor = FxPalette.fade(id, if (moving) { 0.2 } { 0.12 });
		Pen.fillRect(Rect(x1, y1, x2 - x1, y2 - y1));
		Pen.strokeColor = FxPalette.fade(id, 0.9);
		Pen.width = if (moving) { 2.5 } { 1.5 };
		Pen.addRect(Rect(x1, y1, x2 - x1, y2 - y1));
		Pen.stroke;
		// the edges that drag on their own
		Pen.fillColor = id;
		if (lit.includes(\spray)) { Pen.fillRect(Rect(x1 - 2, y1 - 1, 4, (y2 - y1) + 2)) };
		if (lit.includes(\spread)) { Pen.fillRect(Rect(x1, y1 - 2, x2 - x1, 4)); Pen.fillRect(Rect(x1, y2 - 2, x2 - x1, 4)) };
		// the steps a grain's pitch is held to, labelled while there is room
		if (step >= 1) {
			((p - sp) / step).ceil.asInteger.max(this.class.pitchRange.neg div: step)
			.for(((p + sp) / step).floor.asInteger.min(this.class.pitchRange div: step)) { |k|
				var st = k * step, y = this.prY(st);
				Pen.fillColor = FxPalette.fade(id, if (lit.includes(\steps)) { 0.85 } { 0.4 });
				Pen.fillRect(Rect(x1, y.round - 0.5, x2 - x1, 1));
				if ((step * pxSt) >= 12) {
					Pen.stringAtPoint(case { st > 0 } { "+" ++ st } { true } { st.asString }, (x2 + 4) @ (y - 6), small,
						FxPalette.fade(id, 0.9));
				};
			};
		};
	}

	prDrawGrains { |now, frozen, id|
		var grains = this.prGrains(now, frozen), d = unit.get(\grainSize).explin(0.005, 1, 3, 9);
		var lit = this.prLitParts.includes(\back);
		grains.do { |g|
			var x = this.prX(g[\ago]), x0 = this.prX(g[\ago0]), y = this.prY(g[\st]);
			var lv = this.levelAt(g[\ago]), a = g[\fade] * (lv !? { |l| l.max(1e-5).ampdb.linlin(-48, -6, 0.25, 1) } ? 1);
			if ((x - x0).abs > 1.5) {
				Pen.strokeColor = FxPalette.fade(id, 0.35 * a);
				Pen.width = 1;
				Pen.line(x0 @ y, x @ y);
				Pen.stroke;
			};
			if (g[\back]) {
				Pen.strokeColor = FxPalette.fade(if (lit) { FxPalette.text } { id }, a);
				Pen.width = 1.5;
				Pen.strokeOval(Rect(x - (d * 0.5), y - (d * 0.5), d, d));
			} {
				Pen.fillColor = FxPalette.fade(id, a);
				Pen.fillOval(Rect(x - (d * 0.5), y - (d * 0.5), d, d));
			};
		};
	}

	prDrawFeedback { |frozen, id, box, lit|
		var fb = unit.get(\feedback), from, to, mid;
		if ((fb < 0.005) or: { frozen }) { ^this };
		from = box[2] @ ((box[1] + box[3]) * 0.5);
		to = (px1 - 4) @ (sy0 - 2);
		Pen.strokeColor = FxPalette.fade(id, (0.25 + (fb * 0.7)) * if (lit.includes(\feedback)) { 1.3 } { 1 });
		Pen.width = if (lit.includes(\feedback)) { 2 } { 1.25 };
		Pen.lineDash = FloatArray[4, 3];
		Pen.moveTo(from);
		Pen.quadCurveTo(to, to.x @ from.y);
		Pen.stroke;
		Pen.lineDash = FloatArray[1, 0];
		Pen.fillColor = FxPalette.fade(id, 0.25 + (fb * 0.7));
		Pen.moveTo((to.x - 4) @ (to.y - 6));
		Pen.lineTo((to.x + 4) @ (to.y - 6));
		Pen.lineTo(to.x @ to.y);
		Pen.fill;
		mid = ((from.x + to.x) * 0.5);
		if ((to.x - from.x) > 70) {
			Pen.stringAtPoint("feedback " ++ FxPalette.format(fb, "", \pct), (mid - 30) @ (from.y - 14), FxPalette.smallFont,
				FxPalette.fade(id, 0.9));
		};
	}

	prDrawHead { |frozen, lit|
		var c = if (frozen) { FxPalette.accent } { FxPalette.wave }, mid = sy0 + ((sy1 - sy0) * 0.5);
		Pen.fillColor = FxPalette.fade(c, if (frozen or: { lit.includes(\head) }) { 1 } { 0.7 });
		Pen.fillRect(Rect(px1 - 1, py0, 2, sy1 - py0));
		Pen.fillOval(Rect(px1 - 5, mid - 5, 10, 10));
		if (frozen) {
			// stopped: two bars on the head
			Pen.fillColor = FxPalette.bg;
			Pen.fillRect(Rect(px1 - 2.5, mid - 3, 1.5, 6));
			Pen.fillRect(Rect(px1 + 1, mid - 3, 1.5, 6));
		};
	}

	prDrawCloudCaption { |frozen|
		var pos = unit.get(\position), sc = unit.get(\scatter), q = unit.get(\quantize);
		var where = if (sc < 0.0005) { FxPalette.format(pos, "s") ++ " ago" } {
			"% – % ago".format(FxPalette.format(pos, "s"), FxPalette.format(pos + sc, "s"))
		};
		var sp = unit.get(\pitchScatter), pitch = FxPalette.format(unit.get(\pitch), "st", nil, 1, true)
			++ if (sp >= 0.05) { " ± " ++ FxPalette.format(sp, "st", nil, 1) } { "" };
		var str = "reads %  ·  %".format(where, pitch) ++ if (q != \free) { "  ·  in %s".format(q) } { "" };
		Pen.stringAtPoint("cloud", 8 @ 4, FxPalette.font, FxPalette.text);
		Pen.stringAtPoint(str, 48 @ 6, FxPalette.smallFont, FxPalette.dim);
		Pen.stringRightJustIn(if (frozen) { "frozen" } { "listening" }, Rect(px1 - 120, 5, 120, 12), FxPalette.smallFont,
			if (frozen) { FxPalette.accent } { FxPalette.dim });
	}

	// ------------------------------------------------------------- the map

	prDrawMap { |id, lit|
		var small = FxPalette.smallFont, rr = this.class.rateRange, sr = this.class.sizeRange;
		var size = unit.get(\grainSize), rate = unit.get(\grains), eff = this.class.effectiveRate(unit), vary = unit.get(\sizeScatter);
		var x, y, xa, xb, d, capped = eff < (rate * 0.999), cap = Cloud.maxGrains * 0.7, corner;
		var diag = { |k| [this.prSizeX((k / rr[1]).max(sr[0])) @ this.prRateY((k / sr[0]).min(rr[1])).max(gy0),
			this.prSizeX((k / rr[0]).min(sr[1])) @ this.prRateY((k / sr[1]).max(rr[0]))] };
		// the grid
		#[1, 10, 100].do { |g|
			y = this.prRateY(g);
			Pen.fillColor = FxPalette.fade(FxPalette.waveDim, 0.55);
			Pen.fillRect(Rect(gx0, y.round - 0.5, gx1 - gx0, 1));
			Pen.stringRightJustIn(g.asString, Rect(gx0 - 32, y - 7, 26, 14), small, FxPalette.dim);
		};
		Pen.stringRightJustIn("/s", Rect(gx0 - 32, gy0 - 2, 26, 12), small, FxPalette.dim);
		#[0.01, 0.1, 1].do { |s|
			x = this.prSizeX(s);
			Pen.fillColor = FxPalette.fade(FxPalette.waveDim, 0.55);
			Pen.fillRect(Rect(x.round - 0.5, gy0, 1, gy1 - gy0));
			Pen.stringCenteredIn(if (s == 1) { "1 s" } { FxPalette.format(s, "s") }, Rect(if (s == 1) { x - 44 } { x - 30 }, gy1 + 3, 60, 12),
				small, FxPalette.dim);
		};
		// where the server holds the rate down: grains x size over its limit
		corner = diag.(cap);
		Pen.fillColor = FxPalette.fade(FxPalette.dim, 0.14);
		Pen.moveTo(corner[0]);
		Pen.lineTo(gx1 @ gy0);
		Pen.lineTo(corner[1]);
		Pen.fill;
		// how many at once: 1 (under it, gaps) and 8
		[1, 8].do { |k|
			var ends = diag.(k);
			Pen.strokeColor = FxPalette.fade(id, 0.35);
			Pen.width = 1;
			Pen.line(ends[0], ends[1]);
			Pen.stroke;
			Pen.stringAtPoint("% at once".format(k), (ends[1].x - 46) @ (ends[1].y - 14), small, FxPalette.fade(id, 0.7));
		};
		Pen.stringAtPoint("gaps", (this.prSizeX(0.007)) @ (this.prRateY(1.2)), small, FxPalette.dim);
		Pen.stringAtPoint("grainy", (this.prSizeX(0.012)) @ (this.prRateY(120)), small, FxPalette.dim);
		Pen.stringAtPoint("smooth", (this.prSizeX(0.2)) @ (this.prRateY(150)), small, FxPalette.dim);
		// the setting: vary as a bar, the dot where it runs (a ghost where it was asked, if held down)
		x = this.prSizeX(size);
		y = this.prRateY(eff);
		xa = this.prSizeX(size * (2 ** vary.neg));
		xb = this.prSizeX(size * (2 ** vary));
		if (vary > 0.001) {
			Pen.fillColor = FxPalette.fade(id, if (lit.includes(\vary)) { 1 } { 0.75 });
			Pen.fillRect(Rect(xa, y - 1, xb - xa, 2));
			Pen.fillRect(Rect(xa - 0.5, y - 5, 1.5, 10));
			Pen.fillRect(Rect(xb - 1, y - 5, 1.5, 10));
		};
		if (capped) {
			Pen.strokeColor = FxPalette.fade(id, 0.5);
			Pen.width = 1;
			Pen.strokeOval(Rect(x - 6, this.prRateY(rate) - 6, 12, 12));
		};
		d = if (lit.includes(\map)) { 18 } { 14 };
		Pen.fillColor = id;
		Pen.fillOval(Rect(x - (d * 0.5), y - (d * 0.5), d, d));
		if (lit.includes(\map)) {
			Pen.strokeColor = FxPalette.text;
			Pen.width = 1.5;
			Pen.strokeOval(Rect(x - (d * 0.5) - 3, y - (d * 0.5) - 3, d + 6, d + 6));
		};
		// the caption
		Pen.stringAtPoint("grains", (split + 18) @ 4, FxPalette.font, FxPalette.text);
		Pen.stringAtPoint("% at once".format(FxPalette.fixed(eff * size, if ((eff * size) < 10) { 1 } { 0 })) ++ if (capped) { "  ·  held to " ++ FxPalette.sig3(eff) ++ " /s" } { "" },
			(split + 66) @ 6, small, FxPalette.dim);
	}

	// ------------------------------------------------------------- the mouse

	// what is under the pointer: \move, \spray, \spreadUp, \spreadDown, \buffer, \map or nil
	prPart { |x, y|
		var b, thin, narrow;
		this.prLayout;
		if (x > (split + 8)) { ^if ((x >= (gx0 - 8)) and: { y >= (gy0 - 8) } and: { y <= (gy1 + 4) }) { \map } };
		if ((x < (px0 - 4)) or: { x > (px1 + 6) } or: { y < (py0 - 6) } or: { y > sy1 }) { ^nil };
		if (y >= (sy0 - 2)) { ^\buffer };
		b = this.prBox;
		thin = (b[3] - b[1]) < 14;
		narrow = (b[2] - b[0]) < 12;
		if ((y >= (b[1] - 5)) and: { y <= (b[3] + 5) }) {
			if (narrow) {
				if ((x < b[0]) and: { x >= (b[0] - 7) }) { ^\spray }
			} {
				if ((x - b[0]).abs <= 4) { ^\spray }
			};
		};
		if ((x >= b[0]) and: { x <= b[2] }) {
			if (thin) {
				if ((y < b[1]) and: { y >= (b[1] - 7) }) { ^\spreadUp };
				if ((y > b[3]) and: { y <= (b[3] + 7) }) { ^\spreadDown };
			} {
				if ((y - b[1]).abs <= 4) { ^\spreadUp };
				if ((y - b[3]).abs <= 4) { ^\spreadDown };
			};
		};
		^\move
	}

	prSet { |key, v|
		var e = unit.class.param(key), spec = e[\spec];
		unit.set(key, spec.constrain(v));
	}

	prDefault { |key| ^unit.class.param(key)[\default] }

	prMouseDown { |x, y, mod, count|
		var part = this.prPart(x, y);
		drag = nil;
		if (part.isNil) { ^this };
		if (count == 2) {
			switch (part,
				\move, { this.prSet(\position, this.prDefault(\position)); this.prSet(\pitch, this.prDefault(\pitch)) },
				\buffer, { this.prSet(\position, this.prDefault(\position)) },
				\spray, { this.prSet(\scatter, this.prDefault(\scatter)) },
				\spreadUp, { this.prSet(\pitchScatter, this.prDefault(\pitchScatter)) },
				\spreadDown, { this.prSet(\pitchScatter, this.prDefault(\pitchScatter)) },
				\map, { this.prSet(\grains, this.prDefault(\grains)); this.prSet(\grainSize, this.prDefault(\grainSize)) }
			);
			^this
		};
		drag = (part: part).putAll(this.prAnchor(x, y, mod));
		this.refresh;
	}

	// where a drag starts from: the pointer and the values; again when shift changes
	prAnchor { |x, y, mod|
		^(x: x, y: y, shift: mod.isShift, position: unit.get(\position), scatter: unit.get(\scatter),
			pitch: unit.get(\pitch), spread: unit.get(\pitchScatter), grains: this.class.effectiveRate(unit),
			size: unit.get(\grainSize))
	}

	prMouseMove { |x, y, mod|
		var f, dx, dy, pw, ph, st, r;
		if (drag.isNil) { ^this };
		if (mod.isShift != drag[\shift]) { drag.putAll(this.prAnchor(x, y, mod)) };
		f = if (drag[\shift]) { 0.1 } { 1 };
		dx = (x - drag[\x]) * f;
		dy = (y - drag[\y]) * f;
		pw = (px1 - px0).max(1);
		ph = (py1 - py0).max(1);
		st = this.class.pitchRange * 2 / ph;
		switch (drag[\part],
			\move, {
				this.prSet(\position, this.prAgoOfFrac(this.prTimeFrac(drag[\position]) - (dx / pw)));
				this.prSet(\pitch, (drag[\pitch] - (dy * st)).round(if (drag[\shift]) { 0.01 } { 0.1 }));
			},
			\buffer, { this.prSet(\position, this.prAgoOfFrac(this.prTimeFrac(drag[\position]) - (dx / pw))) },
			\spray, {
				this.prSet(\scatter, this.prAgoOfFrac(this.prTimeFrac(drag[\position] + drag[\scatter]) - (dx / pw)) - unit.get(\position))
			},
			\spreadUp, { this.prSet(\pitchScatter, (drag[\spread] - (dy * st)).round(0.1)) },
			\spreadDown, { this.prSet(\pitchScatter, (drag[\spread] + (dy * st)).round(0.1)) },
			\map, {
				r = this.class.sizeRange;
				this.prSet(\grainSize, drag[\size] * ((r[1] / r[0]) ** (dx / (gx1 - gx0).max(1))));
				r = this.class.rateRange;
				this.prSet(\grains, drag[\grains] * ((r[1] / r[0]) ** (dy.neg / (gy1 - gy0).max(1))));
			}
		);
	}

	prMouseUp { drag = nil; this.refresh }

	prWheel { |x, y, delta|
		var part = this.prPart(x, y), s = delta.sign, v;
		if ((part.isNil) or: { s == 0 }) { ^this };
		case
		{ part == \map } { this.prSet(\sizeScatter, (unit.get(\sizeScatter) + (s * 0.1)).round(0.01)) }
		{ wheelMod.isAlt or: { part == \spreadUp } or: { part == \spreadDown } } {
			this.prSet(\pitchScatter, (unit.get(\pitchScatter) + s).round(0.1))
		}
		{ true } {
			v = unit.get(\scatter);
			this.prSet(\scatter, (v + (s * (v * 0.1).max(0.01))).round(0.001))
		};
	}

	// the knobs a part plays light up while the pointer is over it
	prHover { |part|
		var keys = [], ws = panel !? { panel.widgets };
		if (part == hover) { ^this };
		hover = part;
		keys = switch (part,
			\move, { #[\position, \pitch] },
			\buffer, { #[\position] },
			\spray, { #[\scatter] },
			\spreadUp, { #[\pitchScatter] },
			\spreadDown, { #[\pitchScatter] },
			\map, { #[\grains, \grainSize, \sizeScatter] },
			{ [] }
		);
		ws !? { this.class.hotKeys.do { |k| ws[k] !? { |w| w.tryPerform(\highlight_, keys.includes(k)) } } };
		this.refresh;
	}

	hint { |x, y|
		var part;
		if (x.isNil or: { unit.isNil }) {
			^"the grains: now on the right, 4 seconds ago on the left, pitch up - drag the box for where and how high,"
			" its edges for spray and spread; the grains map: drag the dot for size and rate"
		};
		part = this.prPart(x, y);
		this.prHover(part);
		^switch (part,
			\move, { "the grain window: grains are read from % back, at % - drag it along and up and down (shift: fine),"
				" the wheel: spray (alt: spread), double click: back to % and 0 st".format(
					FxPalette.format(unit.get(\position), "s"), FxPalette.format(unit.get(\pitch), "st", nil, 1, true),
					FxPalette.format(this.prDefault(\position), "s")) },
			\spray, { "spray %: how much further back a grain may start - drag the edge (shift: fine), double click: back to %".format(
				FxPalette.format(unit.get(\scatter), "s"), FxPalette.format(this.prDefault(\scatter), "s")) },
			\spreadUp, { this.prSpreadHint },
			\spreadDown, { this.prSpreadHint },
			\buffer, { "the buffer: what went in while this window was open, the write head at now%; the shaded part"
				" is where the grains read - drag along for the position".format(
					if (this.prFrozen) { " (frozen: it stands still and the grains read what it holds)" } { "" }) },
			\map, { "grains % a second, % long, % at once%: drag the dot along for the size, up for more a second (shift: fine);"
				" the wheel: vary; double click: back to 24 /s, 140 ms".format(
					FxPalette.sig3(this.class.effectiveRate(unit)), FxPalette.format(unit.get(\grainSize), "s"),
					FxPalette.sig3(this.class.effectiveRate(unit) * unit.get(\grainSize)),
					if (this.class.effectiveRate(unit) < (unit.get(\grains) * 0.999)) {
						" (held down from %: the server plays at most % grains at once)".format(FxPalette.sig3(unit.get(\grains)), (Cloud.maxGrains * 0.7).round.asInteger)
					} { "" }) },
			{ nil }
		)
	}

	prSpreadHint {
		^"spread %: how far a grain's pitch may stray either way - drag the edge (shift: fine), the wheel too, double click: none".format(
			FxPalette.format(unit.get(\pitchScatter), "st", nil, 1))
	}
}


// =====================================================================
// CloudPanel - the device panel (sc fx kit) as every effect has it, plus:
// the display is given the panel, so hovering a part of it lights the knobs
// that set it, and hovering a knob lights its part of the display.
//
//   Cloud.panelClass       // CloudPanel: unit.gui and unit.panel use it
// =====================================================================

CloudPanel : FxDevicePanel {

	init { |argUnit, parent|
		super.init(argUnit, parent);
		display !? { |d| d.tryPerform(\panel_, this) };
	}

	hints_ { |bar|
		super.hints_(bar);
		if (bar.isNil or: { display.isNil } or: { display.respondsTo(\hot_).not }) { ^this };
		widgets.keysValuesDo { |key, w|
			if (CloudView.hotKeys.includes(key)) { this.prAttachHot(bar, key, w) };
		};
	}

	// a widget that also lights its part of the display while the pointer is on it
	prAttachHot { |bar, key, w|
		var e = cls.param(key);
		bar.attach(w.view, {
			display !? { |d| d.hot = key };
			if (w.isKindOf(FxKnob)) { "%  %   -   %".format(w.label, w.text, e[\hint]) } { e[\hint] }
		});
		w.view.mouseLeaveAction = { display !? { |d| d.hot = nil } };
	}
}
