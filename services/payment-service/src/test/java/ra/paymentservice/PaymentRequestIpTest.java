package ra.paymentservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import ra.common.utils.RequestSignature;
import ra.paymentservice.client.BankClient;
import ra.paymentservice.client.ServiceHttpClient;
import ra.paymentservice.client.TransactionClient;
import ra.paymentservice.controller.PaymentController;
import ra.paymentservice.dto.request.PaymentRequest;
import ra.paymentservice.entity.Payment;
import ra.paymentservice.repository.PaymentRepository;
import ra.paymentservice.service.PaymentService;
import ra.paymentservice.service.impl.PaymentServiceImpl;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentRequestIpTest {
    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private static final String KEY = "22222222-2222-2222-2222-222222222222";
    private static final String PROVIDER = "33333333-3333-3333-3333-333333333333";
    private static final String IP = "127.0.0.1";
    private final ObjectMapper json = new ObjectMapper();
    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final TransactionClient transactions = mock(TransactionClient.class);
    private final BankClient bank = mock(BankClient.class);
    private final PaymentServiceImpl service = new PaymentServiceImpl(repository, transactions, bank, json, "fixture-bank-secret");

    @Test
    void retryFromAnotherIpKeepsOriginalPersistedContext() throws Exception {
        Payment payment = payment("WITHDRAWAL", IP);
        when(transactions.getAccount("alice-wallet", "alice")).thenReturn(json.readTree("{\"kind\":\"USER\"}"));
        when(repository.findByUserAndKey("alice", KEY)).thenReturn(payment);
        when(repository.findById(ID)).thenReturn(Optional.of(payment));
        when(transactions.post(anyMap(), eq("alice"), eq(ID), eq(IP)))
                .thenReturn(json.readTree("{\"id\":\"" + ID + "\",\"status\":\"COMPLETED\"}"));

        service.create("WITHDRAWAL", "alice", KEY, new PaymentRequest("alice-wallet", null, 123), "198.51.100.42");

        verify(repository).insert(anyString(), eq("alice"), eq(KEY), eq(payment.fingerprint()),
                eq("alice-wallet"), eq("bank-clearing"), eq(123L), eq("WITHDRAWAL"), eq("198.51.100.42"));
        verify(transactions).post(anyMap(), eq("alice"), eq(ID), eq(IP));
    }

    @Test
    void depositPersistsInitiatorIpBeforeContactingBank() throws Exception {
        Payment payment = payment("DEPOSIT", IP);
        when(transactions.getAccount("alice-wallet", "alice")).thenReturn(json.readTree("{\"kind\":\"USER\"}"));
        when(repository.findByUserAndKey("alice", KEY)).thenReturn(payment);
        when(repository.findById(ID)).thenReturn(Optional.of(payment));
        when(bank.createPayment(ID, 123L, "alice"))
                .thenReturn(json.readTree("{\"provider_reference\":\"" + PROVIDER + "\"}"));

        service.create("DEPOSIT", "alice", KEY, new PaymentRequest("alice-wallet", null, 123), IP);

        var order = inOrder(repository, bank);
        order.verify(repository).insert(anyString(), eq("alice"), eq(KEY), anyString(),
                eq("alice-wallet"), eq("bank-clearing"), eq(123L), eq("DEPOSIT"), eq(IP));
        order.verify(bank).createPayment(ID, 123L, "alice");
        verify(transactions, never()).post(anyMap(), anyString(), anyString(), anyString());
    }

    @Test
    void callbackUsesBankActorWithOriginalInitiatorIp() throws Exception {
        Payment payment = payment("DEPOSIT", IP);
        when(repository.findById(ID)).thenReturn(Optional.of(payment));
        when(transactions.post(anyMap(), eq("admin"), eq(ID), eq(IP)))
                .thenReturn(json.readTree("{\"id\":\"" + ID + "\",\"status\":\"COMPLETED\"}"));
        String body = json.writeValueAsString(Map.of("payment_id", ID, "provider_reference", PROVIDER,
                "amount", 123L, "status", "PAID"));
        String signature = RequestSignature.sign("fixture-bank-secret", "POST", "/internal/webhook", "0", "bank",
                body.getBytes(StandardCharsets.UTF_8));

        service.webhook(signature, body);

        verify(transactions).post(eq(Map.of("sourceAccount", "bank-clearing", "destinationAccount", "alice-wallet",
                "amount", 123L, "kind", "DEPOSIT")), eq("admin"), eq(ID), eq(IP));
    }

    @Test
    void recoveryRetainsPersistedIpAndLegacyFallback() throws Exception {
        for (String ip : new String[]{IP, null}) {
            reset(repository, transactions);
            when(repository.findRecoverable()).thenReturn(List.of(payment("WITHDRAWAL", ip)));
            when(transactions.post(anyMap(), eq("alice"), eq(ID), anyString()))
                    .thenReturn(json.readTree("{\"id\":\"" + ID + "\",\"status\":\"COMPLETED\"}"));

            service.recover();

            verify(transactions).post(anyMap(), eq("alice"), eq(ID), eq(ip == null ? "bank-sandbox" : ip));
            verify(repository).updateTransaction(ID, ID, "COMPLETED");
        }
    }

    @Test
    void invalidIpCannotPersistOrPost() {
        for (String ip : new String[]{null, "", "bad/ip", " ", "a".repeat(81)}) {
            var error = assertThrows(ResponseStatusException.class, () -> service.create("WITHDRAWAL", "alice", KEY,
                    new PaymentRequest("alice-wallet", null, 123), ip));
            assertEquals(400, error.getStatusCode().value());
        }
        verifyNoInteractions(repository, transactions, bank);
    }

    @Test
    void controllerRequiresInternalAuthenticationBeforeForwardingIp() {
        PaymentService paymentService = mock(PaymentService.class);
        PaymentController controller = new PaymentController(paymentService, "fixture-internal-token");
        PaymentRequest request = new PaymentRequest("alice-wallet", null, 123);
        assertThrows(ResponseStatusException.class,
                () -> controller.withdrawal("invalid", "alice", KEY, IP, request));
        verifyNoInteractions(paymentService);

        controller.withdrawal("fixture-internal-token", "alice", KEY, IP, request);
        verify(paymentService).create("WITHDRAWAL", "alice", KEY, request, IP);
    }

    @Test
    void postingTransmitsIpWithSeparateInternalActor() throws Exception {
        AtomicReference<List<String>> headers = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/postings", exchange -> {
            headers.set(List.of(exchange.getRequestHeaders().getFirst("X-Client-Ip"),
                    exchange.getRequestHeaders().getFirst("X-User-Id"),
                    exchange.getRequestHeaders().getFirst("X-Idempotency-Key"),
                    exchange.getRequestHeaders().getFirst("X-Internal-Token")));
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"status\":\"COMPLETED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var client = new TransactionClient(new ServiceHttpClient(json, "fixture-internal-token"),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals("COMPLETED", client.post(Map.of("amount", 123L), "admin", ID, IP).path("status").asText());
            assertEquals(List.of(IP, "admin", ID, "fixture-internal-token"), headers.get());
        } finally {
            server.stop(0);
        }
    }

    private Payment payment(String kind, String ip) throws Exception {
        byte[] canonical = json.writeValueAsBytes(new TreeMap<>(Map.of("account", "alice-wallet",
                "destination", "bank-clearing", "amount", 123L, "kind", kind)));
        return new Payment(ID, "alice", KEY, RequestSignature.hash(canonical), "alice-wallet", "bank-clearing",
                123, kind, "PENDING", null, PROVIDER, ip, Instant.parse("2026-10-07T00:00:00Z"));
    }
}
