package ra.transactionservice.exception;

import io.grpc.StatusRuntimeException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ra.transactionservice.controller.TransactionController;

import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = TransactionController.class)
public class GrpcExceptionHandler {
    @ExceptionHandler(StatusRuntimeException.class)
    public ResponseEntity<Map<String, String>> grpc(StatusRuntimeException exception) {
        int status = switch (exception.getStatus().getCode()) {
            case NOT_FOUND -> 404;
            case ALREADY_EXISTS -> 409;
            case INVALID_ARGUMENT -> 400;
            case FAILED_PRECONDITION -> 422;
            default -> 503;
        };
        return ResponseEntity.status(status).body(Map.of("code", exception.getStatus().getCode().name()));
    }
}
