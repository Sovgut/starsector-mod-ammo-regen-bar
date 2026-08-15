# Releasing

A release is cut by pushing a `v*` tag. `.github/workflows/release.yml` packages
the mod and publishes a GitHub release with `AmmoRegenBar.zip` attached, using
the token every workflow run gets for free. No personal access token and no
`gh` login are needed on your machine.

## The steps

```sh
# 1. bump the version in BOTH places, they must agree with the tag
#      mod_info.json          "version":"1.3.0"
#      ammoRegenBar.version   "modVersion": { "major": 1, "minor": 3, "patch": 0 }

# 2. rebuild, so the committed jar matches the sources
./package.sh

# 3. commit the jar together with the change
git commit -am "feat: what changed"

# 4. tag and push
git tag -a v1.3.0 -m "Ammo Regen Bar 1.3.0"
git push origin main --follow-tags
```

The tag message becomes the release notes body on GitHub, so write it for
players rather than for the diff.

## What the workflow does, and what it deliberately does not

It checks out the tag, verifies the version, zips the mod and publishes. It
**does not compile**.

Compilation needs `starfarer.api.jar` and the other jars from a Starsector
install. Those are proprietary and must not be committed, so a clean runner
cannot build this mod. The compiled `jars/AmmoRegenBar.jar` is versioned in the
repository instead, and the workflow ships whatever is committed.

The honest consequence: **forget to rebuild before tagging and the release goes
out with stale code under a fresh version number.** Nothing in CI can catch that,
because catching it would require compiling. Step 2 above exists for this reason.

What CI *can* catch, and does, is the realistic version of that mistake: tagging
without bumping. The workflow refuses to publish unless the tag, `mod_info.json`
and `ammoRegenBar.version` all state the same version.

```
tag=1.2.1  mod_info=1.2.1  version file=1.2.1        -> proceeds
tag=9.9.9  mod_info=1.2.1  version file=1.2.1        -> fails, nothing published
```

## The archive layout matters

The zip holds one top level folder, `AmmoRegenBar/`, so extracting it into the
game's `mods/` directory installs the mod as is.

The asset name is fixed: `ammoRegenBar.version` declares

```
"directDownloadURL": ".../releases/latest/download/AmmoRegenBar.zip"
```

which is the URL the Version Checker mod fetches to offer an in-game update.
Rename the asset and that link breaks silently. If you change it, change both.

## Fixing a bad release

Tags are the trigger, so re-pushing one re-runs the workflow. Delete the release
on GitHub first, otherwise `gh release create` fails on the existing tag:

```sh
git tag -d v1.3.0
git push origin :refs/tags/v1.3.0
# fix, commit
git tag -a v1.3.0 -m "..."
git push origin v1.3.0
```

Note that the workflow runs from the commit the tag points at, so a change to
the workflow file only takes effect once a tag includes it.

## Still open

`ammoRegenBar.version` has no `modThreadId`, because there is no forum thread
yet. Version Checker simply skips the forum link while it is absent. Add the
numeric id from the thread URL once the mod is announced.
