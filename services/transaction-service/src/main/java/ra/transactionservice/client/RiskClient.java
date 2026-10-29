package ra.transactionservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class RiskClient {
    private final ObjectMapper json;
    private final String baseUrl;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();

    public RiskClient(ObjectMapper json, @Value("${fraud.url}") String baseUrl,
                      @Value("${internal.token}") String token) {
        this.json = json;
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public boolean accepted(String id, String user, long amount, String ip) throws Exception {
        var body = Map.of("transaction_id", id, "user_id", user, "amount", amount, "ip", ip);
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/check"))
                .timeout(Duration.ofSeconds(3)).header("X-Internal-Token", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Risk unavailable");
        return json.readTree(response.body()).path("allowed").asBoolean(false);
    }
}
