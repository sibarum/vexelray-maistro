@rem A stand-in for claude.exe: answers every message with one recorded turn, and keeps what it is sent in
@rem target/fake-claude-stdin.txt. See FakeClaude.java.
@java "%~dp0FakeClaude.java" "%~dp0replay-map-turn.jsonl" "%~dp0..\..\..\target\fake-claude-stdin.txt"
