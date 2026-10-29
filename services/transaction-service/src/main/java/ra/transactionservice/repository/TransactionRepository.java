package ra.transactionservice.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ra.transactionservice.entity.Transaction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class TransactionRepository {
    private final JdbcTemplate db;

    public TransactionRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void insert(Transaction transaction) {
        db.update("""
                        INSERT INTO transactions(id,client_id,idempotency_key,fingerprint,source_account,
                            destination_account,amount,kind,status,request_ip)
                        VALUES(?,?,?,?,?,?,?,?,'NEW',?)
                        """, transaction.getId(), transaction.getClientId(), transaction.getIdempotencyKey(),
                transaction.getFingerprint(), transaction.getSourceAccount(), transaction.getDestinationAccount(),
                transaction.getAmount(), transaction.getKind(), transaction.getRequestIp());
    }

    public Transaction findByClientAndKey(String client, String key) {
        return db.queryForObject("SELECT * FROM transactions WHERE client_id=? AND idempotency_key=?",
                TransactionRepository::mapTransaction, client, key);
    }

    public Optional<Transaction> findById(String id) {
        return db.query("SELECT * FROM transactions WHERE id=?", TransactionRepository::mapTransaction, id)
                .stream().findFirst();
    }

    public boolean startProcessing(String id) {
        return db.update("""
                UPDATE transactions SET status='PROCESSING',error_code=NULL
                WHERE id=? AND status IN ('NEW','UNKNOWN')
                """, id) != 0;
    }

    public void markCompleted(String id, String journalId) {
        db.update("UPDATE transactions SET status='COMPLETED',journal_id=?,error_code=NULL WHERE id=?",
                journalId, id);
    }

    public void markRejected(String id, String code) {
        db.update("UPDATE transactions SET status='REJECTED',error_code=? WHERE id=?", code, id);
    }

    public void markUnknown(String id, String code) {
        db.update("UPDATE transactions SET status='UNKNOWN',error_code=? WHERE id=?", code, id);
    }

    public void markStaleProcessingUnknown() {
        db.update("""
                UPDATE transactions SET status='UNKNOWN'
                WHERE status='PROCESSING' AND updated_at<TIMESTAMPADD(SECOND,-15,CURRENT_TIMESTAMP)
                """);
    }

    public List<String> findRecoverableIds() {
        return db.queryForList("""
                SELECT id FROM transactions WHERE status IN ('NEW','UNKNOWN') ORDER BY created_at LIMIT 20
                """, String.class);
    }

    public List<Map<String, Object>> countByStatus() {
        return db.queryForList("SELECT status,COUNT(*) AS count FROM transactions GROUP BY status");
    }

    private static Transaction mapTransaction(ResultSet row, int rowNumber) throws SQLException {
        return new Transaction(row.getString("id"), row.getString("client_id"),
                row.getString("idempotency_key"), row.getString("fingerprint"),
                row.getString("source_account"), row.getString("destination_account"),
                row.getLong("amount"), row.getString("kind"), row.getString("status"),
                row.getString("journal_id"), row.getString("error_code"), row.getString("request_ip"));
    }
}
