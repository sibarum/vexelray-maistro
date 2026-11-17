# Agent tools: a proposal

A set of tools Maistro serves to the agent, alongside `apps`, `tree`, `find` and `shot`, for the work the agent now
does through the CLI's `Bash`, `Read`, `Write` and `Edit`. Not built yet. This is the design to agree before it
touches `Recipes.java`. The agents that use these tools, and how they are organized, are described in
[orchestration.md](orchestration.md). How they build is in [engineering-charter.md](engineering-charter.md).

## Why not just the shell

Most of what goes wrong for the agent on this machine goes wrong between the arguments it means and the bytes a
program receives:

- **Quoting has three or four layers on Windows**: JSON, then Git Bash or PowerShell 5.1, then `CommandLineToArgvW`
  or `cmd.exe`'s own rules, then the program. Each layer has its own escapes, and they interact. `ProcessBuilder`
  passes double quotes inside an argument unescaped (Maistro mends this for its own CLI in
  `AgentProcess.forWindows`), and PowerShell 5.1 mangles native arguments that contain quotes.
- **Encodings are guessed**: PowerShell 5.1 writes UTF-16 or the ANSI codepage unless told otherwise, the console
  codepage is not UTF-8, and `Set-Content` and `Out-File` disagree. A file can come back with a BOM it did not have,
  or with its line endings changed.
- **Nothing can be taken back.** An edit, a move or a delete through the shell leaves no record of what was there.
- **Nothing says what changed.** The agent re-reads to check, and a check that reads the wrong thing passes.

The tools remove the shell from the path. That fixes most of this by construction rather than by care.

## Principles

1. **One escaping rule: JSON's.** Every argument is a JSON value in an MCP `tools/call`, as RFC 8259 defines it, and
   it reaches the tool exactly as the agent wrote it. No shell parses it. A string is a sequence of Unicode
   characters, so it has no quoting, no globbing, no `$`, no backtick and no codepage. To check an argument, read
   it as JSON.
2. **Results are JSON too.** Each result has `structuredContent` with a declared `outputSchema` (MCP 2025-06-18),
   and a short text line beside it for reading. A path or a string that can contain a tab, a newline or a quote is
   never laid out bare in a TSV or a line. (`apps` does that today and would move to this.)
3. **Bytes are bytes, and text is declared.** A file is read as bytes. Text is decoded only with an encoding that is
   stated: one the agent asks for, or one that is detected and reported (`utf-8`, `utf-8-bom`, `utf-16le`, ...).
   Bytes that are not valid in that encoding are an error that names the offset. They are never replaced with
   U+FFFD. Writing keeps the file's own encoding, BOM and line endings unless the call says otherwise. Unicode is
   never normalized behind the agent's back: NFC and NFD stay as they were.
4. **Every change is undoable or says that it is not.** Each result is tagged `undoable`, `checkpointed` or
   `irreversible`, as in the design guide's acting-side requirements. An undoable result carries the `op` id that
   undoes it.
5. **Every change can be verified.** A mutation takes an optional `expect_sha256` of the file as the agent last saw
   it, and refuses to act if the file has changed since (optimistic concurrency). Each result gives the hashes
   `before` and `after`, the byte counts, and what changed: the lines touched, or the paths created and removed.
6. **Locks are opt-in and standard.** Any mutating call can take or respect an advisory lockfile (see below).
7. **Errors are results, not exceptions**, as `Tool.Result.error` already works. They say what was refused and what
   would make the call succeed.

## The toolsets

The tools come in six toolsets. Each is its own MCP server in Maistro's process, so the agent sees
`mcp__maistro-fs__read`, `mcp__maistro-git__commit` and so on. The human can then grant a whole toolset at once
(`mcp__maistro-git__*`), and a tool's name says where it belongs. `Toolset` serves several servers from one config
file.

| Server | For |
| --- | --- |
| `maistro` | the running VexelRay applications: `apps`, `tree`, `find`, `shot`, as today |
| `maistro-fs` | files and folders, the journal, undo, locks and blast radius |
| `maistro-diff` | making and applying diffs |
| `maistro-git` | git, without a shell |
| `maistro-web` | a headless browser |
| `maistro-mem` | the memory graph |
| `maistro-run` | running a program from an argv |

All of them live in `tools/`, with no `Gui`, and are unit-tested without a window. Mutating tools go through the
permission prompt by default. The read-only ones are on the `allowed` list, as `apps` and its siblings are. Every
mutation in every toolset goes through the one journal, so `undo` and `history` cover all of them.

### Filesystem (`maistro-fs`)

| Tool | Does | Tag |
| --- | --- | --- |
| `stat` | Size, times, kind, sha256, encoding and line-ending census for one path, or for each of several | read |
| `list` | One directory's entries, structured; optional glob; never recursive unless asked, with a cap | read |
| `tree` | A folder's outline to a depth, with sizes and counts, skipping what `.gitignore` ignores unless asked | read |
| `read` | Bytes or text, with an optional line or byte range; returns the encoding, the EOL style and the sha256 of the whole file | read |
| `search` | A regex or literal search across files under a root, with a cap; matches as `{path, line, column, text}` | read |
| `write` | Creates or replaces a file atomically (a temp file in the same folder, then an atomic move) | undoable |
| `edit` | Exact replacements `[{old, new, count}]` applied all at once or not at all; `count` must match, so an ambiguous edit is refused | undoable |
| `move`, `copy` | One path to another; refuses to overwrite unless asked, and then journals what it overwrote | undoable |
| `delete` | Moves the target into the journal, so it is recoverable. No permanent delete in this set | undoable |
| `mkdir` | With parents; journals which ones it made | undoable |
| `history` | This session's operations, newest first | read |
| `undo` | Reverses the given op (or the last N), newest first | undoable (redo) |
| `lock`, `unlock` | Takes or releases a lockfile explicitly, for a sequence of calls | — |
| `who`, `stability` | The other sessions and how hot a set of paths is (see *Blast radius*) | read |

`read` returns text with numbered lines only on request. By default it returns the text exactly, so what the agent
reads is what `edit` and `patch` match against.

### Diffs (`maistro-diff`)

`edit` suits one or two changes. A larger change to one file, or a change across several, is a patch: reviewable as
a whole, applied as a whole, and undone as a whole.

| Tool | Does | Tag |
| --- | --- | --- |
| `diff` | A unified diff between two of: a file, a git revision of it, a journal state of it, or proposed content | read |
| `check` | Applies a patch in memory and reports each hunk's result, without writing | read |
| `patch` | Applies a patch to one or more files, all files or none, as one journal op | undoable |

**Two forms of the same patch.** The agent may write a patch in either form, and every result returns both:

- **Unified diff text**, exactly as `git diff` writes it, with git's extended headers for a create, a delete, a
  rename and a mode change. It is standard, readable, and `git apply` accepts it, which makes it easy to check.
- **JSON hunks**: `[{path, expect_sha256, hunks: [{line, remove: [...], add: [...]}]}]`. Each line is a JSON
  string, so a line that itself starts with `+`, `-`, a space or `\` needs no thought. This is the form with nothing
  to escape.

**The rules a patch is held to.** None of them guess:

- **Context must match exactly.** There is no fuzz. A hunk may be found at a different line than its header says,
  but only where it matches exactly and uniquely. The shift is reported. A hunk that matches nowhere, or in more than
  one place, fails, and so does the whole patch. The failure shows the hunk beside the file's real lines at that
  place.
- **Line endings are part of a line.** A line in a CRLF file is matched without its `\r`, and an added line gets the
  file's own ending. A file with mixed endings is matched line by line as it is and is never normalized.
  `\ No newline at end of file` means what it means in git.
- **Encoding is the file's.** A patch applies to the decoded text and writes back in the file's encoding and BOM. A
  file that does not decode strictly cannot be patched as text.
- **Every file is checked against its `expect_sha256`, if one is given, before anything is written.** Then each file
  is written atomically. Whatever failed, nothing is left half-applied: on a failure partway, the files already
  written are restored from the journal.
- **Binary files** are refused in this design. A binary change is a `write`.

### Git (`maistro-git`)

Git runs as the `git` executable, through `maistro-run`'s process launcher, so no shell is involved. It is not run
through JGit, whose reflection and size do not suit the native image. Every call is parsed from git's own
unambiguous machine formats, never from what it prints for people:

- `-z` and porcelain v2 everywhere: `status --porcelain=v2 -z`, `diff --name-status -z`, `log -z` with a
  `--format` of `%x00`-separated fields. A path with a newline or a quote in it is no different from any other.
- `-c core.quotepath=false`, `-c i18n.logOutputEncoding=utf-8` and `LC_ALL=C`, so paths and messages arrive as
  UTF-8 and nothing is translated.
- Text the agent writes (a commit message, a tag message) goes in on stdin (`commit -F -`), never as an argument.
- `GIT_TERMINAL_PROMPT=0` and no pager, so git can never wait for input that will not come. Credentials stay with
  git's own credential manager. Maistro never sees them.

| Tool | Does | Tag |
| --- | --- | --- |
| `status` | Branch, upstream, ahead/behind, and each changed path with its index and work-tree state | read |
| `log` | Commits as `{hash, parents, author, date, subject, body}`, by range, path or count | read |
| `show`, `blame` | One commit with its diff; who last changed each line of a file | read |
| `diff` | Between any two of: the work tree, the index, a revision. Returned in both patch forms | read |
| `branches` | Local and remote branches, with their upstreams and last commits | read |
| `stage`, `unstage` | Paths, or hunks given as a patch, into or out of the index | undoable |
| `commit` | With a message on stdin. Optionally amend the last commit, if it has not been pushed | undoable |
| `branch`, `switch` | Creates, renames or deletes a branch; switches to one. Refuses on a dirty work tree unless told to stash | undoable |
| `stash` | Push, list, show, apply, drop | undoable |
| `worktree` | Add, list, remove | undoable |
| `fetch` | From a remote | read |
| `merge`, `rebase` | Non-interactive only; a conflict stops it and is reported path by path, with `abort` beside it | undoable |
| `push` | To a remote | irreversible |

**Undo** records the refs (`HEAD`, the branch, the stash) and the index (`git write-tree`) before each mutation.
Work-tree files are recorded as any other journal op. `undo` puts all three back. A `push` cannot be undone, and it
always asks the human.

**House rules are settings, checked before git runs:** protected branches nothing commits to directly (`master`
and `main` by default, as the user's own instructions require), no `--force` push, no `--no-verify`, and no
skipped signing. An attempt is refused with the rule that refused it. Git's own `index.lock` is respected and
reported, with its age, rather than deleted.

### Headless browser (`maistro-web`)

The agent reads documentation, checks a deployed page, and tests a web front end. The browser is a Chromium Maistro
already has on the machine (Edge on Windows, or Chrome), started headless with `--headless=new`, and driven over the
Chrome DevTools Protocol. That needs only `java.net.http.WebSocket` and Maistro's JSON, so there is no Node, no
Playwright and no reflection.

The tools speak the same vocabulary as `ottermate`, so the agent has one way of looking at both apps and pages:

| Tool | Does | Tag |
| --- | --- | --- |
| `open` | Opens a URL in a new or named tab; returns the tab, its final URL and its title | read |
| `text` | The page's readable text, main content first | read |
| `tree`, `find` | The accessibility tree, with a ref per node that can be acted on, as `ottermate`'s tree has | read |
| `shot` | A screenshot of the viewport or the whole page | read |
| `click`, `type`, `key`, `select`, `scroll` | By ref | irreversible |
| `wait` | For a selector, some text, or the network to settle | read |
| `console`, `network` | The page's console messages and requests | read |
| `eval` | JavaScript in the page, for inspection. Asks the human every time | irreversible |
| `tabs`, `close` | The open tabs; closing one | — |

**What keeps it safe:**

- **Its own profile**, under `~/.maistro/browser/`, holding none of the human's cookies, logins or extensions.
- **Downloads are off.** File uploads are refused.
- **Hosts are allowed by the human.** A new host asks once per session, with `localhost` and the `.test` names
  allowed by default, and that list is a setting.
- **What a page says is data, not instructions.** Every result from a page is wrapped as untrusted content, and
  the tool descriptions say so.
- **No credentials and no payments.** Typing into a password field, or into a field the page marks as a card
  number, is refused.

### Memory graph (`maistro-mem`)

Memories that outlast a conversation, as nodes in a graph with typed relationships between them, so a memory can
be found by what it relates to and not only by its words.

**A memory** is `{id, kind, title, body, scope, created, updated, version, source}`:

- `id` is a kebab-case slug, stable for the memory's life, so renaming its title breaks no link.
- `kind` is one of `user`, `feedback`, `project`, `reference` and `fact`.
- The graph belongs to one main agent ([orchestration.md](orchestration.md)), and is shared by that main's tasks
  and subtasks and by nobody else. Within it, `scope` is `main` (everything this main does), `project` (one
  repository) or `session` (one agent's life).
- `source` records the session and turn it came from, so every memory can be traced to its evidence.

**A relationship** is `{from, to, relation, note}`. The relation comes from a fixed set, so links can be queried
rather than read: `about`, `part-of`, `depends-on`, `supports`, `contradicts`, `supersedes`, `derived-from`,
`see-also`.

| Tool | Does | Tag |
| --- | --- | --- |
| `get` | One memory, and its neighbourhood to a depth | read |
| `search` | By text, kind, scope or relation; matches ranked, with their links | read |
| `list` | Memories by scope and kind, newest first | read |
| `add` | A new memory, optionally with its first links. Refuses an id that is taken, and suggests the memory it may duplicate | undoable |
| `update` | Changes fields, with `expect_version`, so two agents cannot overwrite each other unseen | undoable |
| `delete` | Leaves a tombstone. Refuses while links point at the memory, unless `unlink: true` | undoable |
| `link`, `unlink` | Adds or removes a relationship. Refuses one whose ends do not exist | undoable |
| `history` | Every change to one memory, with who made it | read |
| `export` | Writes a memory and its subtree (by relation and depth) to a folder as files, for other mains and for people | undoable |
| `open` | Reads a folder of exported memories as a read-only graph that `get` and `search` can include, and that this graph's links can point into | read |

**Stored as an append-only log**: `~/.maistro/mains/<main>/memory/log.jsonl`, one event per line (`added`, `updated`,
`deleted`, `linked`, `unlinked`), with each event's version and session. The graph is that log folded. This
gives history and undo directly, lets several agents append at once (each append holds the log's lockfile for one
line), and never loses an old version. A compacted snapshot beside the log keeps loading fast.

**Exported to disk, as documentation.** A main's own log is private to it, though it is still a plain file
that anyone can audit. To share what a main knows, it exports a subtree: one Markdown file per memory, named by its
id. Each file has front matter (`id`, `kind`, `version`, `source`) and its relationships as typed links
(`supersedes: [[older-id]]`), and the body is the memory itself. The files sit beside the project's other docs,
and are reviewed and committed like them. Exporting again rewrites only the files that changed, so a diff shows
exactly what the main learned. Another main reads them with `open` and never copies them into its own log. A
link into them names the file, so what it rests on stays visible. Starting a new main opens nothing: it begins
empty, and opens exported trees only when it reads that documentation.

**Rules the graph keeps:** no dangling link; `supersedes` marks the older memory as superseded but keeps it; a
`contradicts` link is shown on both memories whenever either is read, so a conflict is never silently half-seen.

The map pane could later show this graph beside the conversation's map. Claude Code's own memory folder could be
imported once as a source.

### Running a program (`maistro-run`)

| Tool | Does | Tag |
| --- | --- | --- |
| `run` | Starts a program from an `argv` array, with no shell: `{argv, cwd, env, stdin, timeout_ms, encoding}` → `{exit, stdout, stderr, timed_out}` | irreversible |

#### Windows arguments

`run` is the one place quoting cannot be designed away, because Windows hands a program one command-line string.
`run` builds that string itself with the documented `CommandLineToArgvW` rules (backslashes before a quote doubled,
quotes escaped, an argument quoted when it is empty or contains space or tab). It does not leave this to
`ProcessBuilder`. It grows out of `AgentProcess.forWindows`, which already escapes quotes for the CLI, made into a
module that `run`, git and the CLI launch all share. It refuses a `.cmd` or `.bat` target, which `cmd.exe` would re-parse under rules of its own
(the "BatBadBut" class of bug), unless the call says `"via": "cmd"`. Then the arguments are checked against
`cmd`'s metacharacters, and the call is refused if they cannot be passed safely. The quoting function is pure and
gets a table-driven test: argv in, command line out, and back through a parser written to the same rules.

Output is captured as bytes and decoded with the declared encoding, `utf-8` by default. Bytes that fail to decode
are reported, never silently replaced. Long output is cut with a count of what was left out.

## Undo: the journal

Each Maistro session has a journal under `~/.maistro/journal/<session>/`:

- `ops.jsonl`, one line per operation: `{op, tool, at, paths, before: {sha256|absent}, after: {sha256|absent}}`.
- `blobs/<sha256>`, the content each `before` refers to, stored once however often it recurs.

`undo op` restores each path's `before` only if its current hash is still that op's `after`. Otherwise it refuses
and names who changed it since; `"force": true` overrides that. Undo is itself an op, so it can be undone. The
journal is written before the change, so a crash leaves a record of what was about to happen. Retention could be
kept per session and pruned when the conversation is deleted, or by size; that choice is open (see the end).

`run` cannot be undone, and says so. Later, a `run` with `checkpoint: [paths]` could snapshot named paths first,
which makes it `checkpointed`.

## Blast radius: every agent knows where the others are working

Several agents can work on one machine at once: Maistro's strands and branches, other Claude Code sessions, and a
person in an editor. Each needs to know which folders are unstable before it changes them. Locks only cover what
someone thought to lock. A blast radius covers everything an agent has actually changed.

**Computed, not declared.** A session's blast radius comes only from the files it has edited and when. Nobody
forecasts their own plans, so the radius cannot be wrong about what happened. The journal already records this,
so it needs no new data.

- Every edit adds weight `1` to its file and to each folder above it, up to the session's working folder. The weight
  decays exponentially from the edit's time, with a half-life of 15 minutes (a setting).
- A path's **heat** for one session is the sum of its decayed weights. A file edited three times in five minutes is
  hotter than one edited once.
- The **stability** of a set of paths is the sum of every live session's heat on those paths and on the paths
  beneath them, the asking session's own heat excluded. Zero means nobody else has touched them lately.

**Who is counted.**

- **Maistro sessions** on this machine publish `~/.maistro/radius/<session>.jsonl`, one line per edit:
  `{path, at, op}`, beside a heartbeat file holding `{pid, session, cwd, started, beat}`. A session whose pid is
  dead, or whose beat is older than a minute, is gone; its edits still count as they decay.
- **Plain Claude Code sessions** publish nothing, but their transcripts do: every `Edit`, `Write` and
  `NotebookEdit` call in `~/.claude/projects/*/*.jsonl` carries a file path and a timestamp. Maistro reads those
  files' tails incrementally. The format is not documented, so this source is best-effort. A line it cannot read is
  skipped and counted, never guessed at.
- **Everything else** (a person, a build, `git checkout`) is caught only by the watcher below, and is counted as
  `unknown`.
- **The same account on other machines** is not counted yet. Its edits are plain `{path, at}` data, so it is a
  matter of syncing these files, not of a new protocol.

**Tools.**

| Tool | Does |
| --- | --- |
| `who` | The live sessions: their working folders, their hottest paths, and when they last edited |
| `stability` | For a set of paths: total heat, and each session behind it with its last edit there |

Write-type tools check stability before acting. Above a threshold (a setting), the result carries a warning that
names the sessions, and the call still goes ahead. Whether a hot folder should refuse a write instead is an open
question below.

**What it cannot see.** A session that has read a folder and is about to rewrite it is cold until its first write.
An explicit lock covers that gap.

## The watcher: being told when something changes underneath you

An agent's own blast radius is also where it is most exposed. A change someone else makes there is one the agent
needs to hear about before its next edit rests on a file it no longer has.

- **What is watched:** the folders where this session's own heat is above a floor. The set grows and shrinks as
  the session's heat moves.
- **How it is watched: a native extension per OS**, not the JDK's `WatchService`, which has no portable recursive
  watch, polls on macOS, and reports overflow as a bare event. The API module declares a `FolderWatch` service, and
  each platform module implements it over Panama FFM, as each already implements `ClaudeInstall`:
  - **Windows:** `ReadDirectoryChangesW` on each working folder, with the subtree flag and overlapped I/O. It
    reports renames as old-name and new-name pairs.
  - **Linux:** `inotify`, with one watch per folder in the hot set, added and removed as the set moves. Folders
    created inside a watched folder are added as they appear. `IN_Q_OVERFLOW` is reported as an overflow.
  - **macOS:** FSEvents on each working folder, recursive natively, with file-level events.
  Every implementation hands the same events to the same Java side: created, modified, deleted, renamed (old and
  new), and **overflow**. On an overflow, the watcher rescans the hot folders' hashes, so a change is never lost
  silently. Nothing in the stack wraps these calls yet (none of the sibling checkouts mentions them), so a
  framework-notes entry goes in with the first one: other VexelRay apps want a folder watch too.
- **What counts as external:** a change event, debounced for 200 ms, whose path and resulting sha256 do not match
  the after-state of an operation in this session's journal. Maistro's own writes therefore never trigger it.
  Each change is attributed when possible: to another Maistro session by its radius file, to a Claude Code session
  by its transcript, and otherwise to `unknown`.
- **How the agent hears it.** The model sees nothing between turns except what reaches its context, so there are
  two routes:
  - **Mid-turn:** the next result from any of Maistro's tools carries an `external_changes` block, listing each
    path, its kind (created, modified, deleted, renamed), its new sha256, and who changed it, if known.
  - **Between turns:** the next message the human sends is prefixed with the same block, as a merged branch's
    report already is. The message wears `told · 3 external changes`.
  Changes coalesce per path: five saves of one file are one entry, with the latest hash.
- **What follows from it:** a file the agent read whose hash has changed since appears in the block as `stale`. A
  write that names that file with the old `expect_sha256` is refused anyway; the block says so before the agent
  tries.
- **Threads:** the watcher runs on its own thread, never the frame loop. It writes into one inbox per session, which
  the tool threads and the conductor drain. It commits nothing to the model. A chip in the UI showing the pending
  changes is a later GUI slice.

## Locks

The convention is a lockfile `<target>.lock` beside the target, created with `CREATE_NEW` so that taking it is
atomic on every OS. Its content is JSON: `{pid, process, app: "maistro", session, op, created}`. A lockfile whose pid
is not alive (`ProcessHandle.of`) is stale. A stale lock is reported and broken only when the call says so.

Each mutating call takes `"lock": "none" | "respect" | "hold"`:

- `none` ignores lockfiles.
- `respect` refuses if another live owner holds the lock.
- `hold` takes the lock for the call's duration, and fails if it cannot.

`lock` and `unlock` hold it across several calls. Maistro releases every lock it holds when the session ends.
These are advisory, so a program that does not look will not see them. OS file locks (`FileChannel.lock`) are not
used: they belong to one process, and on Windows they are mandatory, which would break the human's editor.

## Paths

A path is absolute, or relative to the session's working folder. Forward and back slashes both work. Each result
gives back the absolute, normalized path it acted on, so the agent can see exactly what was touched. A path is
refused when Windows would silently change it (a trailing dot or space, a reserved name such as `CON` or `NUL`, a
`:` stream suffix). Mutations are confined to the working folder and any folders added to it, unless the human
allows more.

## Where it lands in the code

- `tools/files/`: `Codec` (detection and strict decoding), `Journal`, `Locks`, `Paths`, `FileTools`; and
  `tools/radius/`: `Heat` (the pure decay sum), `Sessions` (radius and heartbeat files, Claude Code transcripts),
  `Watcher`, `Inbox`; `tools/diff/`: `UnifiedDiff` (parse and print), `Hunks`, `Apply` (pure: text and a patch in,
  text or the failing hunk out); `tools/git/`: `Git` (argv building, `-z` parsers), `GitTools`, `HouseRules`;
  `tools/web/`: `Cdp` (the DevTools socket), `Browsers` (finding Edge or Chrome), `WebTools`, `HostRules`;
  `tools/mem/`: `MemoryLog`, `Graph` (the fold), `MemTools`; and `tools/run/`: `WindowsArgs`, `Launcher`,
  `RunTool`. Each is pure or behind a seam, as `Apps` is. `Heat` takes a clock, so its tests need no sleeping.
  The git parsers are tested on recorded `-z` output, including paths with newlines, quotes and non-ASCII.
- `Tool` gains an optional `outputSchema()`, and `Result` gains `structuredContent`. `Toolset` serves several
  `McpServer`s and writes one config naming all of them.
- `Recipes.toolset()` adds the toolsets and puts the read-only tools on `allowed`. This needs
  agreement before it is made. The model's shape does not change. Calls already run on `Toolset`'s virtual
  threads, and `Journal` and `Locks` hold their own locks, which no model listener reaches. The one new thread is
  the watcher's. The conductor drains its inbox when it sends a message: that is the threads change to agree, and
  it belongs with the TODO that moves the conductor off the GUI thread.
- Later, the action timeline (gap 2) shows each op with its tag and an Undo button. That is the GUI slice, after
  these.

## Slices, in order

1. Several servers in one `Toolset`; strict `Codec` and structured results; `stat`, `list`, `tree`, `read`,
   `search`.
2. `Journal`; `write`, `edit`, `undo`, `history`.
3. Diffs: `diff`, `check`, `patch`, in both forms, all-or-nothing across files.
4. `move`, `copy`, `delete`, `mkdir`.
5. `run`, with `WindowsArgs` and its table test. Git needs its launcher.
6. Git, the read-only tools first (`status`, `log`, `show`, `diff`, `blame`, `branches`), then those that change
   things, with the house rules and ref undo, and `push` last.
7. The memory graph: the log, the fold, and its tools.
8. Blast radius: `Heat`, the radius and heartbeat files, `who`, `stability`, and the warning on writes; then Claude
   Code transcripts as a source.
9. The watcher and the `external_changes` block, mid-turn and between turns: Windows' `FolderWatch` first, then
   Linux's and macOS's.
10. `Locks`, and the `lock` option on the mutating tools.
11. The headless browser: reading tools first (`open`, `text`, `tree`, `shot`), then acting, then `eval`.

Each slice gets unit tests and an `ottermate` check: a tool call played through the relay (`src/test/relay/`), with
its turn seen in the transcript. One thing is still unproven: the CLI's real `tools/call` has not been recorded from
a logged-in run (TODO.md), and these slices lean on it as much as `shot` does.

## Open questions

- **Alongside the CLI's own tools, or instead of them?** The proposal is alongside at first. Once these are proven,
  the CLI could be started with `--disallowedTools Bash,Read,Write,Edit` so the agent has only one way to act.
- **The default `lock` mode**: `respect` is proposed.
- **Journal retention**: kept with the conversation, or capped by size.
- **Advisory or enforced radius**: should a write into a folder another session has made hot be refused, or warn
  and go ahead? The proposal is to warn.
- **The same account on other machines**: when to sync radius files, and through what.
- **Reach, not just heat**: a change to `pom.xml` matters more than one to a doc. A weight per path, from what
  depends on it, could come later.
- **Which browser**: the Edge or Chrome already installed (proposed), or a pinned Chromium Maistro downloads and
  keeps, which gives the same version everywhere at the cost of a download of about 150 MB.
- **The memory graph and Claude Code's own memory**: replace it, import it once, or keep both in sync?
- **Memory search**: text and links at first. Whether to add embeddings, the context pane's vector index, is for
  later.
