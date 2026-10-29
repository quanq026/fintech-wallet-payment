package ra.auditservice.entity;

import java.sql.Timestamp;

public record AuditEvent(long sequenceId, String eventId, String payload,
                         String previousHash, String eventHash, Timestamp createdAt) {
}
