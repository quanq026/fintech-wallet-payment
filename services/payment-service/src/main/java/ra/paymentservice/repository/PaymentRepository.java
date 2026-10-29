package ra.paymentservice.repository;

import ra.paymentservice.entity.Payment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class PaymentRepository {
    private final JdbcTemplate db;

    public PaymentRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void insert(String id, String userId, String key, String fingerprint,
                       String accountId, String destination, long amount, String kind, String ip) {
        try {
            db.update("INSERT INTO payment_requests(id,user_id,idempotency_key,fingerprint,account_id,destination,amount,kind,request_ip) VALUES(?,?,?,?,?,?,?,?,?)",
                    id, userId, key, fingerprint, accountId, destination, amount, kind, ip);
        } catch (DuplicateKeyException duplicate) {
            // The service verifies the persisted key against the canonical payload.
        }
    }

    public Optional<Payment> findById(String id) {
        return db.query("SELECT * FROM payment_requests WHERE id=?", this::mapRow, id)
                .stream().findFirst();
    }

    public Payment findByUserAndKey(String user, String key) {
        return db.queryForObject("SELECT * FROM payment_requests WHERE user_id=? AND idempotency_key=?",
                this::mapRow, user, key);
    }

    public void updateProviderReference(String id, String providerReference) {
        db.update("UPDATE payment_requests SET provider_reference=? WHERE id=?", providerReference, id);
    }

    public void updateStatus(String id, String status) {
        db.update("UPDATE payment_requests SET status=? WHERE id=?", status, id);
    }

    public void updateTransaction(String id, String transactionId, String status) {
        db.update("UPDATE payment_requests SET transaction_id=?,status=? WHERE id=?",
                transactionId, status, id);
    }

    public List<Payment> findRecoverable() {
        return db.query("SELECT * FROM payment_requests WHERE kind<>'DEPOSIT' AND status NOT IN ('COMPLETED','REJECTED') ORDER BY created_at LIMIT 20",
                this::mapRow);
    }

    private Payment mapRow(ResultSet row, int rowNumber) throws SQLException {
        return new Payment(
                row.getString("id"), row.getString("user_id"), row.getString("idempotency_key"),
                row.getString("fingerprint"), row.getString("account_id"), row.getString("destination"),
                row.getLong("amount"), row.getString("kind"), row.getString("status"),
                row.getString("transaction_id"), row.getString("provider_reference"),
                row.getString("request_ip"),
                row.getTimestamp("created_at").toInstant());
    }
}
