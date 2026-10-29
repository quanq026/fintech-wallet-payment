package ra.auditservice.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.sql.Timestamp;

public record AuditEventResponse(
        @JsonProperty("sequence_id") long sequenceId,
        @JsonProperty("event_id") String eventId,
        String payload,
        @JsonProperty("previous_hash") String previousHash,
        @JsonProperty("event_hash") String eventHash,
        @JsonProperty("created_at") Timestamp createdAt) {
}
