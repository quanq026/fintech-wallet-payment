package ra.common.utils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class RequestSignature {
    private RequestSignature() {
    }

    public static String hash(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sign(String key, String method, String target, String timestamp, String nonce, byte[] body) {
        return signCanonical(key, method + "\n" + target + "\n" + timestamp + "\n" + nonce + "\n" + hash(body));
    }

    public static String sign(String key, String method, String target, String timestamp, String nonce, String idempotencyKey, byte[] body) {
        return signCanonical(key, method + "\n" + target + "\n" + timestamp + "\n" + nonce + "\n" + hash(body)
                + "\n" + (idempotencyKey == null ? "" : idempotencyKey));
    }

    private static String signCanonical(String key, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(String key, String method, String target, String timestamp, String nonce, byte[] body, String signature) {
        if (signature == null || !signature.matches("[0-9a-f]{64}")) return false;
        return MessageDigest.isEqual(HexFormat.of().parseHex(sign(key, method, target, timestamp, nonce, body)), HexFormat.of().parseHex(signature));
    }

    public static boolean verify(String key, String method, String target, String timestamp, String nonce, String idempotencyKey, byte[] body, String signature) {
        if (signature == null || !signature.matches("[0-9a-f]{64}")) return false;
        return MessageDigest.isEqual(HexFormat.of().parseHex(sign(key, method, target, timestamp, nonce, idempotencyKey, body)), HexFormat.of().parseHex(signature));
    }
}
