package ra.transactionservice.entity;

public final class OutboxEvent {
    private final String eventId;
    private final String transactionId;
    private final String payload;

    public OutboxEvent(String eventId, String transactionId, String payload) {
        this.eventId = eventId;
        this.transactionId = transactionId;
        this.payload = payload;
    }

    public String getEventId() {
        return eventId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getPayload() {
        return payload;
    }
}
