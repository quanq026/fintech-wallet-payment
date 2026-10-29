package ra.consoleclient.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import ra.consoleclient.client.WalletApiClient;
import ra.consoleclient.client.WalletApiClient.Response;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

// Exercises concurrent consoles and lost-response recovery against the local Gateway and MySQL ledger.
@EnabledIfEnvironmentVariable(named = "CONSOLE_INTEGRATION_TEST", matches = "true")
class WalletConsoleRecoveryIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void threeAccountConsolesShowIncomeForAnUnselectedWallet(@TempDir Path directory) throws Exception {
        WalletApiClient api = new WalletApiClient();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String alice = "live-alice-" + suffix, bob = "live-bob-" + suffix, merchant = "live-shop-" + suffix;
        String bobSelected = "live-bob-other-" + suffix;
        var wallets = List.of(Map.of("accountId", alice, "ownerId", "alice", "kind", "USER"),
                Map.of("accountId", bob, "ownerId", "bob", "kind", "USER"),
                Map.of("accountId", merchant, "ownerId", "admin", "kind", "MERCHANT"),
                Map.of("accountId", bobSelected, "ownerId", "bob", "kind", "USER"));
        for (var wallet : wallets) request(api, "admin", "POST", "/api/accounts", wallet, null);
        Response clearing = api.request("admin", "POST", "/api/accounts",
                Map.of("accountId", "bank-clearing", "ownerId", "admin", "kind", "CLEARING"), null);
        assertTrue(clearing.successful() || clearing.status() == 409);
        JsonNode deposit = request(api, "alice", "POST", "/api/deposits",
                Map.of("accountId", alice, "amount", 10_000L), UUID.randomUUID().toString());
        request(api, "alice", "POST", "/api/bank/settle/" + deposit.path("id").asText(), Map.of(), null);
        awaitCompleted(api, "alice", "/api/payments/" + deposit.path("id").asText());
        Path legacy = directory.resolve("console-wallets.json");
        JSON.writeValue(legacy.toFile(), Map.of("user", "alice", "wallets", wallets,
                "selected", Map.of("alice", alice, "bob", bobSelected, "admin", merchant)));

        var inputs = new ArrayList<PipedInputStream>();
        var writers = new ArrayList<PipedOutputStream>();
        var outputs = new ArrayList<ByteArrayOutputStream>();
        var pool = Executors.newFixedThreadPool(3);
        var runs = new ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (String actor : List.of("alice", "bob", "admin")) {
                PipedInputStream input = new PipedInputStream();
                PipedOutputStream writer = new PipedOutputStream(input);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                inputs.add(input); writers.add(writer); outputs.add(output);
                WalletConsole console = new WalletConsole(new Scanner(input, StandardCharsets.UTF_8),
                        new PrintStream(output, true, StandardCharsets.UTF_8), new WalletApiClient(), legacy, actor);
                runs.add(pool.submit(console::run));
            }
            for (var output : outputs) awaitOutput(output, "Chọn số > ");
            assertTrue(outputs.stream().noneMatch(output -> output.toString(StandardCharsets.UTF_8).contains("Đã khóa gửi tiền")));
            writers.get(0).write("3\n1\n2000\n1\n\n".getBytes(StandardCharsets.UTF_8));
            writers.get(0).flush();
            awaitOutput(outputs.get(1), "Nhận 2.000 VND vào ví " + bob);
            awaitOutput(outputs.get(1), "ví đang mở: " + bobSelected);
            awaitOutput(outputs.get(0), "Số dư hiện tại: 8.000 VND");
            assertEquals(0, balance(api, "bob", bobSelected));

            writers.get(0).write("4\n1\n1\n500\n1\n\n".getBytes(StandardCharsets.UTF_8));
            writers.get(0).flush();
            awaitOutput(outputs.get(2), "Số dư ví " + merchant + ": 500 VND");
            awaitOutput(outputs.get(0), "Số dư hiện tại: 7.500 VND");
            assertEquals(7_500, balance(api, "alice", alice));
            assertEquals(2_000, balance(api, "bob", bob));
            assertEquals(500, balance(api, "admin", merchant));
        } finally {
            for (var writer : writers) {
                try { writer.write("0\n".getBytes(StandardCharsets.UTF_8)); writer.flush(); }
                catch (java.io.IOException ignored) { }
            }
            try {
                for (var run : runs) run.get(10, TimeUnit.SECONDS);
            } finally {
                for (var writer : writers) writer.close();
                for (var input : inputs) input.close();
                pool.shutdownNow();
            }
        }
    }

    private static void awaitOutput(ByteArrayOutputStream output, String expected) throws InterruptedException {
        for (int attempt = 0; attempt < 120; attempt++) {
            if (output.toString(StandardCharsets.UTF_8).contains(expected)) return;
            Thread.sleep(100);
        }
        fail("Missing console update: " + expected + "\n" + output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void acceptedTransferSurvivesConsoleRestartAndDebitsExactlyOnce(@TempDir Path directory) throws Exception {
        WalletApiClient api = new WalletApiClient();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String source = "recover-alice-" + suffix, destination = "recover-bob-" + suffix;
        for (var wallet : List.of(Map.of("accountId", source, "ownerId", "alice", "kind", "USER"),
                Map.of("accountId", destination, "ownerId", "bob", "kind", "USER")))
            request(api, "admin", "POST", "/api/accounts", wallet, null);
        Response clearing = api.request("admin", "POST", "/api/accounts",
                Map.of("accountId", "bank-clearing", "ownerId", "admin", "kind", "CLEARING"), null);
        assertTrue(clearing.successful() || clearing.status() == 409);
        JsonNode deposit = request(api, "alice", "POST", "/api/deposits",
                Map.of("accountId", source, "amount", 10_000L), UUID.randomUUID().toString());
        request(api, "alice", "POST", "/api/bank/settle/" + deposit.path("id").asText(), Map.of(), null);
        awaitCompleted(api, "alice", "/api/payments/" + deposit.path("id").asText());
        assertEquals(10_000L, balance(api, "alice", source));

        Path bookmarks = directory.resolve("console-wallets.json");
        Path journal = directory.resolve("console-requests.json");
        RecordingClient first = new RecordingClient(journal, true);
        run("5\n2\n1\n" + source + "\n0\n3\n1\n" + destination + "\n1000\n1\n\n0\n", first, bookmarks);
        assertEquals(1, first.transfers.size());
        Submitted original = first.transfers.get(0);
        JsonNode saved = JSON.readTree(journal.toFile()).path("receipts").get(0);
        assertEquals(original.key(), saved.path("key").asText());
        assertEquals("", saved.path("id").asText());
        assertTrue(saved.path("attempted").asBoolean());
        JsonNode transaction = awaitCompleted(api, "alice", "/api/transactions/" + first.acceptedId);
        assertEquals(9_000L, balance(api, "alice", source));

        RecordingClient reopened = new RecordingClient(journal, false);
        String output = run("6\n1\n1\n\n0\n0\n", reopened, bookmarks);
        assertEquals(List.of(original), reopened.transfers);
        assertEquals(first.acceptedId, reopened.acceptedId);
        assertTrue(output.contains("HOÀN TẤT"), output);
        assertEquals(9_000L, balance(api, "alice", source));
        assertEquals(1_000L, balance(api, "bob", destination));
        JsonNode entries = request(api, "alice", "GET", "/api/accounts/" + source + "/entries", null, null);
        long debits = 0;
        for (JsonNode entry : entries)
            if (entry.path("journalId").asText().equals(transaction.path("journal_id").asText())
                    && entry.path("amount").asLong() == -1_000L) debits++;
        assertEquals(1, debits);
        JsonNode recovered = JSON.readTree(journal.toFile()).path("receipts").get(0);
        assertEquals(first.acceptedId, recovered.path("id").asText());
        assertEquals("COMPLETED", recovered.path("data").path("status").asText());
    }

    private static JsonNode request(WalletApiClient api, String user, String method, String path,
                                    Object body, String key) throws Exception {
        Response response = api.request(user, method, path, body, key);
        assertTrue(response.successful(), () -> path + " returned " + response.status());
        return response.body();
    }

    private static JsonNode awaitCompleted(WalletApiClient api, String user, String path) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            JsonNode state = request(api, user, "GET", path, null, null);
            if (state.path("status").asText().equals("COMPLETED")) return state;
            assertNotEquals("REJECTED", state.path("status").asText(), state.toString());
            Thread.sleep(250);
        }
        throw new AssertionError("Request did not complete: " + path);
    }

    private static long balance(WalletApiClient api, String user, String account) throws Exception {
        return request(api, user, "GET", "/api/accounts/" + account, null, null).path("balance").asLong();
    }

    private static String run(String input, WalletApiClient api, Path bookmarks) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Scanner scanner = new Scanner(input); PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            new WalletConsole(scanner, stream, api, bookmarks).run();
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private record Submitted(String user, JsonNode body, String key) { }

    private static final class RecordingClient extends WalletApiClient {
        private final Path journal;
        private final boolean loseResponse;
        private final List<Submitted> transfers = new ArrayList<>();
        private String acceptedId;

        private RecordingClient(Path journal, boolean loseResponse) {
            this.journal = journal;
            this.loseResponse = loseResponse;
        }

        @Override
        public Response request(String user, String method, String path, Object body, String key) throws Exception {
            boolean transfer = method.equals("POST") && path.equals("/api/transfers");
            if (transfer) {
                assertTrue(Files.isRegularFile(journal), "Intent must be saved before network I/O");
                JsonNode intent = JSON.readTree(journal.toFile()).path("receipts").get(0);
                assertEquals(key, intent.path("key").asText());
                assertEquals(user, intent.path("user").asText());
                JsonNode payload = JSON.readTree(JSON.writeValueAsBytes(body));
                assertEquals(payload, intent.path("body"));
                transfers.add(new Submitted(user, payload, key));
            }
            Response response = super.request(user, method, path, body, key);
            if (transfer) {
                assertTrue(response.successful());
                acceptedId = response.body().path("id").asText();
                if (loseResponse) throw new HttpTimeoutException("Accepted response intentionally discarded");
            }
            return response;
        }
    }
}
