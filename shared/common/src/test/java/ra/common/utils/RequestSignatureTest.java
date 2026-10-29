package ra.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequestSignatureTest {
    @Test
    void gatewaySignatureBindsExactIdempotencyKeyIncludingItsAbsence() {
        byte[] body = "{\"amount\":100}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String key = "be0ea94e-c350-4fc8-a6dc-65267f488f10";
        String signature = RequestSignature.sign("demo-key", "POST", "/api/transfers", "1700000000", "nonce", key, body);
        assertEquals("d64427cea0ea310021a83517efc16b0ed9a31608386455d48ff5f320dfc0885d", signature);
        assertTrue(RequestSignature.verify("demo-key", "POST", "/api/transfers", "1700000000", "nonce", key, body, signature));
        assertFalse(RequestSignature.verify("demo-key", "POST", "/api/transfers", "1700000000", "nonce", "13b11317-a9d3-4110-8e88-4b6e5f0277fd", body, signature));
        assertFalse(RequestSignature.verify("demo-key", "POST", "/api/transfers", "1700000000", "nonce", null, body, signature));
        String absent = RequestSignature.sign("demo-key", "POST", "/api/transfers", "1700000000", "nonce", null, body);
        assertTrue(RequestSignature.verify("demo-key", "POST", "/api/transfers", "1700000000", "nonce", null, body, absent));
        assertFalse(RequestSignature.verify("demo-key", "POST", "/api/transfers", "1700000000", "nonce", key, body, absent));
        String legacy = RequestSignature.sign("demo-key", "POST", "/api/transfers", "1700000000", "nonce", body);
        assertFalse(RequestSignature.verify("demo-key", "POST", "/api/transfers", "1700000000", "nonce", key, body, legacy));
        assertFalse(RequestSignature.verify("demo-key", "POST", "/api/transfers", "1700000000", "nonce", null, body, legacy));
    }

    @Test
    void bankWebhookRetainsItsFiveLineContract() {
        byte[] body = "{\"amount\":100}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String signature = RequestSignature.sign("demo-key", "POST", "/internal/webhook", "0", "bank", body);
        assertEquals("08b79e2d36c9d34ffe57c9120c7748d88d6a57726093f12aea2be8e5511b6c0b", signature);
        assertTrue(RequestSignature.verify("demo-key", "POST", "/internal/webhook", "0", "bank", body, signature));
    }

    @Test
    void authenticatesExactBytesAndRejectsTampering() {
        byte[] body = "{\"amount\":100}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String signature = RequestSignature.sign("demo-key", "POST", "/api/transfers", "1700000000", "nonce", body);
        assertTrue(RequestSignature.verify("demo-key", "POST", "/api/transfers", "1700000000", "nonce", body, signature));
        assertFalse(RequestSignature.verify("demo-key", "POST", "/api/transfers", "1700000000", "nonce", "{\"amount\":101}".getBytes(), signature));
        assertFalse(RequestSignature.verify("demo-key", "GET", "/api/transfers", "1700000000", "nonce", body, signature));
        assertFalse(RequestSignature.verify("demo-key", "POST", "/api/transfers", "1700000001", "nonce", body, signature));
        assertFalse(RequestSignature.verify("demo-key", "POST", "/api/transfers", "1700000000", "nonce", body, "zz"));
    }
}
