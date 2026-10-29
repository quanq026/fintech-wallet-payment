package ra.transactionservice.entity;

public final class Transaction {
    private final String id;
    private final String clientId;
    private final String idempotencyKey;
    private final String fingerprint;
    private final String sourceAccount;
    private final String destinationAccount;
    private final long amount;
    private final String kind;
    private final String status;
    private final String journalId;
    private final String errorCode;
    private final String requestIp;

    public Transaction(String id, String clientId, String idempotencyKey, String fingerprint,
                       String sourceAccount, String destinationAccount, long amount, String kind,
                       String status, String journalId, String errorCode, String requestIp) {
        this.id = id;
        this.clientId = clientId;
        this.idempotencyKey = idempotencyKey;
        this.fingerprint = fingerprint;
        this.sourceAccount = sourceAccount;
        this.destinationAccount = destinationAccount;
        this.amount = amount;
        this.kind = kind;
        this.status = status;
        this.journalId = journalId;
        this.errorCode = errorCode;
        this.requestIp = requestIp;
    }

    public String getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getSourceAccount() {
        return sourceAccount;
    }

    public String getDestinationAccount() {
        return destinationAccount;
    }

    public long getAmount() {
        return amount;
    }

    public String getKind() {
        return kind;
    }

    public String getStatus() {
        return status;
    }

    public String getJournalId() {
        return journalId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getRequestIp() {
        return requestIp;
    }
}
