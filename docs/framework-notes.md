# Framework notes

Findings about VexelRay, vexelray-gui, Kronometer, tactroller and atchung that came out of building
**Maistro** — things the framework does not have, does not document, or does in a way that cost time to
discover.

**Why this file exists.** An application built on a framework is the only place its gaps are visible, and they
are visible exactly once: at the moment they are worked around. A workaround with no note beside it becomes a
piece of application code nobody can tell from a design decision, and the framework never hears about it. So
the rule is to write the note *when the workaround is written*, not in a retrospective, and to write down what
was measured rather than what was assumed.

**Before writing a workaround, ask whether it is a component.** If the answer is "every project on this
framework will write these same four calls" — that is a finding, and the fix belongs upstream. Say so here
with that framing, so the retrospective has a candidate rather than a complaint.

## How to write one

    ## FN-1 · One line saying what is missing 🔬
    
    What was wanted, what the framework offers instead, and what was done about it.
    Then: what it costs, and what the framework could do about it.

The markers are a filter, not decoration:

| | |
| --- | --- |
| 🔬 | a framework gap — something upstream could fix |
| 💡 | an idea, not yet a finding |
| 📋 | carried over: needs re-verifying against a newer build before it is repeated |

## Findings

## FN-1 · A `Picture` has no regions a click or a script can land on 🔬

The mind map is drawn as one `Picture`, and a node in it had to be clickable, and clickable *by name*, because
`ottermate` targets nodes, not coordinates. A `Sketch` mark has a `tag`, but it only reaches SVG as a CSS class; it
is not hit-tested and not in the semantic snapshot. So `MapPane` floats one transparent `box()` over each drawn
node, gives it a hidden label for its name (as `IconButton` does), a role and a landmark, and keeps the boxes in step
with the drawing by hand: made, moved and dropped as the graph changes.

What it costs: about 50 lines of bookkeeping that every application drawing a graph, a chart or a canvas would write
again, and two copies of every node's geometry that are kept in agreement only by computing both from the same
numbers. What the framework could do: let a `Sketch` mark carry an id, and make each id'd mark a hit region with a
name and a landmark in the semantic snapshot, so `gui.onClick(stage, e -> ...)` hands back the mark's id.

## FN-2 · Placing a node at a pixel position you measured is not documented 🔬

`Length` has no pixel unit, on purpose, and its javadoc says why. But a layout read back from `onResizeUi` is in
pixels, and nothing says how to put a node *there*. The answer is `floatAt(Length.percent(100 * x / parentWidthPx),
...)`, because a float's percent is of the parent's border box, with a size in percent of the parent's content. That
holds only if the parent has no padding, so `MapPane` adds an unpadded layer for the purpose. Took reading
`Length`, `floatAt` and `NodeLayout` together to find.

What the framework could do: one sentence and a snippet on `floatAt` ("to place a node at a point you measured..."),
or `Length.fraction(px, ofPx)` with the same meaning, named so it cannot be mistaken for a pixel unit.

## FN-3 · `ottermate await` is bounded at 30 s, with no way to say longer 🔬

An agent's reply can take a minute. `await status.agent idle` gives up at 30 s and stops the script; the
guide's `--timeout` bounds a whole command answering, not an `await`. The only workaround is to write the `await`
several times under `--keep-going`, which also stops the script stopping on real failures.

What the framework could do: `await <landmark> <text> within 120s`, or an `--await-timeout` option.

## FN-4 · Stale worktrees inside the stack's checkouts double every search hit 🔬

`vexelray-gui/.claude/worktrees/` holds two full copies of the repository and `vexelray-framework/.claude/worktrees/`
one. They are git-ignored, so `git status` is clean, but a grep of the stack (the way to learn its API, per
`CLAUDE.md`) returns each class two or three times, from different branches, and it is not obvious which is real.

What could be done: remove worktrees that are finished, or say in each stack repo's `CLAUDE.md` to search with
`--glob '!.claude/**'`.

## FN-5 · Nothing shows a populated application without its real back end 💡

For Maistro this was the logged-in CLI. The first look at the map needed a stand-in CLI and a recorded turn
(`src/test/ottermate/`). Every application on the framework that talks to something outside itself will need the
same. A template convention could make it one of the first things written: a `replay` folder and a documented
`--<backend>=<stand-in>` switch.

