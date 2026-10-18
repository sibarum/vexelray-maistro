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
- [ ] Permissions. Claude Code runs with `--permission-mode default` and no prompt tool, so anything that needs
      approval is denied. Inline approve/deny in the transcript, over `--permission-prompt-tool` (guide, gap 3).
- [ ] Click a map node to focus it, and start a turn from it with its neighbourhood (`MapGraph.neighbourhood`,
      `MapProtocol.render`). The map is a picture today and is not hit-tested.
- [ ] Ctrl+N for a new conversation, and resuming an earlier one (`--resume`; the conductor already resumes after
      the process dies).

## Later

- [ ] The predictive composer: multiline, ghost text, Tab to accept; then the daydreaming candidates (guide).
- [ ] Live app panes and the action timeline: `ottermate` runs as typed steps with their frames (guide, gaps 1-2).
- [ ] The context stream's KV cache, vector index and session graph sections are placeholders saying so.
- [ ] Hiding the map leaves the conversation at its fixed width with empty space beside it.
- [ ] Automation coverage: a checked-in `ottermate` script through the main path (the e2e one used so far lives
      in `target/`).
