# Working in Maistro

Maistro is Claude Code with hands: a VexelRay desktop client that runs the Claude Code CLI as a child process
(`agent/`, its stream-json protocol), shows the conversation beside a mind map the agent's replies grow (`map/`),
and will drive the other VexelRay applications through `ottermate`. The design is
[docs/gui-design-guide.md](docs/gui-design-guide.md). It was generated from `vexelray-framework`'s `vexel-desktop`
template, and the editor that template starts as has been removed. [README.md](README.md) is the tour: read its
*How it is put together* section before changing anything, and `Recipes.java` before adding a part.

## Where things are

- **`Recipes.java`** is what the application is made of: one `@Provides` method per part. `MaistroAppWiring`
  is generated from it by an annotation processor while the project compiles. Nobody writes it, so never create
  or edit a file of that name. After a build it is in `target/generated-sources/annotations/`, and it shows what is
  built in which phase.
- **`Model.java` and `Doc.java`** hold all of the application's state, as one immutable value that is replaced, never
  mutated. `Ui.java` holds no state.
- **Code with no `Gui` in it** goes in a sub-package with public types (as `text/` does), so it can be unit tested
  without a window.
- **`Landmarks.java`** names everything an automation script may refer to. A new control a test needs to reach gets
  a landmark there, not a string literal.
- **The stack** (vexelray, vexelray-gui, vexelray-framework, tactroller, atchung, kronometer, ...) is usually checked
  out in the folder above this one. **Treat it as read-only.** Its source and javadoc are the documentation; read
  them instead of guessing an API. If something you need is missing there, do not patch the stack. Write it up in
  `docs/framework-notes.md` (below) and say so.

## Rules the code does not show

Breaking one of these rarely fails a build. It shows up as a hang, a stutter or a broken native image, so hold to
them even when a shortcut looks harmless:

- **Nothing reflects.** No `Class.forName`, no runtime annotation reads, no reflective serialization. This compiles to
  a GraalVM native image, and the framework's wiring is generated at compile time so that nothing needs reflection.
- **Nothing in the frame loop allocates, logs or blocks.** No disk, no network, no locks there.
- **Vulkan, the window and present stay on the main thread.** Handlers run on workers, and disk work goes to the
  offload lane. README's *Threading, in one paragraph* is the whole model: nothing commits to the model while
  holding a lock that anything reached from a model listener takes.
- **All input comes through tactroller.** No polling the OS keyboard, and no native listener of your own.
- **Log through `sibarum.probe.Log`**, as `Log.of("vexelray-maistro.<area>")`. stdout is for lines another program parses
  (`automation: localhost:<port>`), and for nothing else, including diagnostics.
- **Nothing appears, moves or grows on hover**, and only command keys auto-repeat.
- **A compile error beats a startup error beats a runtime error.** Prefer the check that fails earliest.
- **No sealed `switch` and no `default` that throws.** Turn it into a method on the type, or a sink.
- **Keep the two editions identical.** `src/edition-debug` and `src/edition-release` declare the same `@VexelApp`; they
  differ only in `starters`.

## Building and checking

```
mvn compile exec:exec                       # run it
mvn test                                    # unit tests, and the real window tree built headless
```

**A change to the window is not done until it has been seen.** "It compiles" and "the tests pass" are not evidence
that it looks or behaves right. Drive it with `ottermate`, the stack's automation client (see the stack's
`vexelray-gui/docs/guides/ottermate.md`), and look at the screenshot before saying it works:

```
ottermate --script check.txt --launch mvn.cmd compile exec:exec -Dautomation=0
```

A script is one command per line: `settle`, `tree`, `click <landmark>`, `type <text>`, `key CTRL+S`,
`await <landmark> <text>`, `shot <file.png>`. It stops at the first failure. Screenshots come only from `ottermate`.
Never add a `--capture` flag, a robot or a screen grab to the application.

When a run fails, read `target/logs/maistro.log` (`target-linux/logs/` on Linux) before changing code. Under automation it is at trace level.

## The three documents

- **`docs/framework-notes.md`**: when you work around something the framework does not do, add an FN entry **at the
  moment you write the workaround**, in the format at the top of that file. That is how the gap gets fixed upstream
  instead of copied into every application.
- **`docs/retrospective.md`**: **when a milestone is done, before starting the next**, read the milestone back and
  add its section, in the format at the top of that file: the shapes in the notes since the last one, what would stop
  each, your own mistakes, and what to keep. Write it while this conversation still remembers what each finding
  cost, and put your own mistakes in as plainly as the framework's. Never delete a section.
- **`docs/TODO.md`**: work on this application that is known about and not done. Delete an entry when it is done.

## How to work here

- **One slice at a time**: a change from the model to the screen, with its test and its `ottermate` check. Not
  several features at once.
- **Copy a precedent.** The template's own files, and the applications generated from it (Vex, Calculator,
  MainFrame), have solved most problems once. Find how they did it before inventing a way.
- **For anything that touches `Recipes.java`, threads or the model's shape, propose the change before making it.**
