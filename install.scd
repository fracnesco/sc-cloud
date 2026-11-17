// Symlink this repository into the SuperCollider extensions folder.
// Evaluate the block below, then recompile the class library
// (Language -> Recompile Class Library, or ctrl/cmd + shift + L).
//
// This is the route for a clone you want to keep editing. If you would
// rather have it installed and updated for you, do not use this file -
// install it as a quark instead, from a fresh sclang:
//
//   Quarks.install("https://github.com/fracnesco/sc-cloud.git");
//   thisProcess.recompile;
//
// One or the other, not both: two copies on the class path is a
// duplicate class error at compile time.
//
// ln -s is macOS and Linux. On Windows, use the quark, or copy this
// folder into Platform.userExtensionDir by hand - .openOS opens it.
//
// Cloud is built on sc fx kit (../sc fx kit), so that has to be installed too.
// If it is sitting next to this folder, this script links it as well. An
// sc fx kit from before November 2026 does not hold FxUnit yet, and an sc EQ
// from before then holds it a second time: update both if you have them.

(
var link, src, parent, dst;

src = PathName(thisProcess.nowExecutingPath).pathOnly;
if (src.endsWith("/")) { src = src.drop(-1) };			// ln -s dislikes it
parent = PathName(src).parentPath;
dst = Platform.userExtensionDir +/+ "Cloud";

link = { |from, to, what, done|
	if (from.endsWith("/")) { from = from.drop(-1) };
	case
	{ File.exists(to) } {
		("Cloud: % is already installed at %.".format(what, to)).postln;
		done.value;
	}
	{ File.exists(from).not } {
		("Cloud: % is not installed, and there is no copy of it at %."
			"\n  The class library will not compile without it."
			"\n  Install it first, then run this again."
			.format(what, from)).warn;
	}
	{ true } {
		File.mkdir(Platform.userExtensionDir);
		("ln -s" + from.shellQuote + to.shellQuote).unixCmd({ |exit|
			if (exit == 0) {
				"Cloud: linked % -> %".format(what, to).postln;
				done.value;
			} {
				"Cloud: linking % failed with exit code %".format(what, exit).warn;
			};
		});
	};
};

// an sc EQ from before the framework moved holds it a second time
[parent +/+ "sc EQ", Platform.userExtensionDir +/+ "ParaEQ"].do { |dir|
	if (File.exists(dir +/+ "classes/FxUnit.sc")) {
		("Cloud: % is an older sc EQ that still holds FxUnit, which lives in sc fx kit"
			"\n  now. Update it (git pull) or the class library will not compile.".format(dir)).warn;
	};
};

// what it is built on first - without it the class library will not compile
link.value(parent +/+ "sc fx kit", Platform.userExtensionDir +/+ "FxKit", "FxKit", {
	link.value(src, dst, "Cloud", {
		"".postln;
		"Now recompile the class library: ctrl/cmd + shift + L".postln;
	});
});
)


// ---------------------------------------------------------------- verify
(
var missing = [\Cloud, \CloudView]
	.select { |sym| sym.asClass.isNil };

if (missing.notEmpty) {
	("Cloud is not compiled - % is missing."
		"\n  Recompile the class library, ctrl/cmd + shift + L, and check the"
		"\n  post window for compile errors - a missing or old FxKit is the usual reason."
		.format(missing.join(", "))).warn;
} {
	"Cloud is installed.".postln;
	"  presets: % - %".format(Cloud.presetNames.size, Cloud.presetNames.join(", ")).postln;
	"  in a graph:  Cloud.ar(sig, \\cloud)      then  Cloud(Ndef(\\x), \\cloud).gui".postln;
	"  on a bus:    Cloud.onBus(0, 2).enabled_(true).gui".postln;
	"  an insert:   ~sdg.addInsert(0, \\cloud)".postln;
	"  help:        Cloud, then cmd+d".postln;
};
)


// ------------------------------------------------------------- uninstall
(
var link = Platform.userExtensionDir +/+ "Cloud";
case
{ File.type(link) == \symlink } {
	File.delete(link);
	("Removed %."
		"\n  Recompile the class library to finish.".format(link)).postln;
}
{ File.type(link) == \not_found } {
	"Nothing to remove at %.".format(link).postln;
}
{ true } {
	"% is not a link, leaving it alone.".format(link).warn;
};
)
