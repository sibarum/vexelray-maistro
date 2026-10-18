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

Its other half: `await` can only wait for a name to *contain* something, never for it to stop, or for a node to go.
After the composer's ✕, `src/test/ottermate/map-focus.txt` can only `find` and leave a person to read the answer,
because a check that the chip went would need `await composer.from !from` or `await-gone composer.from`.

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


## FN-6 · `Node.children(...)` appends, and reads like a setter 🔬

`row.children(a, b)` posts an `Insert` per child, at the end. It does not replace the row's children. Every
builder-style API this resembles replaces, and so did both places Maistro rebuilt a row with it: a turn's chips
(`ConversationPane`) and the map's click boxes after a carve (`MapPane`). Both bugs were silent until two rebuilds
happened before a frame. A tool turn then showed `allowed allowed`. Fixed here by calling `remove()` on each old
child first.

What it costs: a bug that every application writing "redraw this row from the model" hits, and that looks right in
every test that only builds once. What the framework could do: rename it `append(Node...)` and add a
`replaceChildren(Node...)` that does what the name says. At the least, say "appends" in its javadoc, which is
empty.

## FN-7 · A script cannot close the application, so shutdown is never checked 🔬

Maistro saves the conversation as it closes (`Archive.close()`), and there is no way to see that happen under
`ottermate`. At the end of a script `ottermate` kills the process: the log stops at `connection 1 closed`, with no
shutdown in it. The title bar's minimise, maximise and close controls are not in the automation tree (`tree` lists
only the title and the two instrument icons), so there is nothing to `click`, and `key ALT+F4` arrives as a key and
closes nothing. The save on close is unit-tested, and the framework's promise to close `AutoCloseable` parts is
taken on trust.

What it costs: every application's shutdown path (saving, flushing, closing a child process) goes untested end to
end. What the framework could do: put the window controls in the tree with landmarks (`window.close`), or give
`ottermate` a `close` verb that asks the window to close as a person would, then waits for the process to exit and
reports its exit code.

## FN-8 · No quick-pick: choosing one item from a list is built from parts 🔬

Ctrl+O wanted the usual "pick a recent one" list: open on a key, the newest selected, Enter or a click to choose,
Escape to dismiss. The framework has the parts (`ListView` with `onActivate`, `onRowClicked` and a
`SelectionModel`), but no widget that is the whole thing. `Modal` takes only buttons, and the framework's
`Dialogs` are native file dialogs. So `ConversationPane` puts a `ListView` in the transcript's place, selects the
first row by hand, moves the keyboard into it, and routes Escape through the application's global claim. That is
about 60 lines, and every application with "recent files", "switch to" or "run command" will write them again.

What the framework could do: a `QuickPick<T>` (or `Palette`) taking items, a label function and a chosen callback.
It would float over the window, filter as you type, and close itself on Escape or a choice.

## FN-9 · A tooltip left standing after the pointer only passed through 💡

Seen once, in `target/always-asking.png` (the `always-allow.txt` run): the rail's "Mind map (Ctrl+2)" tooltip is up
and covers the tool's command, though the pointer's last stop was the composer. `ottermate` travels the pointer to
each target, and the line from the window's corner to the composer crosses that icon, so a tooltip seems to open
from a pass and not close when the pointer leaves. Not reproduced on purpose; the other dozen runs show none.
Worth checking whether a tip that opens mid-travel is closed on the next pointer move. It is the one thing in this
application that appears on hover, and only the framework's tips do.

## FN-10 · `Sketch` draws only solid lines 🔬

A branch's staged links are drawn dashed, so they read as not yet part of the map. `Sketch` has `line` and
`polyline`, both solid, and nothing for a dash pattern, so `MapPane.dashed` lays dash after dash along the line
itself. It costs a mark per dash, and the dashes restart at each end rather than flowing along a path.

What the framework could do: a `dash(double on, double off)` state on `Sketch`, like `tag`, applied to the lines
and outlines drawn after it, and passed through to SVG as `stroke-dasharray`.
