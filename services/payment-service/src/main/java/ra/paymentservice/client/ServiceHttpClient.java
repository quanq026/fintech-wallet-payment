package ra.paymentservice.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class ServiceHttpClient {
    private final ObjectMapper json;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();

    public ServiceHttpClient(ObjectMapper json, @Value("${internal.token}") String token) {
        this.json = json;
        this.token = token;
    }

    public JsonNode request(String url, String method, Object payload, String user, String key) {
        return request(url, method, payload, user, key, null);
    }

    public JsonNode request(String url, String method, Object payload, String user, String key, String ip) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("X-Internal-Token", token)
                    .header("X-User-Id", user)
                    .header("Content-Type", "application/json");
            if (key != null) {
                builder.header("X-Idempotency-Key", key);
            }
            if (ip != null) {
                builder.header("X-Client-Ip", ip);
            }
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                builder.POST(HttpRequest.BodyPublishers.ofString(encode(payload)));
            }
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400
                    && !(url.endsWith("/internal/postings") && response.statusCode() == 422)) {
                throw new ResponseStatusException(
                        HttpStatus.valueOf(response.statusCode()), "DEPENDENCY_REJECTED");
            }
            return json.readTree(response.body());
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
        }
    }

    private String encode(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BAD_PAYLOAD");
        }
    }
}
