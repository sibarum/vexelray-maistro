# Orchestration: one main agent, many workers

How Maistro runs a large project: one continuous **main agent** the human talks to, which delegates to **task
agents**, which may split their work across **subtask agents**. This is a design, not built yet. It extends what
exists: today a conversation is a main strand plus flat branches (`Doc.Strand`), and this makes those strands a
tree with roles. It sits under [design.md](design.md). The way every agent works is in [engineering-charter.md](engineering-charter.md). The tools
they act through are in [agent-tools.md](agent-tools.md).

## The shape

```
human ── any device ── relay ──► main agent             (continuous; conversation, digests, docs)
                                   ├─► task agent        (a goal; docs and code)
                                   │     ├─► subtask agent   (one parallel piece; docs and code)
                                   │     └─► subtask agent
                                   └─► task agent ...    (possibly on another device)
```

| Role | Lives | Spawns | Reads mostly | Talks to |
| --- | --- | --- | --- | --- |
| **main** | for the project, continuously, compacting as it goes | task agents | the conversation, digests from tasks, documentation | the human, its tasks |
| **task** | until its goal is met or dropped | subtask agents, for work that can run in parallel toward its goal | documentation and code | main, its subtasks, the human if they open it |
| **subtask** | until its piece is done | nothing | documentation and code | its task, the human if they open it |

**Depth is limited by construction, not by instruction.** Spawning is a tool (`spawn`, in a `maistro-team`
toolset). Main is given `spawn` for tasks, a task is given `spawn` for subtasks, and a subtask is not given `spawn`
at all. An agent cannot call a tool it was never listed, so the limit cannot be talked around. That follows the
house rule that the earliest check wins.

**There can be several mains.** Each is a tree of its own: one main with its tasks and subtasks, and **its own
memory graph**. Mains do not share memory. What one main has learned, another learns only if the human or a
document tells it. They do still see each other: blast radius, `who` and the watcher count every agent on the
system, whichever main it belongs to. So two mains working one checkout never collide unseen, even though each
keeps its own understanding. A task's or subtask's memory is its main's: they read and write that graph, and no
other.

**The filesystem is the only thing shared, and all of it is auditable.** A memory tree or subtree can be written
to disk as files, beside the project's other documentation. Once it is there, any main can read it, and that is
the only way one main's knowledge reaches another. There is no hidden channel between mains, and nothing is shared
that a person cannot open, read, diff and commit. Briefs and reports are files too.

**A new main is always a clean slate.** It starts with no memory, and knows only what it reads: the project's
documentation, including any memory trees written there. Nothing carries over invisibly from an earlier main, so
how a main reached its conclusions can always be traced to files. The one thing every main shares is the retrieval
model ([design.md](design.md), *Learning from use*). It is learned skill, and it ranks only what a main can
already see. It supplies no content.

The human usually speaks only to main. Any task or subtask can still be opened and spoken to directly, as a
branch's tab is today. What is said there goes into that agent's next report, so main never loses track.

## Keeping main's context lean

Main should spend its tokens on the conversation, status and documentation, never on code or transcripts. So
nothing flows up the tree raw:

- **Reports, not transcripts.** A task sends main a structured report at each milestone, and whenever it is
  blocked, decides something main should know, or finishes. The report is `{task, state, progress, decided:
  [...], produced: [docs, commits], blocked_on, next}`. A subtask reports to its task in the same shape.
- **Signals Maistro adds by itself**, so no report has to remember them: commits made, tests run with their results,
  external changes inside the task's blast radius, and the agent's cost.
- **A digest per turn.** Main does not get each report as it arrives. Before each of main's turns, Maistro folds
  everything since the last turn into one digest: what moved, what is blocked, what needs a decision. A busy
  hour of work is a few lines.
- **Drill-down on demand.** To know more, main asks the task (`ask`, in `maistro-team`), and the task answers in a
  sentence or a document. Main never reads a worker's transcript.
- **Documentation is the shared memory.** Workers write what they decided and why into the project's docs and into
  the memory graph. Main reads those, not the code.

## Continuity: main never restarts from nothing

Main is one long-lived Claude Code session, resumed with `--resume` across restarts, as strands are today. It
compacts regularly instead of filling up:

1. **Durable facts go to the memory graph first**: decisions with their reasons, requirements the human stated,
   and the state of each task. These are written as memories with links, before anything is summarized away.
2. **Then the conversation is compacted** (the CLI's own compaction), with the memory graph as the record of what
   matters. Anything compacted away can be found again by searching memory.
3. **Compaction is triggered by size**, at a share of the context window (a setting). A compaction is
   reported to the human as a chip in the transcript, so it is never invisible.

Task agents compact too, but they are short-lived, and their output lives in docs and commits, so they usually end
before they need to.

## The map shows the project

The mind map gains a layer for the work itself. Each task and subtask is a node, linked to the concept nodes it
works on, and coloured by its state: working, blocked, waiting for the human, done. Each digest moves the nodes,
so the map is the bird's-eye view: what is happening, where, and what is stuck. Clicking a task node opens that
agent, as clicking a branch tab does today.

## Across devices: the relay

- **Every device runs Maistro**, and each can host task and subtask agents, using that machine's checkouts and
  tools. Main runs on one of them, its **home**.
- **A relay joins them.** Each instance connects outward to the relay, so no device needs an open port, and messages
  are routed by account and device: reports and `ask` going up, `spawn` and messages going down, and the human's
  conversation with main from wherever they are.
- **A thin client** (a phone, a browser) shows the conversation with main and the map, and nothing else. It needs
  no checkout and runs no agent.
- **Main places work where it can be done.** A task needs a machine with the checkout and tools it requires.
  Blast radius ([agent-tools.md](agent-tools.md)) tells main which folders are already hot on which machine, so two
  tasks are not set on the same files.
- **Security:** messages are end-to-end encrypted between the account's devices, and the relay sees only routing.
  Each device is enrolled once by the human. A device can host agents only for projects the human has allowed on
  it.

The name clashes with the test stand-in under `src/test/relay/`, the fake CLI. One of the two needs renaming
before this is built.

## How a task is born and ends

1. Main writes a **brief**: the goal, the larger intent it serves, the requirements the human fixed, the area of
   the code, and what done means. A brief is a document, kept, so a task can always be restarted from it.
2. Main calls `spawn` with the brief and a placement. The task gets a strand, a worktree of its own (so tasks never
   share a working tree), and the role's toolsets.
3. The task works, splits off subtasks where pieces are independent, and reports.
4. When it is done, it reports `done` with what it produced. Main reviews the result: the diff summary, the tests,
   and the docs. Then main merges the branch or sends it back with what is missing.
5. The task's memory-worthy decisions are already in the graph, so ending it loses nothing.

## What this changes in the code

- `Doc.Strand` gains a role (`main`, `task`, `subtask`), a parent, a device, a brief and a state. Strands become
  a tree, and a branch as it exists today is a task the human started by hand.
- A `maistro-team` toolset: `spawn`, `ask`, `report`, `tasks`, `stop`, each listed by role.
- The digest is a pure function from the reports and signals since main's last turn to one block, testable
  without a window.
- The relay is its own module, client and server, with no `Gui`.
- The charter goes into every agent's system prompt through `--append-system-prompt`. Its quotes now arrive
  intact on Windows: `AgentProcess.forWindows` escapes them.

These touch the model's shape and threads, so each is to be proposed before it is made.

## Open questions

- **Where main's home is:** one machine fixed by the human, or wherever Maistro was last opened.
- **The relay itself:** self-hosted, or a service. That choice is open for now, as the cross-device memory and
  radius sync are.
- **Concurrency limits:** how many task and subtask agents run at once, per device and in total. Cost is the human's
  to cap.
