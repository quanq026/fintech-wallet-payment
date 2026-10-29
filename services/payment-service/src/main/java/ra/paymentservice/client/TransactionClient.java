package ra.paymentservice.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class TransactionClient {
    private final ServiceHttpClient http;
    private final String engineUrl;

    public TransactionClient(ServiceHttpClient http, @Value("${engine.url}") String engineUrl) {
        this.http = http;
        this.engineUrl = engineUrl;
    }

    public JsonNode getAccount(String accountId, String user) {
        return http.request(engineUrl + "/api/accounts/" + accountId, "GET", null, user, null);
    }

    public JsonNode getTransaction(String transactionId) {
        return http.request(engineUrl + "/api/transactions/" + transactionId, "GET", null, "admin", null);
    }

    public JsonNode post(Map<String, Object> command, String user, String paymentId, String ip) {
        return http.request(engineUrl + "/internal/postings", "POST", command, user, paymentId, ip);
    }
}
