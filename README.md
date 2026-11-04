# Cloud

**A live granulator - what you play turns into a cloud of itself, and freeze keeps it there - running anywhere a UGen does, with a window.** A SuperCollider extension.

```supercollider
s.boot;
Ndef(\gtr, { Cloud.ar(Pluck.ar(WhiteNoise.ar(0.2 ! 2), Impulse.kr(1), 0.02, 0.005, 2), \cloud) }).play;

c = Cloud(Ndef(\gtr), \cloud);
c.gui;
c.density = 40; c.grainSize = 0.12;
c.pitchScatter = 7; c.quantize = \fifth;
c.frozen = true;                  // stop listening, keep reading
c.preset = 'shimmer haze';
```

Every effect of this family lives in the same three places. Write `Cloud.ar` into a graph of your own and drive it from the object, as above; give it a node of its own on a bus, `Cloud.onBus(0, 2).enabled_(true)`; or put it in a chain, `FxChain.master.add(Cloud())`. If SuperDirtGUI is installed, it is an insert on an orbit too: `~sdg.addInsert(0, \cloud)`.

It is built on [FxKit](../sc%20fx%20kit), which is built on [ParaEQ](../sc%20EQ), and the class library will not compile without them; the installer links both when the folders sit side by side. It uses SuperCollider's own UGens, not sc3-plugins.

Written for SuperCollider 3.13 on macOS. Nothing in it is platform specific apart from the symlink in `install.scd`, which Linux shares and Windows does not.

## Install

### As a quark

```supercollider
Quarks.install("https://github.com/fracnesco/sc-cloud.git");
thisProcess.recompile;
```

The manifest names `sc-fx-kit` as a dependency, which names `sc-eq`, so `Quarks` fetches those first. The quark goes by the name of the repository, `sc-cloud`; the class inside it is `Cloud`.

### From a clone

Clone `sc-eq` into a folder called `sc EQ`, `sc-fx-kit` into `sc fx kit` and this into `sc cloud`, side by side. Open `install.scd` and evaluate its first block, then recompile the class library. It links what is missing and tells you what it did.

On Windows there is no `ln -s`, so use the quark, or copy the three folders into the extensions folder by hand: `Platform.userExtensionDir.openOS` opens it.

Install it one way or the other, **not both**: two copies on the class path is a duplicate class error at compile time.

### Check that it took

```supercollider
Cloud.presetNames;
Cloud.onBus(0, 2).enabled_(true).gui;
```

Select `Cloud` and press cmd/ctrl + d for the help file, and see [examples/cloud-demo.scd](examples/cloud-demo.scd) for a walkthrough you can evaluate a block at a time.

## What it does

The input goes into 4 seconds of buffer per channel, and grains of it are read back: from `position` seconds ago, give or take `scatter`, at a `pitch`, give or take `pitchScatter`, optionally held to a scale by `quantize`. `reverse` says how many play backwards. `chaos` goes from grains like clockwork, which gives the cloud a pitch of its own, to grains at random.

`feedback` puts the cloud back in the buffer, so it can grow out of itself. `frozen` stops writing, and what is in there goes on being read for as long as you like.

Each channel has its own buffer and its own grains, so a stereo cloud is wide. A forward grain faster than the write head starts far enough back never to catch it up, which is the one thing that would otherwise click. There can be 256 grains at once on a channel: `density` gives way if the grains are too long for that.

Each instance takes about 1.5 MB of the server's real time memory at stereo, more for its grains. A stock server has 8 MB and does not fit it at 12 channels (`alloc failed` in the post window, and no sound), so several of them, or a wide bus, call for `s.options.memSize = 8192 * 32` before booting.

## The window

`c.gui` opens the top row (on, presets, save, copy, paste), the display, and a row of knobs per section. Hover over anything and the bar at the bottom says what it does.

In the display, drag left and right for the position, up and down for the pitch; the wheel for the scatter in time. The dots are drawn from the settings: they say what the cloud is doing, they do not report each grain the server makes.

## Presets

Built in: default, fine mist, shimmer haze, stone rain, backwards, frozen pad, fifths tower, clockwork, octave down.

Yours go in `Platform.userAppSupportDir/cloud-presets.scd`: `c.saveAsPreset("mine")`, or type a name in the window and press save. `c.copySettings` and `other.pasteSettings` carry settings from one to another.

## Parameters

Every parameter is a property: `c.density`, `c.density = ...`. These also work by name, `c.set(\density, ...)`, `c.get(\density)`. Knobs clip to their range. This table is generated from the class.

| parameter | section | range | default | what it does |
|---|---|---|---|---|
| `density` | grains | 0.5 .. 200 /s | 24 | how many grains a second start |
| `grainSize` | grains | 0.005 .. 1 s | 0.14 | how long a grain lasts |
| `sizeScatter` | grains | 0 .. 3 oct | 0 | how far a grain's length may stray, in octaves |
| `chaos` | grains | 0 .. 1 | 0.5 | 0 starts them like clockwork, a pitch of its own; 1 at random |
| `position` | grains | 0 .. 3.5 s | 0.2 | how long ago the grains are read from |
| `scatter` | grains | 0 .. 3.5 s | 0.3 | how much further back they may stray |
| `frozen` | grains | on / off | false | stop listening: go on reading what is in the buffer |
| `pitch` | pitch | -24 .. 24 st | 0 | every grain, in semitones |
| `pitchScatter` | pitch | 0 .. 24 st | 0 | how far a grain's pitch may stray |
| `quantize` | pitch | free semitone fifth octave | free | hold each grain's pitch to steps of this |
| `reverse` | pitch | 0 .. 1 | 0 | how many grains play backwards |
| `feedback` | pitch | 0 .. 0.98 | 0 | how much of the cloud goes back in the buffer |
| `damp` | pitch | 200 .. 20000 Hz | 16000 | a low pass on the cloud |
| `width` | pitch | 0 .. 2 | 1 | how far apart the sides are |
| `mix` | pitch | 0 .. 1 | 0.5 | how much of the effect goes out, against the dry signal |
| `output` | pitch | -36 .. 24 dB | 0 | the level of the whole thing, in dB |

## Files

| file | what it is |
|---|---|
| [classes/Cloud.sc](classes/Cloud.sc) | the parameters, the presets, the DSP |
| [classes/CloudView.sc](classes/CloudView.sc) | the window's display |
| [HelpSource/Classes/Cloud.schelp](HelpSource/Classes/Cloud.schelp) | the help file |
| [HelpSource/Classes/CloudView.schelp](HelpSource/Classes/CloudView.schelp) | the help file for the display |
| [examples/cloud-demo.scd](examples/cloud-demo.scd) | a walkthrough, one block at a time |
| [install.scd](install.scd) | the symlink, a check, an uninstall |
| [sc-cloud.quark](sc-cloud.quark) | the quark manifest |

## License

MIT - see [LICENSE](LICENSE).
