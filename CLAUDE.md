# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Paintsprout is a digital art app that aims to feel like real paper and canvas —
spectral pigment mixing, watercolor washes, material surfaces, true-to-life
physical sizes. It is **WYSIWYG in a strict sense**: a physical artist using a
digital canvas, not a digital artist using computer tools. A feature that gives
the artist powers they would not have with real media on a real surface is a
candidate for deferral, not for the backlog. Canvas zoom/pan/rotate are deferred
on exactly this ground — you turn the tablet instead.

`docs/tool-ideas.md` is the status-tracked backlog and carries the "For
consideration" list of things held back by that philosophy.

## Repository layout

Monorepo with two apps:

| Path | State |
|---|---|
| `apps/paintsprout_flutter` | The original Flutter implementation. **Frozen reference** — do not develop here. Last touched at the monorepo restructure. |
| `apps/paintsprout_android` | The live app. Kotlin, Android **View system** (XML + viewBinding, no Compose), AGSL shaders. |

Kotlin sources and doc comments cite the Flutter files they were ported from
(`drawing_canvas.dart`, `stroke.dart`, `surface.dart`, `tools.dart`). Those
citations are still useful for understanding a port decision; they are not a
statement that the Flutter side is maintained.

`docs/` holds the long-form references: `soil-format.md` (what is actually on
disk), `file-format-plan.md` (the reasoning behind it), `backup.md`,
`tool-ideas.md`.

## The live app

Commands, build gotchas and the architecture notes for `apps/paintsprout_android`
live in `apps/paintsprout_android/CLAUDE.md`, which loads when a session works
under that directory. `apps/paintsprout_onyx/CLAUDE.md` does the same for the
Onyx rebuild.

**Installing to a device: use the `device-build-install` skill.** It covers both
apps, has the serials and the signing step, and knows that the debug build is a
*different app* with its own library and recovery key. Never run
`./gradlew installDebug` (it pushes to every attached device).

## Conventions

**Commit messages are a single plain sentence, no prefix, no type tag, roughly
under 78 characters, written in the artist's terms rather than the code's** —
"A mark begins where the pen crosses onto the page", "Put it back where it was",
"A deleted layer goes on a shelf, not in the bin". Match that voice.

**Comments explain why, at length, and in the same register.** This codebase
documents the decision and the failure it avoids, not the mechanism — see
`PaintOp.kt`, `PageSpace.kt`, `Focus.kt` for the house style. A change that
reverses one of those decisions should update the comment that argued for it.

Work proceeds in **named phases**, each ending in a merge to `main`. The
per-phase reasoning is kept in the memory directory rather than here.
