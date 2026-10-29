package ra.transactionservice.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ra.contracts.ledger.Account;
import ra.contracts.ledger.CreateAccountRequest;
import ra.contracts.ledger.EmptyRequest;
import ra.contracts.ledger.GetAccountRequest;
import ra.contracts.ledger.GetOperationRequest;
import ra.contracts.ledger.LedgerServiceGrpc;
import ra.contracts.ledger.ListEntriesRequest;
import ra.contracts.ledger.ListEntriesResponse;
import ra.contracts.ledger.Operation;
import ra.contracts.ledger.PostTransferRequest;
import ra.contracts.ledger.Reconciliation;

import java.util.concurrent.TimeUnit;

@Component
public class LedgerClient {
    private final ManagedChannel channel;
    private final LedgerServiceGrpc.LedgerServiceBlockingStub stub;

    public LedgerClient(@Value("${ledger.host}") String host, @Value("${ledger.port}") int port,
                        @Value("${internal.token}") String token) {
        channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER), token);
        stub = LedgerServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private LedgerServiceGrpc.LedgerServiceBlockingStub call() {
        return stub.withDeadlineAfter(5, TimeUnit.SECONDS);
    }

    public Account account(String id) {
        return call().getAccount(GetAccountRequest.newBuilder().setAccountId(id).build());
    }

    public Account create(String id, String owner, String kind) {
        return call().createAccount(CreateAccountRequest.newBuilder()
                .setAccountId(id).setOwnerId(owner).setKind(kind).build());
    }

    public Operation operation(String id) {
        return call().getOperation(GetOperationRequest.newBuilder().setOperationId(id).build());
    }

    public Operation post(String id, String from, String to, long amount) {
        return call().postTransfer(PostTransferRequest.newBuilder()
                .setOperationId(id).setSourceAccountId(from).setDestinationAccountId(to)
                .setAmount(amount).setCurrency("VND").build());
    }

    public ListEntriesResponse entries(String id) {
        return call().listEntries(ListEntriesRequest.newBuilder().setAccountId(id).build());
    }

    public Reconciliation reconcile() {
        return call().reconcile(EmptyRequest.getDefaultInstance());
    }

    @PreDestroy
    void stop() {
        channel.shutdownNow();
    }
}
