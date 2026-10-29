package ra.auditservice.repository;

import ra.auditservice.entity.AuditEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AuditRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<AuditEvent> MAPPER = (row, index) -> new AuditEvent(
            row.getLong("sequence_id"), row.getString("event_id"), row.getString("payload"),
            row.getString("previous_hash"), row.getString("event_hash"), row.getTimestamp("created_at"));

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String lockLastHash() {
        return jdbc.queryForObject("SELECT last_hash FROM audit_state WHERE id=1 FOR UPDATE", String.class);
    }

    public String lastHash() {
        return jdbc.queryForObject("SELECT last_hash FROM audit_state WHERE id=1", String.class);
    }

    public Optional<AuditEvent> findByEventId(String id) {
        return jdbc.query("SELECT * FROM audit_events WHERE event_id=?", MAPPER, id).stream().findFirst();
    }

    public void insert(String id, String payload, String previous, String hash) {
        jdbc.update("INSERT INTO audit_events(event_id,payload,previous_hash,event_hash) VALUES(?,?,?,?)", id, payload, previous, hash);
        jdbc.update("UPDATE audit_state SET last_hash=? WHERE id=1", hash);
    }

    public List<AuditEvent> findRecent(String user) {
        if (user.equals("admin"))
            return jdbc.query("SELECT * FROM audit_events ORDER BY sequence_id DESC LIMIT 200", MAPPER);
        return jdbc.query("""
                SELECT * FROM audit_events WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.user_id'))=?
                 OR JSON_UNQUOTE(JSON_EXTRACT(payload,'$.source_owner'))=?
                 OR JSON_UNQUOTE(JSON_EXTRACT(payload,'$.destination_owner'))=?
                ORDER BY sequence_id DESC LIMIT 200
                """, MAPPER, user, user, user);
    }

    public List<AuditEvent> findAllOrdered() {
        return jdbc.query("SELECT * FROM audit_events ORDER BY sequence_id", MAPPER);
    }
}
