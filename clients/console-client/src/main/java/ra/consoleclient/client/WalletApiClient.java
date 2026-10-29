package ra.consoleclient.client;

import ra.common.utils.RequestSignature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

public class WalletApiClient {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl;
    private final Function<String, String> secretForUser;

    public WalletApiClient() {
        this(System.getenv().getOrDefault("GATEWAY_URL", "http://127.0.0.1:8080"),
                user -> System.getenv(user.toUpperCase(Locale.ROOT) + "_HMAC_KEY"));
    }

    public WalletApiClient(String baseUrl, Function<String, String> secretForUser) {
        this.baseUrl = baseUrl;
        this.secretForUser = secretForUser;
    }

    public record Response(int status, JsonNode body) {
        public boolean successful() {
            return status >= 200 && status < 300;
        }
    }

    public Response request(String user, String method, String path, Object body, String key) throws Exception {
        byte[] payload = body == null ? new byte[0] : json.writeValueAsBytes(body);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String secret = secretForUser.apply(user);
        if (secret == null || secret.isBlank())
            throw new IllegalStateException("Không xác thực được người dùng. Vui lòng liên hệ quản trị viên để kiểm tra kết nối.");
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(method.equals("GET") ? 4 : 20))
                .header("X-Client-Id", user).header("X-Timestamp", timestamp).header("X-Nonce", nonce)
                .header("X-Signature", RequestSignature.sign(secret, method, path, timestamp, nonce, key, payload))
                .header("Content-Type", "application/json");
        if (key != null) request.header("X-Idempotency-Key", key);
        request.method(method, payload.length == 0 ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(payload));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode value;
        try {
            value = json.readTree(response.body());
        } catch (IOException error) {
            throw new IOException("Phản hồi không đọc được; chưa xác định kết quả yêu cầu.");
        }
        if (value == null) throw new IOException("Phản hồi rỗng; chưa xác định kết quả yêu cầu.");
        return new Response(response.statusCode(), value);
    }
}
