# Maistro

Claude Code with hands: an agent that drives the VexelRay apps.

A VexelRay desktop client for Claude Code. It runs the Claude Code CLI as a child process in its streaming mode and
shows the conversation beside a mind map that the agent's replies grow, with the session's machinery (model, cost,
tokens) in a context stream on the right. The layout, palette and ideas come from the design canvas, summarised in
[docs/gui-design-guide.md](docs/gui-design-guide.md). It was generated from the `vexel-desktop` template; the editor
that template starts as is gone.

## Run it

```
mvn compile exec:exec
```

`exec:exec` rather than `exec:java`, so native access can be enabled for Panama FFM. The window comes up where you
last left it, at 1440x900 the first time, with the panes you last had up.

Maistro needs a Claude Code CLI that is logged in. It looks for `claude.exe` on `PATH`, then for the copy the Claude
desktop app keeps under `%APPDATA%\Claude\claude-code\`, then in `~/.local/bin`. That bundled copy is not logged in
on its own: run it once with `/login`, or name another with `--claude`.

```
mvn compile exec:exec "-Dapp.args=--claude=C:/path/claude.exe"   # which CLI to run
mvn compile exec:exec "-Dapp.args=--model=sonnet --cwd=C:/work"  # its model, and the folder it works in
mvn compile exec:exec "-Dapp.args=--permissionMode=acceptEdits"  # default is "default": anything needing approval is denied
mvn compile exec:exec -Dautomation=0                             # a driving socket on a free port (see Taking a screenshot)
mvn test                                                         # the agent protocol, the map, the real tree headless
```

## A native executable

Two editions, built with GraalVM native-image (a GraalVM JDK as `JAVA_HOME`, and a Visual Studio developer prompt,
which native-image needs for `link.exe` and the icon step needs for `rc.exe`):

```
mvn -Pnative-release package -DskipTests      # target/vexelray-maistro.exe        what ships: no console, no driving socket
mvn -Pnative package -DskipTests              # target/vexelray-maistro-debug.exe  a console, and ottermate can drive it
```

`MaistroApp`, the class the processor reads, is written twice — `src/edition-debug` names `AutomationStarter`
and `src/edition-release` does not — and the pom compiles one. Keep the two annotations identical apart from
`starters`. Everything else is shared; an ordinary build, `exec:exec` and the tests are the debug edition.

This program's own native-image metadata is `src/main/resources/META-INF/native-image/.../reachability-metadata.json`,
and it is short because almost nothing is this program's: the libraries register their own, and the processor
registers the icon. If a native run fails on something the JVM run did not (a `Missing...RegistrationError`), run the
debug edition on a JVM under `-agentlib:native-image-agent=config-output-dir=...`, do the thing that failed, and add
only the entries that name this program.

## The icon

`src/main/rc/vexelray-maistro.ico` is the application's mark, and it is one file read three ways: the window and its
taskbar button wear it (`@VexelApp(icon = Maistro.ICON)`; the pom puts it on the class path), and so does the
title bar the framework draws; the `.exe` links it (`src/main/rc/vexelray-maistro.rc`), and so Explorer, a shortcut and a
pinned button show it too. It starts as the framework's "new app" mark, whose source is `vexelray-maistro.svg` beside it.

A second window — a viewer, a settings window — wears the window mark, on the taskbar and in its title bar, with
nothing to write: declare it in a recipe with `windows.named(...)`, and the framework builds its tree and claims it
wearing `shell.windowMark()`. That mark is this same one unless
a `vexelray-maistro-window.ico` sits beside it, which is then found by name and registered for the native image with
the mark. The VexelRay suite's applications ship one: a band of the application's colour over its glyph, so a
second window reads as its own and is still told apart from the main one.

To make it yours, draw a 64x64 SVG and render it at every size Windows asks for, 16 to 256 px, into that `.ico` —
`vex-suite-common`'s `tools/Ico.java` does exactly this, from each size's own rasterisation rather than shrinking the
largest. Replace the file, and the window and the executable change together. An `.ico` or `.png` that is missing is
a compile error; one that does not decode costs the window its mark, logged, and nothing else.

Run from `mvn exec:exec`, the process is `java.exe`, so the window and its taskbar button wear the mark but Windows
groups the button with other Java programs, and pinning it pins Java. The native executable has neither problem.

## Using it

| Keys | Does |
| --- | --- |
| Enter (in the composer) | send |
| Escape | interrupt the agent's turn |
| Ctrl+L | put the keyboard in the composer |
| Ctrl+1 / Ctrl+2 / Ctrl+3 | show or hide the conversation, the mind map, the context stream |
| Ctrl+= / Ctrl+- / Ctrl+0 | zoom |

The first message starts Claude Code; it stays up for the conversation, and if its process ends, the next message
starts it again with `--resume`, so the conversation carries on. Each tool the agent runs is a turn of its own,
in teal, that ends with the first line of its result. The agent is asked (in its system prompt) to end a reply with
a `maistro-map` block; Maistro strips it from the transcript, adds what it names to the map, and puts a chip under
the reply saying what changed. The map's nodes from the last turn are outlined in amber, and the focus is filled.

## How it is put together

Read these in this order.

| File | What belongs in it |
| --- | --- |
| `Recipes.java` | **what this application builds.** One `@Provides` method per part; `MaistroAppWiring`, which builds them in order, is generated from it while the project compiles. A part's phase is the latest phase of anything it takes, so there is none to declare. New parts go here. |
| `Doc.java`, `Model.java` | the session: the transcript, what the agent is doing, which panes are up, the agent's own readouts and the mind map, as one immutable value changed only by functions of the current value, committed through atchung's `State`. |
| `Conductor.java` | the agent's side: one Claude Code process, and each of its events turned into one commit to the model. |
| `Ui.java` and its panes | the window: `PaneRail`, `ConversationPane`, `MapPane`, `ContextPane`. Hold no state; `show(Doc)` writes everything derived from the session. |
| `Actions.java` | every command and its chord. |
| `Session.java` | what comes back next time (the panes), in the framework's one settings store. |
| `Motion.java` | the one tempo every widget moves at, on the framework's clock. |
| `agent/` | **no `Gui`:** finding the CLI, its stream-json protocol as typed events, a strict JSON reader, the process. |
| `map/` | **no `Gui`:** the concept graph as a value, the `maistro-map` block format and its parser, a deterministic layout that keeps old nodes still. |
| `chat/` | **no `Gui`:** the transcript and its turns. |
| `text/Markdown.java` | **no `Gui`:** Markdown as spans, for the agent's replies. |

`Maistro.java` is the entry point and the constants, and nothing else. The application edge — input, the
clipboard, window memory, the clock, the frame loop with its wakes and its pacing, the dialogs, the command line and
the shutdown order — is `vexelray-framework`'s. `Look.java`, `Type.java` and `Landmarks.java` are the three
vocabularies: colour, size, and the names an automation script is allowed to depend on.

### Phases

A phase is a **correctness** constraint rather than a scheduling detail, and having them as a type is what makes the
constraints structural instead of remembered:

| phase | what exists by then |
| --- | --- |
| `CONFIG` | the settings store and the look — values, before there is a `Gui` to apply them to |
| `MODEL` | what the application knows, before there is anything to draw it with |
| `GUI` | the `Gui` and the clock; the theme applied and the zoom range set, before the first widget |
| `TREE` | the widgets. Buildable with no window, which is what lets a test build it with no GPU |
| `WINDOW` | the device and the window handle. Main-thread from here on |
| `ATTACH` | anything that needed the handle: chrome controls, the close gate, the driving socket |

Open `target/generated-sources/annotations/.../MaistroAppWiring.java` after a build to see which part landed where.

### Threading, in one paragraph

The GUI loop runs on its own thread. Every handler (a click, a key, a submitted message) runs on a worker; the first
send starts Claude Code there, off the frame loop. The agent's events arrive on the session's own reader thread, one
at a time and in order. Workers and the reader meet at `Model`, whose compare-and-set puts concurrent changes in an
order. A listener registered with `Model.onChange` fires on the committing thread; writing a node's props from there
is correct, because a prop written off the GUI thread is applied by the next drain. Nothing commits to the model
while holding a lock a model listener takes, and nothing inside the frame loop reads the model.

### Spans

`Markdown.spans` is a pure function from text to `Span`s (foreground, background, underline); the conversation pane
hands an agent reply's spans to its text node each time the reply grows.

### Motion

The widgets animate only when handed a ramp, and without one they cut, so leaving the clock out costs no error,
only a stiffer window. `Motion` makes the ramps from the framework's clock; today only the status line's alert cue
uses them.

## The stack

Everything below is installed locally rather than downloaded, so `mvn install` in each of those projects has to have
happened at least once. If the build cannot resolve them, that is why.

| what | why it is here |
| --- | --- |
| `vexelray-framework-shell`, `-automation`, `-processor` | the application edge, the driving socket, and the processor that writes the wiring |
| `vexelray-gui-widget` | the widgets: `Tabs`, `TreeView`, `TextField`, `SplitPane`, `StatusBar`, `Cues` |
| `vexelray-gui-krono` | the clock: every transition and cue |
| `vexelray-gui-automation` | driving the running app the way a person does — a debugging instrument |
| `tactroller-atchung` | keyboard and pointer input onto an atchung bus (brings `atchung-core`) |
| `tactroller-clipboard` | the OS clipboard, with no input-subsystem coupling |
| `vexelray-os-*`, `tactroller-*` | picked by an OS-activated profile; exactly one activates |

## Taking a screenshot

Screenshots are `ottermate`'s, and only its: it photographs the running window on the application's own device, so
the picture is right about content as well as chrome. There is no `--capture` flag in this project on purpose.

```
ottermate shot out.png --launch mvn compile exec:exec -Dautomation=0
```

Say what the picture is *of* with options, and `ottermate` sets it first and stops if it cannot. Everything before
`--launch` is ottermate's; everything after it is the application's command line (on Windows, `mvn.cmd`):

```
ottermate --zoom 1.5 --dpi 2 --size 1600x900 shot big.png --launch ...   # pixels
ottermate --size 66emx20em shot smallest.png --launch ...                # the minimum, in em
```

The landmarks in `Landmarks.java` are the names a script uses: `await status.agent idle` waits for a reply to
finish, and `click composer` then `type ...` and `key ENTER` sends one. `ottermate help` lists every verb.

### Without a logged-in Claude Code

`src/test/ottermate/` holds a check that needs no login and no network: `fake-claude.cmd` stands in for the CLI and
prints one recorded turn (`replay-map-turn.jsonl`) whose `maistro-map` block puts three nodes on the map, and
`map-focus.txt` sends a message, clicks a node and waits for the focus to move. It leaves its two screenshots in
`target/`. Copy it to check anything that needs a reply on screen.

```
ottermate --script src/test/ottermate/map-focus.txt --launch mvn.cmd compile exec:exec -Dautomation=0 "-Dapp.args=--claude=src/test/ottermate/fake-claude.cmd"
```

The stand-in exits after its turn, so the status line ends at `agent: stopped`, not `idle`.

## Logging

The framework configures logging for you, before anything else runs, and the defaults follow what kind of run it is:

| Run | Console | File |
| --- | --- | --- |
| a test | warnings | none |
| from your checkout (`mvn exec:exec`) | info | `target/logs/vexelray-maistro.log`, debug |
| driven by `ottermate` or `-Dautomation` | debug | `…/vexelray-maistro.log`, **trace**, with a probe trace beside it |
| a native image | warnings | `~/.vexelray-maistro/logs/vexelray-maistro.log`, info |

Write to it with a logger, not `System.out` — `Actions` does, as `vexelray-maistro.files`:

```java
private static final Log LOG = Log.of("vexelray-maistro.files");   // sibarum.probe.Log
LOG.warn("could not save {}", path, exception);               // the exception goes last; its stack reaches the file
```

`--log=debug` (or `off`) changes the level for one launch, and `-Dlog.level.vexelray-maistro.files=trace` raises one
logger. Levels, settings and the rules for what belongs at which level are in `atchung/docs/logging.md`.

## The two documents

`docs/framework-notes.md` and `docs/TODO.md` start nearly empty and are meant to be filled in as you go. See the note
at the top of the first one — it is the more important of the two.
