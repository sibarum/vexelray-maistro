package dev.vexelray.maistro.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

class AgentSessionTest {

    /** A blocking input stream fed chunk by chunk; an empty chunk is the end. */
    static final class QueueInput extends InputStream {
        private final LinkedBlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private byte[] current = new byte[0];
        private int pos;
        private boolean eof;

        void feed(String text) {
            chunks.add(text.getBytes(StandardCharsets.UTF_8));
        }

        void end() {
            chunks.add(new byte[0]);
        }

        @Override
        public int read() {
            while (pos >= current.length) {
                if (eof) {
                    return -1;
                }
                try {
                    current = chunks.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
                pos = 0;
                if (current.length == 0) {
                    eof = true;
                    return -1;
                }
            }
            return current[pos++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            int c = read();
            if (c < 0) {
                return -1;
            }
            b[off] = (byte) c;
            int n = 1;
            while (n < len && pos < current.length) {
                b[off + n++] = current[pos++];
            }
            return n;
        }
    }

    /** A CLI stand-in: records stdin lines, answers each with whatever {@code responder} feeds to stdout. */
    static final class FakeProcess implements AgentProcess {
        final QueueInput out = new QueueInput();
        final QueueInput err = new QueueInput();
        final List<String> received = new CopyOnWriteArrayList<>();
        final CountDownLatch ended = new CountDownLatch(1);
        volatile int exitCode;
        volatile boolean destroyed;
        volatile boolean stdinClosed;
        Consumer<String> responder = line -> { };
        boolean exitOnStdinClose;

        private final OutputStream in = new OutputStream() {
            private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

            @Override
            public synchronized void write(int b) {
                if (b == '\n') {
                    String line = buf.toString(StandardCharsets.UTF_8);
                    buf.reset();
                    received.add(line);
                    responder.accept(line);
                } else {
                    buf.write(b);
                }
            }

            @Override
            public void close() {
                stdinClosed = true;
                if (exitOnStdinClose) {
                    exit(0);
                }
            }
        };

        void exit(int code) {
            exitCode = code;
            out.end();
            err.end();
            ended.countDown();
        }

        @Override
        public OutputStream stdin() {
            return in;
        }

        @Override
        public InputStream stdout() {
            return out;
        }

        @Override
        public InputStream stderr() {
            return err;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return ended.await(timeout, unit);
        }

        @Override
        public int exitCode() {
            return exitCode;
        }

        @Override
        public void destroyTree() {
            if (ended.getCount() > 0) {
                destroyed = true;
                exit(137);
            }
        }
    }

    static final class Collector implements AgentListener {
        final List<AgentEvent> events = new CopyOnWriteArrayList<>();
        final CountDownLatch exited = new CountDownLatch(1);
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        volatile CountDownLatch results = new CountDownLatch(1);

        @Override
        public void onEvent(AgentEvent event) {
            threads.add(Thread.currentThread());
            events.add(event);
            AgentListener.super.onEvent(event);
        }

        @Override
        public void onResult(AgentEvent.Result e) {
            results.countDown();
        }

        @Override
        public void onProcessExit(AgentEvent.ProcessExit e) {
            exited.countDown();
        }

        List<String> kinds() {
            List<String> k = new ArrayList<>();
            for (AgentEvent e : events) {
                k.add(e.getClass().getSimpleName());
            }
            return k;
        }
    }

    static final Path EXE = Path.of("claude.exe");

    @Test
    void commandLineCarriesTheOptions() {
        AgentSession.Options o = AgentSession.Options.of(EXE).withModel("sonnet").withPermissionMode("plan")
                .withAppendSystemPrompt("Be brief.\nReally.").withResume("abc-123")
                .withExtraArgs(List.of("--max-turns", "3"));
        assertEquals(List.of(EXE.toString(), "-p", "--input-format", "stream-json", "--output-format", "stream-json",
                "--verbose", "--include-partial-messages", "--model", "sonnet", "--permission-mode", "plan",
                "--append-system-prompt", "Be brief.\nReally.", "--resume", "abc-123", "--max-turns", "3"),
                o.command());
        assertEquals(List.of(EXE.toString(), "-p", "--input-format", "stream-json", "--output-format", "stream-json",
                "--verbose", "--include-partial-messages"), AgentSession.Options.of(EXE).withModel(" ").command());
    }

    @Test
    void childEnvironmentDropsHostVariablesAndAppliesOverrides() {
        Map<String, String> inherited = Map.of("PATH", "x", "CLAUDECODE", "1", "CLAUDE_CODE_ENTRYPOINT", "sdk",
                "CLAUDE_CONFIG_DIR", "keep", "DROP_ME", "y");
        Map<String, String> overrides = new HashMap<>();
        overrides.put("NEW", "v");
        overrides.put("DROP_ME", null);
        Map<String, String> env = AgentSession.Options.of(EXE).withEnvironment(overrides).childEnvironment(inherited);
        assertEquals(Map.of("PATH", "x", "CLAUDE_CONFIG_DIR", "keep", "NEW", "v"), env);

        Map<String, String> kept = AgentSession.Options.of(EXE).withScrubHostVariables(false)
                .childEnvironment(inherited);
        assertEquals("1", kept.get("CLAUDECODE"));
    }

    @Test
    void launcherGetsCommandDirectoryAndEnvironment() throws Exception {
        FakeProcess fake = new FakeProcess();
        List<Object> got = new ArrayList<>();
        Path dir = Path.of("work");
        AgentSession s = AgentSession.start(AgentSession.Options.of(EXE).withWorkingDirectory(dir), new Collector(),
                (command, workingDir, environment) -> {
                    got.add(command);
                    got.add(workingDir);
                    got.add(environment.containsKey("CLAUDECODE"));
                    return fake;
                });
        s.close();
        assertEquals(AgentSession.Options.of(EXE).command(), got.get(0));
        assertEquals(dir, got.get(1));
        assertEquals(false, got.get(2));
    }

    @Test
    void multiTurnConversationOverOneProcess() throws Exception {
        FakeProcess fake = new FakeProcess();
        List<String> turn = EventParserTest.fixture("synth-text-turn.jsonl");
        fake.responder = line -> {
            if (line.contains("\"type\":\"user\"")) {
                // the first turn gets the whole recorded turn; later turns skip the init line, as the CLI does
                List<String> lines = fake.received.size() == 1 ? turn : turn.subList(1, turn.size());
                fake.out.feed(String.join("\n", lines) + "\n");
            }
        };
        Collector c = new Collector();
        AgentSession s = AgentSession.start(AgentSession.Options.of(EXE), c, launcherOf(fake));
        assertTrue(s.isRunning());
        assertTrue(s.sessionId().isEmpty());

        assertTrue(s.send("Say hi in three words."));
        assertTrue(c.results.await(5, TimeUnit.SECONDS));
        assertEquals("5b0c1f8e-2d6a-4c1b-9f53-7e2a4d9c0a11", s.sessionId().orElseThrow());

        c.results = new CountDownLatch(1);
        assertTrue(s.send("And again, with \"quotes\"\nand a newline."));
        assertTrue(c.results.await(5, TimeUnit.SECONDS));

        assertEquals(2, fake.received.size());
        assertEquals("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"Say hi in three words.\"}}",
                fake.received.get(0));
        assertEquals("And again, with \"quotes\"\nand a newline.",
                Json.parse(fake.received.get(1)).get("message").get("content").asString());

        s.close();
        assertTrue(c.exited.await(5, TimeUnit.SECONDS));
        assertTrue(fake.stdinClosed);
        assertEquals(32, c.events.size()); // 16 + 15 events, then the exit
        assertEquals("ProcessExit", c.kinds().getLast());
        assertEquals(1, Collections.frequency(c.kinds(), "SessionInit"));
        assertFalse(s.isRunning());
        assertFalse(s.send("too late"));
        for (Thread t : c.threads) {
            assertTrue(t.getName().startsWith("maistro-agent-") && t.getName().endsWith("-out"), t.getName());
        }
    }

    @Test
    void malformedAndUnknownLinesDoNotStopTheStream() throws Exception {
        FakeProcess fake = new FakeProcess();
        Collector c = new Collector();
        AgentSession.start(AgentSession.Options.of(EXE), c, launcherOf(fake));
        fake.out.feed("garbage\n{\"type\":\"future_thing\"}\n\n");
        fake.out.feed(String.join("\n", EventParserTest.fixture("real-not-logged-in.jsonl")) + "\n");
        fake.exit(1);
        assertTrue(c.exited.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("Unknown", "Unknown", "SessionInit", "Status", "ApiError", "Result", "ProcessExit"),
                c.kinds());
        assertEquals(1, ((AgentEvent.ProcessExit) c.events.getLast()).exitCode());
    }

    @Test
    void aThrowingListenerDoesNotStopTheStream() throws Exception {
        FakeProcess fake = new FakeProcess();
        CountDownLatch exited = new CountDownLatch(1);
        List<String> seen = new CopyOnWriteArrayList<>();
        AgentListener l = new AgentListener() {
            @Override
            public void onStatus(AgentEvent.Status e) {
                seen.add(e.status());
                throw new IllegalStateException("listener bug");
            }

            @Override
            public void onProcessExit(AgentEvent.ProcessExit e) {
                exited.countDown();
            }
        };
        AgentSession.start(AgentSession.Options.of(EXE), l, launcherOf(fake));
        String status = "{\"type\":\"system\",\"subtype\":\"status\",\"status\":\"%s\"}\n";
        fake.out.feed(status.formatted("one") + status.formatted("two"));
        fake.exit(0);
        assertTrue(exited.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("one", "two"), seen);
    }

    @Test
    void interruptSendsAControlRequestAndKeepsTheProcess() throws Exception {
        FakeProcess fake = new FakeProcess();
        fake.responder = line -> {
            JsonValue v = Json.parse(line);
            if (v.get("type").asString().equals("control_request")) {
                fake.out.feed("{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\",\"request_id\":"
                        + Json.quote(v.get("request_id").asString()) + ",\"response\":{\"still_queued\":[]}}}\n");
            }
        };
        CountDownLatch acked = new CountDownLatch(1);
        List<AgentEvent.ControlResponse> responses = new CopyOnWriteArrayList<>();
        AgentSession s = AgentSession.start(AgentSession.Options.of(EXE), new AgentListener() {
            @Override
            public void onControlResponse(AgentEvent.ControlResponse e) {
                responses.add(e);
                acked.countDown();
            }
        }, launcherOf(fake));
        String id = s.interrupt();
        assertFalse(id.isEmpty());
        assertTrue(acked.await(5, TimeUnit.SECONDS));
        assertEquals(id, responses.getFirst().requestId());
        assertTrue(responses.getFirst().success());
        JsonValue sent = Json.parse(fake.received.getFirst());
        assertEquals("interrupt", sent.get("request").get("subtype").asString());
        assertTrue(s.isRunning());
        assertFalse(fake.destroyed);
        assertFalse(id.equals(s.interrupt()));
        s.close();
    }

    @Test
    void permissionAnswersAreControlResponses() throws Exception {
        FakeProcess fake = new FakeProcess();
        AgentSession s = AgentSession.start(AgentSession.Options.of(EXE), new AgentListener() {
        }, launcherOf(fake));
        assertTrue(s.allow("r1", Json.parse("{\"command\":\"ls\"}")));
        assertTrue(s.deny("r2", "no"));
        assertTrue(s.refuse("r3", "not handled"));
        assertEquals(List.of(
                "{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\",\"request_id\":\"r1\","
                        + "\"response\":{\"behavior\":\"allow\",\"updatedInput\":{\"command\":\"ls\"}}}}",
                "{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\",\"request_id\":\"r2\","
                        + "\"response\":{\"behavior\":\"deny\",\"message\":\"no\"}}}",
                "{\"type\":\"control_response\",\"response\":{\"subtype\":\"error\",\"request_id\":\"r3\","
                        + "\"error\":\"not handled\"}}"),
                fake.received);
        s.close();
    }

    @Test
    void permissionPromptsAreAskedForOnTheCommandLine() {
        List<String> c = AgentSession.Options.of(EXE).withPermissionPrompts().command();
        int at = c.indexOf("--permission-prompt-tool");
        assertTrue(at > 0, c.toString());
        assertEquals("stdio", c.get(at + 1));
    }

    @Test
    void closeKillsAProcessThatIgnoresItsInput() throws Exception {
        FakeProcess fake = new FakeProcess();
        Collector c = new Collector();
        AgentSession s = AgentSession.start(AgentSession.Options.of(EXE), c, launcherOf(fake));
        long t0 = System.nanoTime();
        s.close();
        s.close(); // twice is fine
        assertTrue(c.exited.await(5, TimeUnit.SECONDS));
        assertTrue(fake.destroyed);
        assertEquals(137, ((AgentEvent.ProcessExit) c.events.getLast()).exitCode());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 4000);
    }

    @Test
    void closeLetsAProcessThatExitsOnEndOfInputExit() throws Exception {
        FakeProcess fake = new FakeProcess();
        fake.exitOnStdinClose = true;
        Collector c = new Collector();
        AgentSession s = AgentSession.start(AgentSession.Options.of(EXE), c, launcherOf(fake));
        s.close();
        assertTrue(c.exited.await(5, TimeUnit.SECONDS));
        assertFalse(fake.destroyed);
        assertEquals(0, ((AgentEvent.ProcessExit) c.events.getLast()).exitCode());
    }

    @Test
    void closeFromTheListenerDoesNotDeadlock() throws Exception {
        FakeProcess fake = new FakeProcess();
        CountDownLatch exited = new CountDownLatch(1);
        AgentSession[] holder = new AgentSession[1];
        CountDownLatch started = new CountDownLatch(1);
        AgentSession s = AgentSession.start(AgentSession.Options.of(EXE), new AgentListener() {
            @Override
            public void onResult(AgentEvent.Result e) {
                try {
                    started.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                holder[0].close();
            }

            @Override
            public void onProcessExit(AgentEvent.ProcessExit e) {
                exited.countDown();
            }
        }, launcherOf(fake));
        holder[0] = s;
        started.countDown();
        fake.out.feed("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false}\n");
        assertTrue(exited.await(5, TimeUnit.SECONDS));
    }

    private static AgentProcess.Launcher launcherOf(FakeProcess fake) {
        return (command, workingDir, environment) -> fake;
    }
}
