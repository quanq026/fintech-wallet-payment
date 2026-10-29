package ra.paymentservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import ra.common.security.InternalAuthentication;
import ra.paymentservice.dto.request.PaymentRequest;
import ra.paymentservice.dto.response.PaymentResponse;
import ra.paymentservice.service.PaymentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {
    private final PaymentService paymentService;
    private final String token;

    public PaymentController(PaymentService paymentService, @Value("${internal.token}") String token) {
        this.paymentService = paymentService;
        this.token = token;
    }

    @PostMapping("/api/deposits")
    public PaymentResponse deposit(@RequestHeader("X-Internal-Token") String suppliedToken,
                                   @RequestHeader("X-User-Id") String user,
                                   @RequestHeader("X-Idempotency-Key") String key,
                                   @RequestHeader("X-Client-Ip") String ip,
                                   @RequestBody PaymentRequest body) {
        InternalAuthentication.require(token, suppliedToken);
        return paymentService.create("DEPOSIT", user, key, body, ip);
    }

    @PostMapping("/api/withdrawals")
    public PaymentResponse withdrawal(@RequestHeader("X-Internal-Token") String suppliedToken,
                                      @RequestHeader("X-User-Id") String user,
                                      @RequestHeader("X-Idempotency-Key") String key,
                                      @RequestHeader("X-Client-Ip") String ip,
                                      @RequestBody PaymentRequest body) {
        InternalAuthentication.require(token, suppliedToken);
        return paymentService.create("WITHDRAWAL", user, key, body, ip);
    }

    @PostMapping("/api/payments")
    public PaymentResponse payment(@RequestHeader("X-Internal-Token") String suppliedToken,
                                   @RequestHeader("X-User-Id") String user,
                                   @RequestHeader("X-Idempotency-Key") String key,
                                   @RequestHeader("X-Client-Ip") String ip,
                                   @RequestBody PaymentRequest body) {
        InternalAuthentication.require(token, suppliedToken);
        return paymentService.create("PAYMENT", user, key, body, ip);
    }

    @GetMapping("/api/payments/{id}")
    public PaymentResponse get(@RequestHeader("X-Internal-Token") String suppliedToken,
                               @RequestHeader("X-User-Id") String user, @PathVariable String id) {
        InternalAuthentication.require(token, suppliedToken);
        return paymentService.get(id, user);
    }

    @PostMapping("/api/bank/settle/{id}")
    public JsonNode settle(@RequestHeader("X-Internal-Token") String suppliedToken,
                           @RequestHeader("X-User-Id") String user, @PathVariable String id) {
        InternalAuthentication.require(token, suppliedToken);
        return paymentService.settle(id, user);
    }

    @PostMapping("/internal/webhook")
    public PaymentResponse webhook(@RequestHeader("X-Bank-Signature") String signature,
                                   @RequestBody String body) {
        return paymentService.webhook(signature, body);
    }
}
