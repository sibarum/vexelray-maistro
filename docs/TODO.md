# TODO

Work on **Maistro** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.
The design is [design.md](design.md), whose *Milestones* are the longer list this is drawn from. The window's
layout and look are in [gui-design-guide.md](gui-design-guide.md).

## Next

- [ ] Ending a strand's process still happens on the GUI thread (New conversation, Abandon, Merge, Ctrl+O):
      `AgentSession.close` waits up to a second for the CLI to exit and two more for its reader. Starting one is the
      `Talk` job's now; ending one could be too.
- [ ] Signing in (guide, *Signing in*), after the status check, card, mid-session expiry and the card's Sign in: the
      account in Settings with Sign out. Also find out whether a CLI already running picks up a sign-in made after it
      started: the message kept on `authentication_failed` goes back to the same process.
- [ ] See the card's Sign in with the real CLI and a real browser, and SSO and a Console account at all. Only the
      stand-in (`signin-browser.txt`) has been driven; the real `auth login` was run once by hand from a terminal.
- [ ] The scripts assume the conversation pane is up, but the panes come back as the last run left them, and a run
      that hid the conversation makes every script fail at `click composer`. A launch option, or the scripts showing
      it, would make them independent of the last run.

- [ ] Agent tools: record a real `tools/call` from a logged-in run (only the handshake is recorded,
      `real-mcp-handshake.jsonl`). The relay (`src/test/relay/`) plays one by the specification, and Maistro answers it,
      shot included. Then slice 2, the driving tools (`click`, `type`, `key`, `settle`, `await`) through the permission
      prompt.
- [ ] The relay ends an interrupted turn as `error_during_execution`, a guess: record what a logged-in CLI sends.
- [ ] See a real reply drawn on the map. The pipeline is tested headless, and seen end to end only as far as the
      CLI's "Not logged in": the desktop app's bundled `claude.exe` needs `claude auth login` once.
- [ ] Record a real `can_use_tool` request from a logged-in run into `src/test/resources/agent/` and test the
      parser on it, and see the CLI apply an `updatedPermissions` answer and stop asking. Both shapes are the Agent
      SDK's documented ones and have not been seen from this CLI yet.
- [ ] Permissions, the rest: per-app trust (guide, gap 3), a reason typed with a Deny, and a view of the standing
      rules already set up (they live in Claude Code's settings files).
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
