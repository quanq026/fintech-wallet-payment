package ra.consoleclient.presentation;

import ra.common.validation.InputRules;
import ra.consoleclient.client.WalletApiClient;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class LiveConsoleMonitor implements AutoCloseable {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final WalletApiClient client;
    private final PrintStream out;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "wallet-live-monitor");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Context context = new Context("alice", null, "", false);
    private volatile boolean closed;
    private boolean started;
    private Long lastBalance;
    private boolean connectionFailed;

    public LiveConsoleMonitor(WalletApiClient client, PrintStream out) {
        this.client = client;
        this.out = out;
    }

    public void start() {
        synchronized (out) {
            if (started || closed) return;
            started = true;
            executor.scheduleWithFixedDelay(this::pollOnce, 2, 2, TimeUnit.SECONDS);
        }
    }

    public void setContext(String user, String walletId, String prompt, boolean enabled) {
        synchronized (out) {
            if (closed) return;
            InputRules.client(user);
            if (walletId != null) InputRules.identifier(walletId);
            if (!context.user.equals(user) || !Objects.equals(context.walletId, walletId)) {
                lastBalance = null;
                connectionFailed = false;
            }
            context = new Context(user, walletId, prompt == null ? "" : prompt, enabled);
        }
    }

    public void seedBalance(String user, String walletId, long balance) {
        synchronized (out) {
            if (closed) return;
            setContext(user, walletId, "", false);
            lastBalance = balance;
            connectionFailed = false;
        }
    }

    void pollOnce() {
        Context observed = context;
        if (closed || !observed.enabled || observed.walletId == null) return;
        Long balance = null;
        try {
            var response = client.request(observed.user, "GET", "/api/accounts/" + observed.walletId, null, null);
            if (response.successful() && response.body() != null
                    && observed.walletId.equals(response.body().path("accountId").asText())
                    && response.body().path("balance").isIntegralNumber()
                    && response.body().path("balance").canConvertToLong())
                balance = response.body().path("balance").longValue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // The last verified balance remains valid history when a refresh fails.
        }
        synchronized (out) {
            // A new context also invalidates requests started before a pause or user switch.
            if (closed || context != observed) return;
            if (balance == null) {
                if (!connectionFailed)
                    show(observed, "Chưa cập nhật được số dư ví " + observed.walletId
                            + ". Sẽ tự thử lại; số dư chưa được xác minh.");
                connectionFailed = true;
                return;
            }
            if (connectionFailed)
                show(observed, "Đã kết nối lại. Số dư ví " + observed.walletId + ": " + ConsoleDisplay.money(balance));
            else if (lastBalance != null && lastBalance.longValue() != balance.longValue())
                show(observed, "Số dư ví " + observed.walletId + ": " + ConsoleDisplay.money(balance));
            lastBalance = balance;
            connectionFailed = false;
        }
    }

    private void show(Context observed, String message) {
        out.print("\n[" + LocalTime.now().format(TIME) + "] " + message + "\n" + observed.prompt);
        out.flush();
    }

    @Override
    public void close() {
        synchronized (out) {
            closed = true;
            executor.shutdownNow();
        }
    }

    private record Context(String user, String walletId, String prompt, boolean enabled) { }
}
