package ra.consoleclient.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import ra.common.utils.RequestSignature;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class WalletApiClientTest {
    @Test
    void retryKeepsIdempotencyPayloadAndActorButUsesFreshNonceAndSignature() throws Exception {
        List<Received> received = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                var headers = exchange.getRequestHeaders();
                received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                        headers.getFirst("X-Client-Id"), headers.getFirst("X-Timestamp"), headers.getFirst("X-Nonce"),
                        headers.getFirst("X-Signature"), headers.getFirst("X-Idempotency-Key"), exchange.getRequestBody().readAllBytes()));
                byte[] body = "{\"status\":\"REJECTED\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            WalletApiClient client = new WalletApiClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    user -> "test-only-" + user);
            Map<String, Object> body = Map.of("sourceAccount", "alice-ui", "destinationAccount", "bob-ui", "amount", 10_000L);
            for (int i = 0; i < 2; i++) {
                var response = client.request("alice", "POST", "/api/transfers", body, "test-retry-key");
                assertEquals(200, response.status());
                assertEquals("REJECTED", response.body().path("status").asText());
            }
            client.request("bob", "GET", "/api/accounts/bob-ui", null, null);

            assertEquals(3, received.size());
            Received first = received.get(0), retry = received.get(1), bob = received.get(2);
            assertEquals("alice", first.user);
            assertEquals(first.user, retry.user);
            assertEquals("test-retry-key", first.key);
            assertEquals(first.key, retry.key);
            assertArrayEquals(first.body, retry.body);
            assertNotEquals(first.nonce, retry.nonce);
            assertNotEquals(first.signature, retry.signature);
            for (Received request : received) {
                assertTrue(RequestSignature.verify("test-only-" + request.user, request.method, request.path,
                        request.timestamp, request.nonce, request.key, request.body, request.signature));
                assertFalse(RequestSignature.verify("wrong-test-key", request.method, request.path,
                        request.timestamp, request.nonce, request.key, request.body, request.signature));
                assertFalse(RequestSignature.verify("test-only-" + request.user, request.method, request.path,
                        request.timestamp, request.nonce, "changed-key", request.body, request.signature));
            }
            assertEquals("bob", bob.user);
            assertNull(bob.key);
            assertEquals(0, bob.body.length);
        } finally {
            server.stop(0);
        }
    }

    private record Received(String method, String path, String user, String timestamp, String nonce,
                            String signature, String key, byte[] body) { }
}
