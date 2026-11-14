# TODO

Work on **Maistro** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.
The design is [gui-design-guide.md](gui-design-guide.md); its *Gaps* and *Design principles* sections are the
longer list this is drawn from.

## Next

- [ ] A strand's first message starts Claude Code on the GUI thread (`Conductor.send`, and `release` after a sign-in
      check): since the 0.2 port every command is heard there, and starting a process is I/O (about 15 ms seen, plus
      writing `mcp.json`). Move the start off it, most likely by making the conductor a `@Component` that hears the
      commands as messages, or a `@Job` that starts the process and replies with it.
- [ ] Signing in (guide, *Signing in*), after the status check and card: sign in from the card's button
      (`claude auth login`, with Cancel; find out whether it needs a terminal or a pasted code), mid-session
      `authentication_failed` turning the account to signed out, and the account in Settings with Sign out.
- [ ] Record `claude auth status --json` from a signed-in CLI into `src/test/resources/agent/` and test the parser
      on it; only the signed-out shape has been seen.

- [ ] On Windows every double quote in a CLI argument is dropped (`ProcessBuilder` passes it unescaped), so
      `--append-system-prompt` reaches the CLI with `MapProtocol.INSTRUCTIONS`' quotes gone. `--mcp-config` sidesteps it
      with a file; the prompt does not yet.
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
