package ra.auditservice.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.kafka.annotation.KafkaListener;
import ra.auditservice.repository.AuditRepository;
import ra.auditservice.client.RiskClient;
import ra.auditservice.dto.response.AuditEventResponse;
import ra.auditservice.dto.response.AuditReconciliationResponse;
import ra.auditservice.service.AuditService;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import ra.common.validation.InputRules;

@Service
public class AuditServiceImpl implements AuditService {
    private final AuditRepository repository;
    private final ObjectMapper json;
    private final RiskClient risk;

    public AuditServiceImpl(AuditRepository repository, ObjectMapper json, RiskClient risk) {
        this.repository = repository;
        this.json = json;
        this.risk = risk;
    }

    static String hash(String data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @KafkaListener(topics = "financial-transactions")
    @Transactional(rollbackFor = Exception.class)
    public void consume(String payload) throws Exception {
        var event = json.readTree(payload);
        if (!event.isObject() || event.size() != 11) throw new IllegalArgumentException("Invalid event schema");
        for (String field : java.util.List.of("event_id", "transaction_id", "user_id", "source", "destination", "kind", "journal_id", "ip", "source_owner", "destination_owner"))
            if (!event.path(field).isTextual()) throw new IllegalArgumentException("Invalid event field");
        String id = InputRules.uuid(event.get("event_id").textValue());
        if (!id.equals(InputRules.uuid(event.get("transaction_id").textValue())))
            throw new IllegalArgumentException("Event transaction mismatch");
        InputRules.uuid(event.get("journal_id").textValue());
        InputRules.identifier(event.get("source").textValue());
        InputRules.identifier(event.get("destination").textValue());
        InputRules.client(event.get("user_id").textValue());
        InputRules.client(event.get("source_owner").textValue());
        InputRules.client(event.get("destination_owner").textValue());
        if (!event.path("amount").isIntegralNumber() || !event.path("amount").canConvertToLong())
            throw new IllegalArgumentException("Invalid event amount");
        InputRules.amount(event.get("amount").longValue());
        if (!java.util.Set.of("TRANSFER", "DEPOSIT", "WITHDRAWAL", "PAYMENT").contains(event.get("kind").textValue()) || event.get("source").textValue().equals(event.get("destination").textValue()))
            throw new IllegalArgumentException("Invalid event transfer");
        if (!event.get("ip").textValue().matches("[A-Za-z0-9_.:-]{1,80}"))
            throw new IllegalArgumentException("Invalid event IP");
        String previous = repository.lockLastHash();
        var duplicate = repository.findByEventId(id);
        if (duplicate.isPresent()) {
            if (!duplicate.get().payload().equals(payload))
                throw new IllegalStateException("Event id reused with different payload");
            return;
        }
        String hash = hash(previous + "\n" + payload);
        repository.insert(id, payload, previous, hash);
    }

    public List<AuditEventResponse> events(String user) {
        return repository.findRecent(user).stream().map(event -> new AuditEventResponse(
                event.sequenceId(), event.eventId(), event.payload(), event.previousHash(),
                event.eventHash(), event.createdAt())).toList();
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public AuditReconciliationResponse reconcile() {
        String previous = "0".repeat(64);
        long checked = 0;
        for (var event : repository.findAllOrdered()) {
            if (!previous.equals(event.previousHash()) || !hash(previous + "\n" + event.payload()).equals(event.eventHash()))
                return new AuditReconciliationResponse(false, checked, null);
            previous = event.eventHash();
            checked++;
        }
        return new AuditReconciliationResponse(previous.equals(repository.lastHash()), checked, previous);
    }

    public String riskFlags() {
        return risk.flags();
    }
}
