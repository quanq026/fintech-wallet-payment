package ra.auditservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditReconciliationResponse(boolean valid, long verifiedEvents, String lastHash) {
}
