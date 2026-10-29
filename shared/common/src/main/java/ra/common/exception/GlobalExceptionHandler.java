package ra.common.exception;

import ra.common.dto.response.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception error) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_PAYLOAD",
                "Check required fields, JSON types, identifier format and amount limits", null));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> missingHeader(MissingRequestHeaderException error) {
        int status = java.util.Set.of("X-Internal-Token", "X-Bank-Signature")
                .contains(error.getHeaderName()) ? 401 : 400;
        return ResponseEntity.status(status)
                .body(new ErrorResponse("MISSING_HEADER", null, error.getHeaderName()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(ErrorResponse.code(
                error.getReason() == null ? "REQUEST_REJECTED" : error.getReason()));
    }
}
