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
      parser on it, and see the CLI apply an `updatedPermissions` answer and stop asking. Both shapes are the Agent
      SDK's documented ones and have not been seen from this CLI yet.
- [ ] Permissions, the rest: per-app trust (guide, gap 3), a reason typed with a Deny, and a view of the standing
      rules already set up (they live in Claude Code's settings files).
- [ ] Ctrl+B is checked with the map focused; whether the composer, when it has the keyboard, lets the chord
      through is not.
- [ ] Conversations, later: delete, rename, search, and Claude Code sessions started outside Maistro. A click on a
      row resumes through `onRowClicked`, which no script exercises yet (`resume.txt` uses Enter): rows are
      landmarked by id, which a script cannot know in advance.
- [ ] See `Archive.close()` save a conversation left mid-turn in a real shutdown. It is unit-tested, but a script
      cannot close the window (framework-notes FN-7).

## Later

- [ ] The predictive composer: multiline, ghost text, Tab to accept; then the daydreaming candidates (guide).
- [ ] Live app panes and the action timeline: `ottermate` runs as typed steps with their frames (guide, gaps 1-2).
- [ ] The context stream's KV cache, vector index and session graph sections are placeholders saying so.
- [ ] Hiding the map leaves the conversation at its fixed width with empty space beside it.
- [ ] Automation coverage: `src/test/ottermate/map-focus.txt` is the one checked-in script. Nothing runs it from
      `mvn`, and its stand-in CLI is a `.cmd`, so it is Windows-only.
