@rem A stand-in for claude.exe played by hand: see RelayClaude.java. Pass it as --claude=src/test/relay/relay-claude.cmd.
@rem Maistro's own classes (target/classes, there after any mvn compile) give it the JSON reader. Maistro's arguments
@rem are not passed on: cmd would re-read them, and the system prompt's < and > would become redirections. Only the
@rem first is looked at, plain as it always is: asked `auth status`, it is signed in.
@if "%1"=="auth" (
  echo {"loggedIn": true, "authMethod": "relay"}
  exit /b 0
)
@java -cp "%~dp0..\..\..\target\classes" "%~dp0RelayClaude.java" serve "%~dp0..\..\..\target\relay"
