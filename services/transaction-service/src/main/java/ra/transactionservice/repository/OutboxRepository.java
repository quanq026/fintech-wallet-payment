package ra.transactionservice.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ra.transactionservice.entity.OutboxEvent;

import java.util.List;

@Repository
public class OutboxRepository {
    private final JdbcTemplate db;

    public OutboxRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void insert(String transactionId, String payload) {
        db.update("""
                INSERT INTO outbox(event_id,transaction_id,payload) VALUES(?,?,?)
                ON DUPLICATE KEY UPDATE event_id=event_id
                """, transactionId, transactionId, payload);
    }

    public List<OutboxEvent> findPending() {
        return db.query("""
                SELECT event_id,transaction_id,CAST(payload AS CHAR) AS payload
                FROM outbox WHERE published=FALSE ORDER BY created_at LIMIT 50
                """, (row, rowNumber) -> new OutboxEvent(row.getString("event_id"),
                row.getString("transaction_id"), row.getString("payload")));
    }

    public void markPublished(String eventId) {
        db.update("UPDATE outbox SET published=TRUE WHERE event_id=?", eventId);
    }

    public Long countPending() {
        return db.queryForObject("SELECT COUNT(*) FROM outbox WHERE published=FALSE", Long.class);
    }
}
