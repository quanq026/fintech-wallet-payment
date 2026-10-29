package ra.ledgerservice;

import ra.ledgerservice.grpc.LedgerGrpcServer;
import ra.ledgerservice.service.LedgerService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ra.contracts.ledger.*;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"ledger.grpc.port=0", "server.port=0", "ledger.internal-token=integration-test-token"})
@EnabledIfEnvironmentVariable(named = "LEDGER_INTEGRATION_TEST", matches = "true")
class LedgerIntegrationTest {
    @Autowired
    LedgerService ledger;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LedgerGrpcServer grpc;

    String account(String kind) {
        String id = UUID.randomUUID().toString();
        ledger.createAccount(CreateAccountRequest.newBuilder().setAccountId(id).setOwnerId("test-owner").setKind(kind).build());
        return id;
    }

    PostTransferRequest request(String source, String destination, long amount) {
        return PostTransferRequest.newBuilder().setOperationId(UUID.randomUUID().toString())
                .setSourceAccountId(source).setDestinationAccountId(destination).setAmount(amount).setCurrency("VND").build();
    }

    @Test
    void bankClearingIsPreparedAutomaticallyAndMetadataCannotBeReassigned() {
        var clearing = ledger.getAccount("bank-clearing");
        assertEquals("admin", clearing.getOwnerId());
        assertEquals("CLEARING", clearing.getKind());
        assertEquals("VND", clearing.getCurrency());
        assertEquals(clearing, ledger.createAccount(CreateAccountRequest.newBuilder()
                .setAccountId("bank-clearing").setOwnerId("admin").setKind("CLEARING").build()));
        assertThrows(IllegalArgumentException.class, () -> ledger.createAccount(CreateAccountRequest.newBuilder()
                .setAccountId("bank-clearing").setOwnerId("alice").setKind("USER").build()));
        assertEquals("admin", ledger.getAccount("bank-clearing").getOwnerId());
    }

    @Test
    void balancedImmutableIdempotentAndNoOverdraft() {
        String clearing = account("CLEARING"), a = account("USER"), b = account("USER");
        var funding = request(clearing, a, 100);
        var first = ledger.postTransfer(funding);
        assertEquals(first, ledger.postTransfer(funding));
        assertThrows(IllegalArgumentException.class, () -> ledger.postTransfer(funding.toBuilder().setAmount(101).build()));
        assertThrows(IllegalStateException.class, () -> ledger.postTransfer(request(a, b, 101)));
        ledger.postTransfer(request(a, b, 40));
        assertEquals(60, ledger.getAccount(a).getBalance());
        assertEquals(40, ledger.getAccount(b).getBalance());
        assertEquals(0, jdbc.queryForObject("select sum(amount) from ledger_entries where journal_id=?", Long.class, first.getJournalId()));
        assertThrows(Exception.class, () -> jdbc.update("update ledger_entries set amount=1 where journal_id=?", first.getJournalId()));
        assertThrows(Exception.class, () -> jdbc.update("insert into ledger_entries(journal_id,account_id,amount,currency) values(?,?,1,'VND')", first.getJournalId(), a));
        assertThrows(Exception.class, () -> jdbc.update("delete from ledger_journals where journal_id=?", first.getJournalId()));
    }

    @Test
    void concurrentDuplicateAndCompetingTransfers() throws Exception {
        String clearing = account("CLEARING"), a = account("USER"), b = account("USER");
        ledger.postTransfer(request(clearing, a, 100));
        var duplicate = request(a, b, 1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            var results = pool.invokeAll(IntStream.range(0, 100).mapToObj(i -> (Callable<String>) () -> ledger.postTransfer(duplicate).getJournalId()).toList());
            for (var result : results) assertEquals(results.get(0).get(), result.get());
            assertEquals(99, ledger.getAccount(a).getBalance());
            var competing = pool.invokeAll(IntStream.range(0, 100).mapToObj(i -> (Callable<Boolean>) () -> {
                try {
                    ledger.postTransfer(request(a, b, 2));
                    return true;
                } catch (IllegalStateException e) {
                    return false;
                }
            }).toList());
            long success = 0;
            for (var result : competing) if (result.get()) success++;
            assertEquals(49, success);
            assertEquals(1, ledger.getAccount(a).getBalance());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rejectsOverflowAndDirectUnbalancedJournal() {
        String clearing = account("CLEARING"), a = account("USER");
        ledger.postTransfer(request(clearing, a, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ledger.postTransfer(request(clearing, a, 1)));
        assertEquals(Long.MAX_VALUE, ledger.getAccount(a).getBalance());
        assertThrows(Exception.class, () -> jdbc.update("insert into ledger_journals(journal_id,operation_id,fingerprint,source_account_id,destination_account_id,amount,currency) values (?,?,?,?,?,1,'VND')",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "bad", clearing, a));
    }

    @Test
    void oppositeDirectionsAndRollback() throws Exception {
        String clearing = account("CLEARING"), a = account("USER"), b = account("USER");
        ledger.postTransfer(request(clearing, a, 100));
        ledger.postTransfer(request(clearing, b, 100));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            var results = pool.invokeAll(IntStream.range(0, 100).mapToObj(i -> (Callable<Operation>) () -> ledger.postTransfer(i % 2 == 0 ? request(a, b, 1) : request(b, a, 1))).toList());
            for (var result : results) assertNotNull(result.get());
            assertEquals(100, ledger.getAccount(a).getBalance());
            assertEquals(100, ledger.getAccount(b).getBalance());
        } finally {
            pool.shutdownNow();
        }
        var rejected = request(a, b, 101);
        assertThrows(IllegalStateException.class, () -> ledger.postTransfer(rejected));
        assertThrows(java.util.NoSuchElementException.class, () -> ledger.getOperation(rejected.getOperationId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from (select journal_id from ledger_entries group by journal_id having sum(amount)<>0 or count(*)<>2) bad", Long.class));
    }

    @Test
    void disjointAccountsAllowConcurrentDistinctOperations() throws Exception {
        var sources = new java.util.ArrayList<String>();
        var destinations = new java.util.ArrayList<String>();
        String clearing = account("CLEARING");
        for (int pair = 0; pair < 8; pair++) {
            String source = account("USER");
            sources.add(source);
            destinations.add(account("USER"));
            ledger.postTransfer(request(clearing, source, 100));
        }
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            var calls = IntStream.range(0, 100).mapToObj(index -> (Callable<Operation>) () ->
                    ledger.postTransfer(request(sources.get(index % 8), destinations.get(index % 8), 1))).toList();
            var operations = new java.util.HashSet<String>();
            for (var result : pool.invokeAll(calls)) {
                operations.add(result.get().getOperationId());
            }
            assertEquals(100, operations.size());
            for (int pair = 0; pair < 8; pair++) {
                long transferred = pair < 4 ? 13 : 12;
                assertEquals(100 - transferred, ledger.getAccount(sources.get(pair)).getBalance());
                assertEquals(transferred, ledger.getAccount(destinations.get(pair)).getBalance());
            }
            assertEquals(0, ledger.reconcile().getUnbalancedJournals());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void realGrpcAuthenticationAndAccountLookup() throws Exception {
        var server = (io.grpc.Server) org.springframework.test.util.ReflectionTestUtils.getField(grpc, "server");
        assertNotNull(server);
        assertEquals(java.util.List.of(new java.net.InetSocketAddress("127.0.0.1", grpc.boundPort())),
                server.getListenSockets());
        String account = account("USER");
        var channel = io.grpc.ManagedChannelBuilder.forAddress("127.0.0.1", grpc.boundPort()).usePlaintext().build();
        try {
            var stub = LedgerServiceGrpc.newBlockingStub(channel);
            var request = GetAccountRequest.newBuilder().setAccountId(account).build();
            var denied = assertThrows(io.grpc.StatusRuntimeException.class,
                    () -> stub.withDeadlineAfter(5, TimeUnit.SECONDS).getAccount(request));
            assertEquals(io.grpc.Status.Code.UNAUTHENTICATED, denied.getStatus().getCode());
            var headers = new io.grpc.Metadata();
            headers.put(io.grpc.Metadata.Key.of("x-internal-token", io.grpc.Metadata.ASCII_STRING_MARSHALLER), "integration-test-token");
            var authenticated = stub.withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(headers));
            assertEquals(account, authenticated.withDeadlineAfter(5, TimeUnit.SECONDS).getAccount(request).getAccountId());
            String clearing = account("CLEARING");
            var transfer = request(clearing, account, 100);
            ExecutorService pool = Executors.newFixedThreadPool(16);
            try {
                var results = pool.invokeAll(IntStream.range(0, 100).mapToObj(i -> (Callable<String>) () -> authenticated.withDeadlineAfter(10, TimeUnit.SECONDS).postTransfer(transfer).getJournalId()).toList());
                String journal = results.get(0).get();
                for (var result : results) assertEquals(journal, result.get());
                assertEquals(100, authenticated.withDeadlineAfter(5, TimeUnit.SECONDS).getAccount(request).getBalance());
                assertEquals(2, jdbc.queryForObject("select count(*) from ledger_entries where journal_id=?", Long.class, journal));
                var reconciliation = authenticated.withDeadlineAfter(5, TimeUnit.SECONDS).reconcile(EmptyRequest.getDefaultInstance());
                assertEquals(0, reconciliation.getTotalAmount());
                assertEquals(0, reconciliation.getUnbalancedJournals());
                assertEquals(0, reconciliation.getNegativeUserAccounts());
                assertEquals(reconciliation.getJournalCount() * 2, reconciliation.getEntryCount());
            } finally {
                pool.shutdownNow();
            }
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    void immutableTriggersAlsoBlockMigrationAdministrator() throws Exception {
        String a = account("CLEARING"), b = account("USER");
        var operation = ledger.postTransfer(request(a, b, 1));
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("LEDGER_MIGRATION_URL"), System.getenv("LEDGER_MIGRATION_USER"), System.getenv("LEDGER_MIGRATION_PASSWORD"))) {
            for (String sql : java.util.List.of("update ledger_entries set amount=2 where journal_id=?", "delete from ledger_entries where journal_id=?", "delete from ledger_journals where journal_id=?")) {
                try (var statement = connection.prepareStatement(sql)) {
                    statement.setString(1, operation.getJournalId());
                    var rejected = assertThrows(java.sql.SQLException.class, statement::executeUpdate);
                    assertEquals("45000", rejected.getSQLState());
                }
            }
        }
    }

    @Test
    void directProcedureRejectsChangedPayloadWithOriginalFingerprint() {
        String source = account("CLEARING"), destination = account("USER"), other = account("USER");
        var request = request(source, destination, 100);
        var posted = ledger.postTransfer(request);
        String fingerprint = jdbc.queryForObject("select fingerprint from ledger_journals where operation_id=?", String.class, request.getOperationId());
        for (var altered : java.util.List.of(request.toBuilder().setAmount(101).build(),
                request.toBuilder().setSourceAccountId(other).build(),
                request.toBuilder().setDestinationAccountId(other).build(),
                request.toBuilder().setCurrency("USD").build())) {
            var rejected = assertThrows(org.springframework.dao.DataAccessException.class, () ->
                    jdbc.update("call post_ledger_transfer(?,?,?,?,?,?)", altered.getOperationId(), fingerprint, altered.getSourceAccountId(), altered.getDestinationAccountId(), altered.getAmount(), altered.getCurrency()));
            assertEquals("45002", ((java.sql.SQLException) rejected.getMostSpecificCause()).getSQLState());
        }
        assertEquals(posted, ledger.getOperation(request.getOperationId()));
        assertEquals(100, ledger.getAccount(destination).getBalance());
        assertEquals(2, jdbc.queryForObject("select count(*) from ledger_entries where journal_id=?", Long.class, posted.getJournalId()));
    }

    @Test
    void malformedInputsNeverCreateJournalOrAccount() {
        String source = account("CLEARING"), destination = account("USER");
        var valid = request(source, destination, 1);
        for (var invalid : java.util.List.of(valid.toBuilder().setOperationId("1-1-1-1-1").build(),
                valid.toBuilder().setOperationId(valid.getOperationId().toUpperCase()).build(),
                valid.toBuilder().setAmount(0).build(), valid.toBuilder().setAmount(-1).build(),
                valid.toBuilder().setDestinationAccountId(source).build(), valid.toBuilder().setSourceAccountId("bad/id").build()))
            assertThrows(IllegalArgumentException.class, () -> ledger.postTransfer(invalid));
        long before = java.util.Objects.requireNonNull(
                jdbc.queryForObject("SELECT COUNT(*) FROM ledger_journals", Long.class));
        for (String operation : new String[]{null, "1-1-1-1-1", valid.getOperationId().toUpperCase()}) {
            var error = assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("call post_ledger_transfer(?,?,?,?,?,?)", operation, "0".repeat(64), source, destination, 1, "VND"));
            assertEquals("45002", ((java.sql.SQLException) error.getMostSpecificCause()).getSQLState());
        }
        var nullAmount = assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("call post_ledger_transfer(?,?,?,?,?,?)", valid.getOperationId(), "0".repeat(64), source, destination, null, "VND"));
        assertEquals("45002", ((java.sql.SQLException) nullAmount.getMostSpecificCause()).getSQLState());
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("call create_ledger_account(?,?,?)", null, "owner", "USER"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("call create_ledger_account(?,?,?)", UUID.randomUUID().toString(), "owner", "user"));
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM ledger_journals", Long.class));
        assertEquals(0, ledger.getAccount(destination).getBalance());
    }
}
