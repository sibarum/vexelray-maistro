# Maistro design

What Maistro is for, how it is built, and the order it is built in. This is the top of the design: when another
document disagrees with this one, this one wins, and the other is fixed. The detail lives in four documents
under it:

| Document | Covers |
| --- | --- |
| [orchestration.md](orchestration.md) | main, task and subtask agents, reports and digests, compaction, several mains, the relay |
| [agent-tools.md](agent-tools.md) | the toolsets the agents act through, the journal and undo, blast radius, the watcher, locks |
| [engineering-charter.md](engineering-charter.md) | how every agent builds; given to each one as part of its system prompt |
| [gui-design-guide.md](gui-design-guide.md) | the design canvas's layout and visual language, and the earlier design conversation |

## What Maistro is

**Maistro runs large software projects from a bird's-eye view.** The human talks to one continuous agent, the
**main**, about what is being built and why. The main turns that into tasks, places each with a task agent (on
this machine or another), and keeps track through short reports. The work itself, the documentation and code, is
done further down the tree, where the context is spent on it.

It began as "Claude Code with hands", a desktop client that drives the VexelRay applications. That remains one of
the things its agents can do, among files, diffs, git, a browser and memory.

**What it is aiming for:** writing world-class code should never be a chore. Every agent holds to the
[charter](engineering-charter.md): understand the vision before building, decide technical questions itself and
record why, and build reusable modules properly the first time, with no shortcuts and no "refactor later".

## Principles

These decide between designs. When two designs both work, the one that keeps more of these wins.

1. **The human speaks intent; the agents decide technique.** The human gives the vision, the requirements and the
   critical-path constraints. Technical decisions are the agents', each recorded with its reason, so that any of
   them can be found and questioned.
2. **The filesystem is the only shared state, and all of it is auditable.** Briefs, reports, decisions and
   exported memory are files beside the project's documentation, readable, diffable and committable. Nothing passes
   between mains through a hidden channel.
3. **A new main is a clean slate.** It knows only what it reads. How it reached its conclusions can always be traced
   to files. The shared retrieval model is skill, not content: it ranks what a main can already see.
4. **Context is spent where the work is.** The main's context holds conversation, digests and documentation. Code
   and transcripts stay with the task and subtask agents. Nothing flows up the tree raw.
5. **No shell between intent and effect.** Agents act through typed tools whose only escaping rule is JSON's. Text
   has a declared encoding, and every change returns what changed.
6. **Every change can be undone, or says that it cannot.** Every action is tagged undoable, checkpointed or
   irreversible. Irreversible ones, such as a push, a deletion or a message sent, ask the human.
7. **Every agent can see every other.** Blast radius and the folder watcher count every agent on the system,
   whichever main it belongs to, and the human in an editor as well.
8. **Limits are structural.** What an agent may not do, it is not given a tool for. A rule a prompt can talk
   around is not a rule. The earliest check wins: a type before a test, startup before runtime.
9. **Visible, not hidden.** The map shows the project's state. Every compaction, every external change and every
   decision taken autonomously shows up where the human can see it.

## The parts

```
 devices ── relay ──┐
                    ▼
 ┌──────────────────────────── Maistro (one per device) ────────────────────────────┐
 │  window: conversation · map · context stream · agent panes                        │
 │                                                                                   │
 │  agents: main ─► task ─► subtask          each a Claude Code process (a strand)   │
 │                                                                                   │
 │  toolsets (MCP, in process):  fs · diff · git · run · mem · team · web · apps     │
 │                                                                                   │
 │  journal (undo) · blast radius · watcher · locks · memory graph (per main)        │
 └───────────────────────────────────────────────────────────────────────────────────┘
                    │
                    ▼
     the filesystem: checkouts, docs, briefs, reports, exported memory  (auditable)
```

- **Agents.** Each agent is a Claude Code CLI process that Maistro runs in streaming mode: a strand. Strands form
  trees: a main, its tasks, their subtasks. There may be several mains, each with its own memory.
  ([orchestration.md](orchestration.md))
- **Toolsets.** Each is an MCP server inside Maistro's process, reached over the CLI's own pipes. Which toolsets an
  agent is given depends on its role, so a subtask has no `spawn`. ([agent-tools.md](agent-tools.md))
- **The journal.** One per session, recording every change any toolset makes, so that each can be undone.
- **Coordination.** Blast radius (how hot each folder is, from the edits made in it), the native folder watcher
  (changes made by others), and advisory locks.
- **Memory.** Each main has its own graph of memories and typed relationships, kept as an append-only log. It can
  be exported to Markdown files beside the docs, which is the only way it reaches another main.
- **The relay.** It joins the account's devices, carrying reports, messages and the human's conversation with
  main. A phone or a browser is a thin client: the conversation and the map, and nothing else.

## Learning from use: retrieval that gets better

As Maistro is used, it fine-tunes a small specialist **instruct-embedding** model. The model learns which problems
lie close to which builds, tests and resolutions, so a search of the history brings back the past work most
likely to help, and Maistro can fetch it without being asked.

**The unit is an episode**: a problem statement, the builds and tests run against it, and how it was resolved.
Maistro already records every part of one:

| Stage | Where it comes from |
| --- | --- |
| problem statement | the human's message, a task's brief, a failing test's output, a build error |
| build | `run` results: the command, exit code, and the diagnostics in its output |
| test | `run` results for tests: which failed, then which passed |
| resolution | the journal's diffs and patches, the commit, the task's `done` report, and decisions recorded as memories |

Episodes are cut from the journal, `run` results, git and reports. **The labels come from the work itself.** A
failing test that passes after a diff ties that problem to that resolution, so nobody has to label anything. A
resolution that is later undone or reverted counts against the pairing.

**Training.** Contrastive pairs are drawn from adjacent stages of one episode: problem ↔ resolution, failing test
↔ fix, build error ↔ the change that cleared it. Other episodes in the batch serve as negatives. Each side carries
an instruction saying what it is for, for example *represent this build failure, to find what fixed similar
ones*, so that one model serves several kinds of search. The base model is small, and fine-tuning is
parameter-efficient (an adapter), so training runs locally, in the background, as a separate process, never in
Maistro's frame loop or on its tool threads.

**A new version must beat the last one before it is used.** Each version is scored on held-out episodes (recall@k
of the true resolution for each problem) and replaces the current one only if it does better. Versions are kept,
so a regression can be rolled back. This is the charter applied to the model.

**Using it.** Inference runs in-process through a native runtime over Panama FFM, so it needs no reflection and
works in the native image. Its index is a file per corpus, beside what it indexes. It is used in three places:

- **Memory and history search**: `search` in `maistro-mem`, and a history search over episodes, rank by the model
  as well as by text and links.
- **Auto-fetch**: when a problem statement arrives (a brief, a failing test, a build error), the nearest past
  resolutions are fetched and offered to the agent, each marked as fetched rather than chosen, with its score and
  the file it came from. This is the earlier design's "daydreaming" retrieval, made concrete.
- **The context stream's vector-index section** shows what was fetched and why. It stops being a placeholder.

**Audit, and the clean slate.** Every fetched result cites a file: the episode, the memory or the commit. A
retrieval can always be checked against its source. The model's weights rank what a main can see. They never
supply content.

**One model, shared by every main** (decided 2026-11-17). It trains on the episodes of every main and ranks for all
of them, a new main included. It is learned skill, not shared memory: it knows which kinds of problem lie near
which kinds of resolution, and it holds no facts a main could read out of it. A main still retrieves only from
the corpora it can see, its own log and the files it has opened. So the clean slate holds for content, and every
result still cites its file.

## The window

The canvas's layout and visual language stand ([gui-design-guide.md](gui-design-guide.md)): a dark ground, amber
for what is new or selected, teal for what is settled or structural, IBM Plex, and the pane rail. What changes is
what the panes are about.

| Pane | Was | Becomes |
| --- | --- | --- |
| Conversation | one strand's transcript, with branch tabs | the conversation with main. Any other agent opens in the same pane on request, and its transcript is there to read, not to follow |
| Mind map | the concepts a conversation grows | the project. Concept nodes as before, plus a node per task and subtask, coloured by state (working, blocked, waiting for you, done) and linked to the concepts it works on. Each digest moves them |
| Context stream | the model, cost, tokens; placeholders for the KV cache and vector index | the machinery of the agent on screen: its context use and next compaction, cost, blast radius, external changes pending, memory touched |
| Agent panes (new) | — | the action timeline of the gui guide's gap 2: each tool call as a step with its tag, its verdict and its delta, and Undo beside it. Live app panes (gap 1) are one kind of step's view |

The ideas the earlier design conversation committed to carry over, reinterpreted for the tree:

- **Mandate, Carve, Compose.** A task's brief is its mandate. A surprise is an expectation failing, and a carve is
  a path struck out, with its reason and scope. Both are recorded as memories. Compose is how subtasks combine
  into a task's result.
- **Every node is a fork point.** Starting a branch from a node, as Maistro does today, becomes starting a task
  from it, with the node's neighbourhood as the start of its brief.
- **Steering, not gating.** A yes, a no or no reaction to a candidate steers the agent. Only an explicit no carves.
  The predictive, daydreaming composer is still wanted, after the agent tree is built.

## What exists today

The client works for a single human-to-agent conversation:

- the conversation with Markdown, tool turns, interrupts, and Allow or Deny for each permission request, with
  Claude Code's own suggestions for standing rules
- the mind map grown from `maistro-map` blocks; focus; flat branches started from a node, staged, merged or
  abandoned
- conversations saved and resumed; the sign-in check, its card, and a message held through a lost sign-in
- process start and message writing off the GUI thread; arguments that reach the CLI intact on Windows
- the read-only app tools (`apps`, `tree`, `find`, `shot`) as an MCP server in Maistro's process

Known gaps are in [TODO.md](TODO.md).

## Milestones

One at a time. Each milestone is finished, seen working under `ottermate`, and written up in
[retrospective.md](retrospective.md) before the next starts. The slices inside each are in the detail documents.

1. **Tools foundation.** Several MCP servers in one toolset; structured results. Filesystem reads, then the
   journal with `write`, `edit` and `undo`; diffs; `move`, `copy`, `delete`, `mkdir`; `run` on a shared argv
   launcher.
2. **Git.** Read-only tools first, then the ones that change things, with house rules and ref undo; `push` last.
3. **Memory.** The per-main graph, its log and tools, and export and open.
4. **The agent tree.** Strands with roles and parents; the `team` toolset (`spawn`, `ask`, `report`, `tasks`,
   `stop`), listed by role; briefs and reports as files; the digest; the main's compaction into memory. Today's
   branches become tasks.
5. **The map as the project.** Task nodes, their states, and the digest moving them; the context stream for the
   agent on screen; the action timeline with Undo.
6. **Learned retrieval.** Cutting episodes from the journal, `run`, git and reports; text search over them first;
   then the base embedding model in process, with its index; then local fine-tuning, evaluation and promotion;
   then auto-fetch.
7. **Coordination.** Blast radius from the journals and from Claude Code transcripts; the native watcher on
   Windows, then Linux and macOS; `external_changes`; locks.
8. **Driving the apps.** The acting `ottermate` tools (`click`, `type`, `key`, `settle`, `await`) through the
   permission prompt, and live app panes.
9. **The browser.** Reading tools first, then acting, then `eval`.
10. **Across devices.** The relay, device enrolment, placing tasks on other machines, and the thin client.

The order follows dependencies. The tree needs tools to act with and memory to compact into. The map needs the
tree. Learned retrieval needs the episodes that the journal, `run`, git and reports record. Coordination matters once several agents write at once. The relay comes last, because everything it
carries must exist first.

## Open questions

These are gathered from the detail documents. Each is answered there, and removed from here once answered.

- **Main's home:** one machine fixed by the human, or wherever Maistro was last opened.
- **The relay:** self-hosted or a service, and the cross-device sync of memory and blast radius through it.
- **Concurrency and cost:** how many agents may run at once, per device and in total, and who caps the spending.
- **Advisory or enforced radius:** whether a write into a hot folder warns (proposed) or is refused.
- **Alongside the CLI's own tools or instead of them:** start alongside, then turn off `Bash`, `Read`, `Write` and
  `Edit` once Maistro's toolsets are proven.
- **Which browser:** the Edge or Chrome already installed, or a pinned Chromium.
- **Claude Code's own memory:** whether it is opened as an exported tree, imported, or left alone.
- **The base model and its runtime:** which small instruct-embedding model to start from, and whether to run it on
  ONNX Runtime or llama.cpp. Training, too, needs a runtime that Maistro can drive as a process.
- **From the earlier design:** how app states are made canonical, how a carve is scoped, and how a late no
  calibrates the agent ([gui-design-guide.md](gui-design-guide.md)).
- **Naming:** the test stand-in under `src/test/relay/` clashes with the relay, and one of them is renamed.
