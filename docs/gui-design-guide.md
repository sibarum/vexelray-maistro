# Maistro GUI design guide

Maistro is Claude Code with tools that drive the VexelRay desktop apps directly (via `ottermate`'s automation
socket). The GUI is the human-agent interface for that, and it has to keep working as the suite grows.

**Source of truth:** the design canvas at
<https://claude.ai/artifact/FgVsoC3pSZBxQ8j7L7jxTn> (artboard `project/Main.dc.html`, 1440x900, interactive).
The canvas is edited out-of-band by another agent as the POC evolves. The user says when it changes; re-read
it then, and treat this file as a snapshot, not the authority.

Snapshot taken 2026-10-17 (artifact version `1792282697-a7c4`).

## Layout (left to right)

| Region | What it is |
| --- | --- |
| Title bar | Logo, session title ("Surfel cache eviction"), live-context status dot ("context live - indexing") |
| Icon rail (72px) | Conversation, Mind map, Graph database, Vector index, KV cache, Settings. Panes toggle on/off |
| Conversation (max 620px) | Chat transcript plus composer |
| Mind map (flexible, the main stage) | Concept graph that grows with the conversation |
| Context stream (290-340px) | Focus, entropic KV cache grid, vector-index nearest hits, session graph stats |

## Ideas the design commits to

- **Mind map as a first-class view of the conversation.** Each turn adds nodes and edges. Nodes added in the last
  turn are outlined amber ("from last turn"). The agent's reply carries chips (`map - +3 nodes`,
  `graph - ring-buffer streaming`) saying what it touched. Clicking a node sets the **Focus**.
- **Predictive composer.** Ghost text completes the input from suggestions. `Tab` accepts, `Enter` sends, and
  there is an explicit accept button too.
- **Context is visible, not hidden.** The right rail shows the machinery: entropic KV blocks (colour ramp
  settled=teal to surprising=amber), vector-index nearest neighbours with scores, graph node/edge counts.
- **Surprise is the shared signal.** The same information-theoretic score drives KV eviction and what the map
  highlights.

## Visual language

- Dark neutral ground (`#0F1316` stage, `#12181B` panels, `#222C31` rules), warm off-white text `#E6E1D6`.
- Amber `#F0A93B` = new / selected / primary action. Teal `#5CC2B5` = settled / structure / links.
- IBM Plex Sans for prose, IBM Plex Mono for readouts, labels (uppercase, letter-spaced) and chips.
- 44px minimum touch targets, real buttons with `aria-label`s, 4.5:1 text contrast.

## Gaps the canvas does not cover yet (the Maistro-specific part)

The canvas shows the thinking side (map, context). It has no surface yet for the *acting* side, which is what
makes Maistro different from Claude Code. Candidates to design next, in rough priority:

1. **Live app panes.** A view of each VexelRay app the agent is driving (from `ottermate list`), with its
   screenshot stream, the element the agent is about to click or type into, and a clear "agent has control"
   state with a one-key take-over.
2. **Action timeline.** Tool calls as typed steps (key, click, read readout, screenshot) tied to the app and the
   frame they affected, replayable.
3. **Permission and approval.** Inline approve/deny for effectful steps; per-app trust.
4. **App switcher that scales.** The rail already lists panes; with many suite apps it needs grouping, search
   and running-state badges.
5. **Map nodes linking to apps.** A node for "Surprise score" could open the app/file/readout it came from.

## Design principles from the design conversation (2026-10-17)

These come from talking the design through from the agent's side. They extend the canvas and are not yet drawn on it.

### Expectation before action (Mandate-Carve-Compose)

- **Mandate:** the session has an outcome the human wants, pinned above the map. Each step also states what the
  agent expects to see before it acts.
- **Surprise** is a step's expectation failing. It is the one signal that should drive map highlights, KV
  eviction and "stop and look" prompts.
- **Carve:** remove a path from the search after a surprise, or because the human struck it out (a node, an app,
  a UI subtree, a class of action). Carves need a stated reason and a scope, and must be revocable. Irreversible
  actions start carved until the human uncarves them once.
- **Compose:** primitives (key, click, read readout) combine into steps, steps into named routines with their
  mandate attached as the contract, routines across apps into things no single app does.
- Loop: mandate, act, surprise, carve, recompose against the same mandate.

### The state graph (second map, per app)

Every `ottermate` run is a walk through an app's state graph (state, action, next state). The per-app graph
persists across sessions and is the learnable object: start is the current state, the mandate is the end
predicate, carves say where not to go. Positive labelling is not needed. It is drawn in the app's live pane,
separate from the conversation's concept map. When storage is full, evict settled, low-surprise regions first.

### Daydreaming composer, steering not gating

- The composer retrieves from the vector index and graph DB as the human types and shows what the draft attaches
  to. These are speculative candidates, drawn dim like ghost text. They cost the agent nothing and do not
  change its behaviour until acted on.
- **Yes** (click a candidate) is a signal and so is **no**. **No reaction** leaves it to the agent's judgment.
  None of the three is an access restriction. They steer the conversation, like nonverbal communication.
- Only an explicit no carves. Silence delegates to the agent and is not a rejection.
- Context the agent pulls in unprompted is marked agent-chosen with a one-line reason, and the agent says what
  it skipped. A human yes or no outranks agent judgment, and a late no on agent-chosen context is a calibration
  signal worth keeping.
- There is no workspace picker. The workspace is derived from the draft plus the associations confirmed, and can
  change mid-conversation. Context for likely candidates can be precomputed speculatively (as in speculative
  decoding) so a yes feels instant.

### Replies built to be forked

- Every node is a fork point. A turn started from a node gets that node's neighbourhood (projects, docs), not the
  whole history. A branch can merge back as new edges.
- For an ambiguous question the agent answers the most likely reading briefly and offers the other readings as
  nodes. Clicking one is the reply. Unclicked nodes stay open until an explicit no.
- The agent writes replies with separable topics on purpose, because each section is a place the human may go
  next. It makes a call and does not hedge, and asks only when the readings lead somewhere very different.

### Acting-side requirements (agent's view)

- Structure over pixels: show cheap reads (tree, readouts) differently from costly ones (screenshots).
- Every action returns a verdict and a delta (what changed, whether the app settled, whether it matched the
  expectation), shown in the timeline.
- Shared control is explicit: who holds keyboard and pointer, one-key take-over, human actions reach the agent as
  events, pointing resolves to a node ID and not coordinates.
- Each action is tagged undoable, checkpointed or irreversible. Per-app checkpoints where possible.
- Every claim links to its evidence (frame or readout). A visible stuck state shows what has been tried. One lane
  per app so work does not block on one.

### Open questions

- **Node canonicalization:** raw UI-tree hashes are too fine (a blinking cursor makes a new state). States must
  be built from the tree plus readouts. This choice shapes everything.
- **Carve scoping:** how to say a carve holds only in one context without it poisoning others.
- **Branch merge-back:** what a merged branch contributes to the graph.
- **Calibration:** how late-no events on agent-chosen context feed back into the agent's discretion.

## Implementation note

The canvas is an HTML mock. The real client is expected to be a VexelRay app (see `vexelray-framework`,
`vexelray-gui`), not a web page, so treat the canvas as spec for layout, behaviour and tokens rather than
code to port.
