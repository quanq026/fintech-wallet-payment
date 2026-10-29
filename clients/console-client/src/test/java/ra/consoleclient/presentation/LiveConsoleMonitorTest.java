package ra.consoleclient.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ra.consoleclient.client.WalletApiClient;
import ra.consoleclient.client.WalletApiClient.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LiveConsoleMonitorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROMPT = "Chọn chức năng > ";

    @Test
    void firstBalanceIsSilentAndOnlyChangedBalancesRefreshThePrompt() {
        AtomicReference<Response> response = new AtomicReference<>(account("alice-live", 100_000));
        FakeClient client = new FakeClient((user, path) -> response.get());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var monitor = monitor(client, output)) {
            monitor.setContext("alice", "alice-live", PROMPT, true);
            monitor.pollOnce();
            monitor.pollOnce();
            assertEquals("", output.toString(StandardCharsets.UTF_8));

            response.set(account("alice-live", 110_000));
            monitor.pollOnce();
            String changed = output.toString(StandardCharsets.UTF_8);
            assertTrue(changed.matches("\\n\\[\\d{2}:\\d{2}:\\d{2}] Số dư ví alice-live: 110\\.000 VND\\n" + PROMPT));
            assertFalse(changed.contains("100.000"));
            monitor.pollOnce();
            assertEquals(changed, output.toString(StandardCharsets.UTF_8));
            assertEquals(4, client.calls.get());
        }
    }

    @Test
    void displayedBalanceSeedsTheFirstPollAndReseedingClearsFailureState() {
        AtomicReference<Response> response = new AtomicReference<>(account("alice-live", 110_000));
        FakeClient client = new FakeClient((user, path) -> response.get());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var monitor = monitor(client, output)) {
            monitor.seedBalance("alice", "alice-live", 100_000);
            monitor.pollOnce();
            assertEquals(0, client.calls.get());
            monitor.setContext("alice", "alice-live", PROMPT, true);
            monitor.pollOnce();
            assertTrue(output.toString(StandardCharsets.UTF_8).endsWith("Số dư ví alice-live: 110.000 VND\n" + PROMPT));

            response.set(new Response(503, null));
            monitor.pollOnce();
            output.reset();
            monitor.seedBalance("alice", "alice-live", 110_000);
            monitor.setContext("alice", "alice-live", PROMPT, true);
            response.set(account("alice-live", 110_000));
            monitor.pollOnce();
            assertEquals("", output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void failuresWarnOnceKeepTheBalanceAndReportRecoveryOnce() {
        AtomicReference<Response> response = new AtomicReference<>(account("bob-live", 20_000));
        FakeClient client = new FakeClient((user, path) -> {
            if (response.get() == null) throw new IOException("Never print transport details or credentials");
            return response.get();
        });
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var monitor = monitor(client, output)) {
            monitor.setContext("bob", "bob-live", PROMPT, true);
            monitor.pollOnce();
            response.set(null);
            monitor.pollOnce();
            String warning = output.toString(StandardCharsets.UTF_8);
            assertTrue(warning.contains("Chưa cập nhật được số dư ví bob-live"));
            assertFalse(warning.contains("credentials"));
            assertFalse(warning.contains("0 VND"));
            monitor.pollOnce();
            response.set(new Response(403, JSON.valueToTree(Map.of("code", "NOT_WALLET_OWNER"))));
            monitor.pollOnce();
            response.set(new Response(200, JSON.valueToTree(Map.of("accountId", "bob-live"))));
            monitor.pollOnce();
            response.set(account("alice-live", 0));
            monitor.pollOnce();
            assertEquals(warning, output.toString(StandardCharsets.UTF_8));

            response.set(account("bob-live", 20_000));
            monitor.pollOnce();
            String recovered = output.toString(StandardCharsets.UTF_8);
            assertTrue(recovered.contains("Đã kết nối lại. Số dư ví bob-live: 20.000 VND"));
            monitor.pollOnce();
            assertEquals(recovered, output.toString(StandardCharsets.UTF_8));
            response.set(account("bob-live", 21_000));
            monitor.pollOnce();
            assertTrue(output.toString(StandardCharsets.UTF_8).endsWith("Số dư ví bob-live: 21.000 VND\n" + PROMPT));
        }
    }

    @Test
    void pausingPreservesTheBaselineAndDoesNotPollDuringForms() {
        AtomicReference<Response> response = new AtomicReference<>(account("alice-live", 100_000));
        FakeClient client = new FakeClient((user, path) -> response.get());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var monitor = monitor(client, output)) {
            monitor.setContext("alice", null, PROMPT, true);
            monitor.pollOnce();
            assertEquals(0, client.calls.get());
            monitor.setContext("alice", "alice-live", PROMPT, true);
            monitor.pollOnce();
            monitor.setContext("alice", "alice-live", "Số tiền > ", false);
            response.set(account("alice-live", 120_000));
            monitor.pollOnce();
            assertEquals(1, client.calls.get());
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            monitor.setContext("alice", "alice-live", PROMPT, true);
            monitor.pollOnce();
            assertTrue(output.toString(StandardCharsets.UTF_8).endsWith("Số dư ví alice-live: 120.000 VND\n" + PROMPT));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("Số tiền"));
        }
    }

    @Test
    void oldResponsesCannotPrintAfterPauseSwitchSeedOrClose() throws Exception {
        for (String change : new String[]{"pause", "switch", "seed", "close"}) {
            CountDownLatch requested = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger sequence = new AtomicInteger();
            FakeClient client = new FakeClient((user, path) -> {
                if (sequence.incrementAndGet() == 1) return account("alice-live", 100_000);
                requested.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                return account("alice-live", 130_000);
            });
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            var monitor = monitor(client, output);
            try {
                monitor.setContext("alice", "alice-live", PROMPT, true);
                monitor.pollOnce();
                CompletableFuture<Void> pending = CompletableFuture.runAsync(monitor::pollOnce);
                try {
                    assertTrue(requested.await(3, TimeUnit.SECONDS));
                    switch (change) {
                        case "pause" -> {
                            monitor.setContext("alice", "alice-live", "Số tiền > ", false);
                            monitor.setContext("alice", "alice-live", PROMPT, true);
                        }
                        case "switch" -> monitor.setContext("bob", "bob-live", PROMPT, true);
                        case "seed" -> {
                            monitor.seedBalance("alice", "alice-live", 140_000);
                            monitor.setContext("alice", "alice-live", PROMPT, true);
                        }
                        case "close" -> monitor.close();
                    }
                } finally {
                    release.countDown();
                }
                pending.get(3, TimeUnit.SECONDS);
                assertEquals("", output.toString(StandardCharsets.UTF_8), change);
                if (change.equals("close")) {
                    monitor.start();
                    monitor.pollOnce();
                    assertEquals(2, client.calls.get());
                }
            } finally { monitor.close(); }
        }
    }

    private static LiveConsoleMonitor monitor(FakeClient client, ByteArrayOutputStream output) {
        return new LiveConsoleMonitor(client, new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    private static Response account(String wallet, long balance) {
        return new Response(200, JSON.valueToTree(Map.of("accountId", wallet, "balance", balance)));
    }

    @FunctionalInterface
    private interface Responder { Response respond(String user, String path) throws Exception; }

    private static final class FakeClient extends WalletApiClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final Responder responder;

        private FakeClient(Responder responder) {
            super("http://127.0.0.1:1", user -> "unused-test-key");
            this.responder = responder;
        }

        @Override
        public Response request(String user, String method, String path, Object body, String key) throws Exception {
            assertEquals("GET", method);
            assertEquals("/api/accounts/" + user + "-live", path);
            assertNull(body);
            assertNull(key);
            calls.incrementAndGet();
            return responder.respond(user, path);
        }
    }
}
