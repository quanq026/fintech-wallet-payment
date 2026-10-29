package ra.auditservice.client;

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
public class RiskClient {
    private final String baseUrl;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    public RiskClient(@Value("${FRAUD_URL:http://127.0.0.1:8085}") String baseUrl, @Value("${internal.token}") String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public String flags() {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/flags"))
                    .timeout(Duration.ofSeconds(3)).header("X-Internal-Token", token).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("Risk request failed");
            return response.body();
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "RISK_UNAVAILABLE");
        }
    }
}
