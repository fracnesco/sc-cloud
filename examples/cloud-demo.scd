// Cloud - a walkthrough, one block at a time
//
// Evaluate each ( ... ) block in turn with the cursor inside it. This is
// an editor document, not a file to .load.

// ---- 1. a sound, and Cloud driven from its Ndef
(
s.boot;
)

(
Ndef(\gtr, { Cloud.ar(Pluck.ar(WhiteNoise.ar(0.2 ! 2), Impulse.kr(1), 0.02, 0.005, 2), \cloud) }).play;
~c = Cloud(Ndef(\gtr), \cloud);
~c.gui;
)

// ---- 2. settings: each is a property, each moves the window
(
~c.density = 40; ~c.grainSize = 0.12;
~c.pitchScatter = 7; ~c.quantize = \fifth;
~c.frozen = true;                  // stop listening, keep reading
~c.preset = 'shimmer haze';
)

// ---- 3. every preset; evaluate one at a time
~c.preset = \default;
~c.preset = 'fine mist';
~c.preset = 'shimmer haze';
~c.preset = 'stone rain';
~c.preset = \backwards;
~c.preset = 'frozen pad';
~c.preset = 'fifths tower';
~c.preset = \clockwork;
~c.preset = 'octave down';

// ---- 4. settings kept, and carried
(
~c.saveAsPreset("demo");
~c.copySettings;
Cloud.presetNames.postln;
Cloud.removePreset("demo");
)

// ---- 5. the same effect with a node of its own, on the main out
(
~own = Cloud.onBus(0, 2);
~own.pasteSettings;
~own.enabled = true;
~own.gui;
)

(
~own.free;
)

// ---- 6. tidy up
(
~c.free;
Ndef(\gtr).clear;
)
