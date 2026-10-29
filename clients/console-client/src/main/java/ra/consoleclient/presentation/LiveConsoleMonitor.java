package ra.consoleclient.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ra.common.validation.InputRules;
import ra.consoleclient.client.WalletApiClient;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class LiveConsoleMonitor implements AutoCloseable {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final WalletApiClient client;
    private final PrintStream out;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, NotificationState> notifications = new HashMap<>();
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

    public void primeNotifications(String user) {
        InputRules.client(user);
        Context observed = context;
        synchronized (out) {
            if (closed || notifications.containsKey(user)) return;
        }
        List<Notification> recent = readNotifications(user);
        synchronized (out) {
            if (closed || context != observed || notifications.containsKey(user)) return;
            NotificationState state = new NotificationState();
            notifications.put(user, state);
            if (recent == null) {
                state.failed = true;
                show(observed, "Chưa cập nhật được thông báo nhận tiền. Sẽ tự thử lại; xem mục Thông báo để kiểm tra giao dịch.");
            } else {
                state.sequence = recent.stream().mapToLong(Notification::sequence).max().orElse(0);
            }
        }
    }

    void pollOnce() {
        Context observed = context;
        if (closed || !observed.enabled) return;
        Long balance = null;
        try {
            if (observed.walletId != null) {
                var response = client.request(observed.user, "GET", "/api/accounts/" + observed.walletId, null, null);
                if (response.successful() && response.body() != null
                        && observed.walletId.equals(response.body().path("accountId").asText())
                        && response.body().path("balance").isIntegralNumber()
                        && response.body().path("balance").canConvertToLong())
                    balance = response.body().path("balance").longValue();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // The last verified balance remains valid history when a refresh fails.
        }
        if (closed || context != observed) return;
        List<Notification> recent = readNotifications(observed.user);
        synchronized (out) {
            // A new context also invalidates requests started before a pause or user switch.
            if (closed || context != observed) return;
            if (observed.walletId != null && balance == null) {
                if (!connectionFailed)
                    show(observed, "Chưa cập nhật được số dư ví " + observed.walletId
                            + ". Sẽ tự thử lại; số dư chưa được xác minh.");
                connectionFailed = true;
            } else if (balance != null) {
                if (connectionFailed)
                    show(observed, "Đã kết nối lại. Số dư ví " + observed.walletId + ": " + ConsoleDisplay.money(balance));
                else if (lastBalance != null && lastBalance.longValue() != balance.longValue())
                    show(observed, "Số dư ví " + observed.walletId + ": " + ConsoleDisplay.money(balance));
                lastBalance = balance;
                connectionFailed = false;
            }
            updateNotifications(observed, recent);
        }
    }

    private List<Notification> readNotifications(String user) {
        try {
            var response = client.request(user, "GET", "/api/notifications", null, null);
            if (!response.successful() || response.body() == null || !response.body().isArray()) return null;
            List<Notification> recent = new ArrayList<>();
            for (JsonNode value : response.body()) {
                if (!value.path("sequence_id").isIntegralNumber() || !value.path("sequence_id").canConvertToLong()
                        || value.path("sequence_id").longValue() <= 0 || !value.path("payload").isTextual())
                    return null;
                JsonNode event = json.readTree(value.path("payload").textValue());
                if (event == null || !event.isObject() || !event.path("destination_owner").isTextual()
                        || !event.path("source").isTextual() || !event.path("destination").isTextual()
                        || !event.path("amount").isIntegralNumber() || !event.path("amount").canConvertToLong())
                    return null;
                InputRules.client(event.path("destination_owner").textValue());
                InputRules.identifier(event.path("source").textValue());
                InputRules.identifier(event.path("destination").textValue());
                InputRules.amount(event.path("amount").longValue());
                recent.add(new Notification(value.path("sequence_id").longValue(), event));
            }
            recent.sort(Comparator.comparingLong(Notification::sequence));
            return recent;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Keep the cursor unchanged until a complete notification response is verified.
        }
        return null;
    }

    private void updateNotifications(Context observed, List<Notification> recent) {
        NotificationState state = notifications.computeIfAbsent(observed.user, ignored -> new NotificationState());
        if (recent == null) {
            if (!state.failed)
                show(observed, "Chưa cập nhật được thông báo nhận tiền. Sẽ tự thử lại; xem mục Thông báo để kiểm tra giao dịch.");
            state.failed = true;
            return;
        }
        if (state.sequence == null) {
            state.sequence = recent.stream().mapToLong(Notification::sequence).max().orElse(0);
            if (state.failed)
                show(observed, "Đã kết nối lại thông báo. Xem mục Thông báo để kiểm tra tiền nhận trong lúc gián đoạn.");
        } else {
            if (state.failed) show(observed, "Đã kết nối lại thông báo nhận tiền.");
            // shortcut: the endpoint returns 200 events; use pagination for longer offline backlogs.
            for (Notification notification : recent) {
                if (notification.sequence <= state.sequence) continue;
                JsonNode event = notification.event;
                if (observed.user.equals(event.path("destination_owner").textValue())) {
                    String wallet = event.path("destination").textValue();
                    show(observed, "Nhận " + ConsoleDisplay.money(event.path("amount").longValue())
                            + " vào ví " + wallet + " từ ví " + event.path("source").textValue()
                            + (observed.walletId != null && !wallet.equals(observed.walletId)
                            ? " (ví đang mở: " + observed.walletId + ")" : ""));
                }
                state.sequence = notification.sequence;
            }
        }
        state.failed = false;
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
    private record Notification(long sequence, JsonNode event) { }
    private static final class NotificationState {
        private Long sequence;
        private boolean failed;
    }
}
