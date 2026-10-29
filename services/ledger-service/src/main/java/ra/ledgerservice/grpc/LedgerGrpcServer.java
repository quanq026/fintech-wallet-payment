package ra.ledgerservice.grpc;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
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
import ra.ledgerservice.service.LedgerService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

@Component
public class LedgerGrpcServer extends LedgerServiceGrpc.LedgerServiceImplBase implements SmartLifecycle {
    private final LedgerService ledger;
    private final int port;
    private final byte[] token;
    private Server server;

    public LedgerGrpcServer(LedgerService ledger, @Value("${ledger.grpc.port}") int port,
                            @Value("${ledger.internal-token}") String token) {
        if (token.isBlank()) {
            throw new IllegalArgumentException("LEDGER_INTERNAL_TOKEN is required");
        }
        this.ledger = ledger;
        this.port = port;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void start() {
        ServerInterceptor authentication = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                String supplied = headers.get(Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER));
                if (supplied == null || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
                    call.close(Status.UNAUTHENTICATED.withDescription("Internal authentication required"), new Metadata());
                    return new ServerCall.Listener<>() {
                    };
                }
                return next.startCall(call, headers);
            }
        };
        try {
            server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", port))
                    .addService(ServerInterceptors.intercept(this, authentication))
                    .build()
                    .start();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot start Ledger gRPC", error);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    public int boundPort() {
        return server.getPort();
    }

    @Override
    public void createAccount(CreateAccountRequest request, StreamObserver<Account> response) {
        reply(response, () -> ledger.createAccount(request));
    }

    @Override
    public void getAccount(GetAccountRequest request, StreamObserver<Account> response) {
        reply(response, () -> ledger.getAccount(request.getAccountId()));
    }

    @Override
    public void postTransfer(PostTransferRequest request, StreamObserver<Operation> response) {
        reply(response, () -> ledger.postTransfer(request));
    }

    @Override
    public void getOperation(GetOperationRequest request, StreamObserver<Operation> response) {
        reply(response, () -> ledger.getOperation(request.getOperationId()));
    }

    @Override
    public void listEntries(ListEntriesRequest request, StreamObserver<ListEntriesResponse> response) {
        reply(response, () -> ledger.listEntries(request.getAccountId()));
    }

    @Override
    public void reconcile(EmptyRequest request, StreamObserver<Reconciliation> response) {
        reply(response, ledger::reconcile);
    }

    private <T> void reply(StreamObserver<T> response, Supplier<T> work) {
        try {
            response.onNext(work.get());
            response.onCompleted();
        } catch (IllegalArgumentException error) {
            response.onError(Status.INVALID_ARGUMENT.withDescription(error.getMessage()).asRuntimeException());
        } catch (NoSuchElementException error) {
            response.onError(Status.NOT_FOUND.withDescription(error.getMessage()).asRuntimeException());
        } catch (IllegalStateException error) {
            response.onError(Status.FAILED_PRECONDITION.withDescription(error.getMessage()).asRuntimeException());
        } catch (RuntimeException error) {
            response.onError(Status.UNAVAILABLE
                    .withDescription("Ledger temporarily unavailable; retry same operation ID")
                    .asRuntimeException());
        }
    }
}
