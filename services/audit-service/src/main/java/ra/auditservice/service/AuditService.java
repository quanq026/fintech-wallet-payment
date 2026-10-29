package ra.auditservice.service;

import ra.auditservice.dto.response.AuditEventResponse;
import ra.auditservice.dto.response.AuditReconciliationResponse;

import java.util.List;

public interface AuditService {
    void consume(String payload) throws Exception;

    List<AuditEventResponse> events(String user);

    AuditReconciliationResponse reconcile();

    String riskFlags();
}
