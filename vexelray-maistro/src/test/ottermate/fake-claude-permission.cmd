@rem A stand-in for claude.exe whose every turn asks to run Bash and waits for the answer. See FakeClaude.java.
@java "%~dp0FakeClaude.java" "%~dp0replay-permission-turn.jsonl" "%~dp0..\..\..\target\fake-claude-stdin.txt"
