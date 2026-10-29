CREATE TABLE payment_requests (
 id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(80) NOT NULL, idempotency_key VARCHAR(36) NOT NULL,
 fingerprint CHAR(64) NOT NULL, account_id VARCHAR(80) NOT NULL, destination VARCHAR(80) NOT NULL,
 amount BIGINT NOT NULL, kind VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 transaction_id VARCHAR(36), provider_reference VARCHAR(80), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uq_payment_user_key(user_id,idempotency_key),CHECK(amount>0)
) ENGINE=InnoDB;
