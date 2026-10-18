# TODO

Work on **Maistro** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.
The design is [gui-design-guide.md](gui-design-guide.md); its *Gaps* and *Design principles* sections are the
longer list this is drawn from.

## Next

- [ ] See a real reply drawn on the map. The pipeline is tested headless, and seen end to end only as far as the
      CLI's "Not logged in": the desktop app's bundled `claude.exe` needs its own `/login`.
- [ ] Record a real `can_use_tool` request from a logged-in run into `src/test/resources/agent/` and test the
      parser on it. Its shape is the Agent SDK's documented one and has not been seen from this CLI yet.
- [ ] Permissions, the rest: "always allow" from `permission_suggestions` (answered as `updatedPermissions`),
      per-app trust (guide, gap 3), and a reason typed with a Deny.
- [ ] True forks: a turn from a node in a fresh Claude Code session given only its neighbourhood, shown as a
      branch, merged back as new edges (guide, *Replies built to be forked*). Today a node only narrows where the
      one session looks (`Conductor.fromNode`).
- [ ] Ctrl+N for a new conversation, and resuming an earlier one (`--resume`; the conductor already resumes after
      the process dies).

## Later

- [ ] The predictive composer: multiline, ghost text, Tab to accept; then the daydreaming candidates (guide).
- [ ] Live app panes and the action timeline: `ottermate` runs as typed steps with their frames (guide, gaps 1-2).
- [ ] The context stream's KV cache, vector index and session graph sections are placeholders saying so.
- [ ] Hiding the map leaves the conversation at its fixed width with empty space beside it.
- [ ] Automation coverage: `src/test/ottermate/map-focus.txt` is the one checked-in script. Nothing runs it from
      `mvn`, and its stand-in CLI is a `.cmd`, so it is Windows-only.
