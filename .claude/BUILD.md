# Building

```sh
./build.sh          # compiles src/ into jars/AmmoRegenBar.jar
./package.sh        # build.sh, then packs dist/AmmoRegenBar.zip
```

## What you need

- **A JDK 17 or newer.** The output is pinned to `--release 17` because the game
  launches its own bundled JRE 17 (`jre_linux/bin/java`), whatever JDK is
  installed on the machine. Build with a newer target and the game refuses to
  load the class.
- **A Starsector install**, for its jars. `build.sh` compiles against
  `starfarer.api.jar`, `lwjgl.jar`, `json.jar` and `log4j-1.2.9.jar` taken from
  the install directory.

By default the script assumes the repository sits in the game's `mods/` folder
and looks two levels up. Cloned anywhere else, point it at the install:

```sh
STARSECTOR_HOME=/path/to/Starsector ./build.sh
```

It stops with a clear message and exit code 1 if `starfarer.api.jar` is not
there, rather than producing a broken jar.

Those jars are proprietary Fractal Softworks files and are deliberately **not**
vendored here. That is also why CI does not compile: see `RELEASE.md`.

## Three constraints that shaped this

They are not style choices, and each one cost a debugging round to find.

### The mod ships a jar, not source scripts

Starsector can compile `.java` files under `data/scripts` at runtime with
Janino 2.7.8, and that is how this mod started. Janino resolves each file on its
own, so a class in a second file is not reliably visible from the first, and its
dialect is old (no lambdas, no diamond operator, no switch on strings). Shipping
a compiled jar removes the whole category. Keep the sources in `src/`, which the
game ignores.

### `java.lang.reflect` is unavailable

Mod classes are loaded through a class loader that refuses to hand out
`java.lang.reflect.Method`, `Field` and `Constructor`. From the in-game console
the refusal is explicit:

```
SecurityException: File access and reflection are not allowed to scripts
```

It applies to jar mods too, not only to `data/scripts`. Worse, merely
*importing* a blocked type makes Janino fail the compilation unit and report a
misleading error about an unrelated type.

The mod therefore fetches those classes through the bootstrap loader and drives
them with `java.lang.invoke`, which is not restricted. This is what every
reflecting mod in the ecosystem does. See the `reflection helpers` section of
`AmmoRegenBarPlugin`.

**If you touch that code, verify the compiled output afterwards:**

```sh
unzip -p jars/AmmoRegenBar.jar ammoregenbar/AmmoRegenBarPlugin.class \
  | strings | grep java/lang/reflect
```

It must print nothing. String literals with dots (`java.lang.reflect.Method`,
used by `Class.forName`) are fine; class references with slashes are what break
the mod at runtime, silently.

### Drawing happens inside the game's UI tree

`EveryFrameCombatPlugin.renderInUICoords()` draws *underneath* the vanilla HUD,
so anything painted there is covered by the weapon rows. The overlay is a
`CustomPanelAPI` added into the game's own UI tree with `addComponent` plus
`bringComponentToTop`, which is what puts it above the ammo bar.

## Verifying a change

`javac` and the game are the only real checks; there are no unit tests.

1. `./build.sh` - compiles clean.
2. The `java/lang/reflect` grep above - prints nothing.
3. Launch a battle and read `starsector.log`. Set `"debugLog": true` in
   `data/config/ammo_regen_bar.json` to get one line per attached overlay per
   second, including the anchor widget's coordinates.
4. `"debugLoud": true` paints the overlay magenta over the full ammo bar,
   ignoring ammo state, keeping the real geometry. Use it when the bar is in the
   wrong place, or missing, and you need to tell "not drawn" from "drawn
   somewhere else".

Both flags are re-read in battle with **ALT + R**, no restart needed.
