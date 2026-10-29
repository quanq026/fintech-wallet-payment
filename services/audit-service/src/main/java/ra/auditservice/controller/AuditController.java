package ra.auditservice.controller;

import ra.auditservice.service.AuditService;
import ra.auditservice.dto.response.AuditEventResponse;
import ra.auditservice.dto.response.AuditReconciliationResponse;
import java.util.List;
import ra.common.security.InternalAuthentication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AuditController {
    private final AuditService service;
    private final String token;

    public AuditController(AuditService service, @Value("${internal.token}") String token) {
        this.service = service;
        this.token = token;
    }

    @GetMapping({"/api/audit/events", "/api/notifications"})
    public List<AuditEventResponse> events(@RequestHeader("X-Internal-Token") String supplied, @RequestHeader("X-User-Id") String user) {
        authenticate(supplied);
        return service.events(user);
    }

    @GetMapping("/api/audit/reconciliation")
    public AuditReconciliationResponse reconcile(@RequestHeader("X-Internal-Token") String supplied, @RequestHeader("X-User-Id") String user) {
        requireAdmin(supplied, user);
        return service.reconcile();
    }

    @GetMapping("/api/risk/flags")
    public ResponseEntity<String> risk(@RequestHeader("X-Internal-Token") String supplied, @RequestHeader("X-User-Id") String user) {
        requireAdmin(supplied, user);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(service.riskFlags());
    }

    private void requireAdmin(String supplied, String user) {
        authenticate(supplied);
        if (!user.equals("admin")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private void authenticate(String supplied) {
        try {
            InternalAuthentication.require(token, supplied);
        } catch (ResponseStatusException error) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
