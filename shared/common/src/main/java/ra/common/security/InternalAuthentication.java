package ra.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class InternalAuthentication {
    private InternalAuthentication() {
    }

    public static void require(String expected, String supplied) {
        if (supplied == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INTERNAL_AUTH_REQUIRED");
        }
    }
}
