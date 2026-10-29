package ra.consoleclient.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ra.consoleclient.client.WalletApiClient;
import ra.consoleclient.client.WalletApiClient.Response;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

// These tests verify console navigation and requests, not backend money correctness.
class WalletConsoleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PAYMENT_ID = "11111111-1111-4111-8111-111111111111";
    private static final String TRANSACTION_ID = "22222222-2222-4222-8222-222222222222";

    @Test
    void invalidMainMenuAndEofNeverCallTheBackend() {
        for (String input : List.of("abc\n-1\n8\n0\n", "", "abc\n")) {
            FakeClient client = new FakeClient(call -> { throw new AssertionError("Unexpected request: " + call); });

            String output = run(input, client);

            assertTrue(client.calls.isEmpty());
            assertTrue(output.contains("Đã đóng console"));
            if (!input.isEmpty()) assertTrue(output.contains("Chọn một số từ 0 đến 6."));
            if (!input.endsWith("0\n")) assertTrue(output.contains("Đã hết dữ liệu nhập."));
        }
    }

    @Test
    void cancelOrEofBeforeConfirmationNeverSubmitsMoney() {
        for (String input : List.of("2\n0\n0\n", "2\n5000\n\n0\n", "2\n5000\n", "3\n0\n0\n")) {
            FakeClient client = new FakeClient(call -> accounts(call, 100_000));

            String output = run(openAlice() + input, client);

            assertTrue(client.calls.stream().noneMatch(call -> call.method.equals("POST")), input);
            assertTrue(output.contains("Đã đóng console"));
            assertFalse(output.contains("Đang gửi yêu cầu..."));
        }
    }

    @Test
    void invalidAmountRetriesKeepTheSelectedWalletAndOnlySendTheValidAmount() {
        FakeClient client = new FakeClient(call -> switch (call.path) {
            case "/api/deposits", "/api/payments/" + PAYMENT_ID -> payment("PENDING", false);
            default -> accounts(call, 100_000);
        });

        String output = run(openAlice() + "2\nabc\n-1\n1.5\n9000000000000001\n10000\n1\n0\n0\n", client);

        List<Call> submitted = posts(client);
        assertEquals(1, submitted.size());
        assertEquals("/api/deposits", submitted.get(0).path);
        assertEquals("alice", submitted.get(0).user);
        assertEquals(JSON.valueToTree(Map.of("accountId", "alice-ui", "amount", 10_000L)), submitted.get(0).body);
        assertEquals(4, occurrences(output, "Nhập số nguyên từ 1 đến"));
        assertTrue(output.contains("Kiểm tra: Nạp tiền 10.000 VND | Ví alice-ui"));
    }

    @Test
    void aliceCanConfirmTheBankOnTheDepositScreenUsingPaymentReceiptEndpoints() {
        AtomicBoolean settled = new AtomicBoolean();
        FakeClient client = new FakeClient(call -> switch (call.path) {
            case "/api/deposits" -> payment("PENDING", false);
            case "/api/payments/" + PAYMENT_ID -> payment(settled.get() ? "COMPLETED" : "PENDING", settled.get());
            case "/api/bank/settle/" + PAYMENT_ID -> {
                settled.set(true);
                yield response(200, Map.of("status", "accepted"));
            }
            default -> accounts(call, settled.get() ? 110_000 : 100_000);
        });

        String output = run(openAlice() + "2\n10000\n1\n1\n\n0\n", client);

        assertEquals(List.of("/api/accounts/bank-clearing",
                        "/api/deposits", "/api/payments/" + PAYMENT_ID, "/api/bank/settle/" + PAYMENT_ID,
                        "/api/payments/" + PAYMENT_ID),
                client.calls.stream().map(Call::path).filter(path -> !path.equals("/api/accounts/alice-ui")).toList());
        assertTrue(client.calls.stream().allMatch(call -> call.user.equals("alice")));
        assertEquals(2, posts(client).size());
        assertTrue(output.contains("CHỜ NGÂN HÀNG XÁC NHẬN"));
        assertTrue(output.contains("HOÀN TẤT — đã ghi nhận tiền"));
        assertTrue(output.contains("Số dư ví alice-ui hiện tại: 110.000 VND"));
        String settlement = output.substring(output.indexOf("CHỜ NGÂN HÀNG XÁC NHẬN"), output.indexOf("HOÀN TẤT — đã ghi nhận tiền"));
        assertFalse(settlement.contains("FINTECH WALLET |"), "Bank confirmation must stay in the deposit flow");
    }

    @Test
    void http200DoesNotTurnARejectedTransactionIntoSuccess() {
        for (String status : List.of("COMPLETED", "REJECTED")) {
            FakeClient client = new FakeClient(call -> switch (call.path) {
                case "/api/transfers", "/api/transactions/" + TRANSACTION_ID -> transaction(status, "TRANSFER", 10_000L);
                default -> accounts(call, status.equals("COMPLETED") ? 90_000 : 100_000);
            });

            String output = run(openAlice() + "3\n1\nbob-ui\n10000\n1\n\n0\n", client);

            assertEquals(1, posts(client).size());
            assertEquals(status.equals("COMPLETED"), output.contains("HOÀN TẤT — đã ghi nhận tiền"), status);
            assertEquals(status.equals("REJECTED"), output.contains("BỊ TỪ CHỐI — không thực hiện giao dịch"), status);
            if (status.equals("REJECTED")) assertTrue(output.contains("Ví không đủ tiền."));
        }
    }

    @Test
    void timeoutCanBeResumedAfterSwitchingUsersWithoutChangingIdentityKeyOrPayload() {
        AtomicInteger attempts = new AtomicInteger();
        FakeClient client = new FakeClient(call -> switch (call.path) {
            case "/api/deposits" -> {
                if (attempts.incrementAndGet() == 1) throw new HttpTimeoutException("Synthetic UI test timeout");
                yield payment("PENDING", false);
            }
            case "/api/payments/" + PAYMENT_ID -> payment("PENDING", false);
            default -> accounts(call, 100_000);
        });

        String output = run(openAlice() + "2\n10000\n1\n\n"
                + "5\n5\n2\n0\n6\n0\n" // Bob sees no Alice receipt.
                + "5\n5\n1\n0\n6\n1\n1\n0\n0\n0\n", client);

        List<Call> submitted = posts(client);
        assertEquals(2, submitted.size());
        assertEquals("alice", submitted.get(0).user);
        assertEquals(submitted.get(0), submitted.get(1));
        assertNotNull(submitted.get(0).key);
        assertFalse(submitted.get(0).key.isBlank());
        assertFalse(output.contains(submitted.get(0).key));
        int bobStart = output.indexOf("GIAO DỊCH & THÔNG BÁO | bob");
        assertTrue(bobStart >= 0);
        String bobActivity = output.substring(bobStart, output.indexOf("FINTECH WALLET |", bobStart));
        assertTrue(bobActivity.contains("Chưa có lệnh nào."));
        assertFalse(bobActivity.contains("1. Nạp tiền |"));
        assertTrue(output.contains("Gửi lại CHÍNH lệnh cũ"));
        assertTrue(output.contains("Chưa nhận được kết quả từ hệ thống."));
        assertFalse(output.contains("HOÀN TẤT"));
    }

    @Test
    void switchingUsersKeepsEachUsersSelectedWallet() {
        FakeClient client = new FakeClient(call -> accounts(call, 100_000));

        String output = run(openAlice() + "5\n5\n2\n2\n1\nbob-ui\n3\n\n5\n1\n3\n\n0\n0\n", client);

        assertTrue(client.calls.stream().anyMatch(call -> call.user.equals("bob") && call.path.equals("/api/accounts/bob-ui")));
        assertTrue(client.calls.stream().allMatch(call -> call.path.equals("/api/accounts/" + call.user + "-ui")));
        Call last = client.calls.get(client.calls.size() - 1);
        assertEquals("alice", last.user);
        assertEquals("/api/accounts/alice-ui", last.path);
        assertTrue(output.contains("VÍ & NGƯỜI DÙNG | bob"));
        assertTrue(output.contains("Ví đang dùng: bob-ui"));
        assertTrue(output.contains("Người dùng: alice | Ví đang dùng: alice-ui"));
        assertTrue(posts(client).isEmpty());
    }

    @Test
    void publicMenuShowsWalletActionsAndAdminToolsRequireAdminIdentity() {
        for (String input : List.of("7\n0\n", "5\n5\n2\n0\n7\n0\n")) {
            FakeClient client = new FakeClient(call -> { throw new AssertionError("Unexpected request: " + call); });
            String output = run(input, client);

            assertTrue(client.calls.isEmpty());
            assertTrue(output.contains("1. Tổng quan ví"));
            assertTrue(output.contains("6. Giao dịch & thông báo"));
            assertTrue(output.contains("Chọn một số từ 0 đến 6."));
            assertFalse(output.contains("7. Quản trị"));
            for (String hidden : List.of("demo", "test", "kiểm thử", "giả lập", "dùng thử"))
                assertFalse(output.toLowerCase(Locale.ROOT).contains(hidden), hidden);
        }

        FakeClient admin = new FakeClient(call -> {
            assertEquals("admin", call.user);
            assertEquals("GET", call.method);
            assertEquals("/api/risk/flags", call.path);
            return response(200, List.of());
        });
        String output = run("5\n5\n3\n0\n7\n2\n\n0\n0\n", admin);

        assertEquals(1, admin.calls.size());
        assertTrue(output.contains("7. Quản trị"));
        assertTrue(output.contains("QUẢN TRỊ | admin"));
        assertTrue(output.contains("4. Thiết lập tài khoản ngân hàng"));
        for (String hidden : List.of("demo", "test", "kiểm thử", "giả lập", "dùng thử"))
            assertFalse(output.toLowerCase(Locale.ROOT).contains(hidden), hidden);
    }

    @Test
    void overviewShowsFreshSelectedWalletDetailsWithoutSubmittingMoney() {
        FakeClient client = new FakeClient(call -> accounts(call, 123_456));
        String output = run(openAlice() + "1\n\n0\n", client);

        assertTrue(output.contains("TỔNG QUAN VÍ | alice"));
        assertTrue(output.contains("Ví       : alice-ui"));
        assertTrue(output.contains("Chủ ví   : alice"));
        assertTrue(output.contains("Số dư    : 123.456 VND"));
        assertTrue(posts(client).isEmpty());

        FakeClient noWallet = new FakeClient(call -> { throw new AssertionError("Unexpected request: " + call); });
        String empty = run("1\n\n0\n", noWallet);
        assertTrue(noWallet.calls.isEmpty());
        assertTrue(empty.contains("Chưa chọn ví. Mở 'Ví & người dùng' để tạo hoặc chọn ví."));
    }

    @Test
    void savedReceiptRemainsVisibleWhenStatusRefreshTimesOut() {
        AtomicInteger updates = new AtomicInteger();
        FakeClient client = new FakeClient(call -> switch (call.path) {
            case "/api/deposits" -> payment("PENDING", false);
            case "/api/payments/" + PAYMENT_ID -> {
                if (updates.incrementAndGet() > 1) throw new HttpTimeoutException("Synthetic status timeout");
                yield payment("PENDING", false);
            }
            default -> accounts(call, 100_000);
        });

        String output = run(openAlice() + "2\n10000\n1\n0\n6\n1\n0\n0\n0\n", client);

        assertEquals(1, posts(client).size());
        assertTrue(output.contains("Đang hiển thị biên nhận đã lưu; trạng thái có thể chưa mới nhất."));
        assertEquals(2, occurrences(output, "Mã biên nhận nạp/rút/thanh toán: " + PAYMENT_ID));
        assertFalse(output.contains("HOÀN TẤT"));
    }

    @Test
    void reopenedTransactionLookupKeepsTheTransactionEndpointForDepositOrWithdrawalKinds() {
        for (String kind : List.of("WITHDRAWAL", "DEPOSIT")) {
            FakeClient client = new FakeClient(call -> switch (call.path) {
                case "/api/transactions/" + TRANSACTION_ID -> transaction("COMPLETED", kind, 10_000L);
                default -> accounts(call, 100_000);
            });

            String output = run("5\n5\n3\n0\n6\n1\n2\n" + TRANSACTION_ID + "\n\n1\n\n0\n0\n", client);

            assertEquals(List.of("/api/transactions/" + TRANSACTION_ID, "/api/accounts/alice-ui",
                            "/api/transactions/" + TRANSACTION_ID, "/api/accounts/alice-ui"),
                    client.calls.stream().map(Call::path).toList(), kind);
            assertTrue(client.calls.stream().allMatch(call -> call.user.equals("admin")), kind);
            assertTrue(posts(client).isEmpty(), kind);
            assertEquals(2, occurrences(output, "Kết quả  : HOÀN TẤT — đã ghi nhận tiền"), kind);
        }
    }

    @Test
    void bookmarksRememberEachWalletButFetchBalancesAgainAfterRestart(@TempDir Path directory) throws Exception {
        Path bookmarks = directory.resolve("console-wallets.json");
        FakeClient firstClient = new FakeClient(call -> accounts(call, 100_000));
        run(openAlice() + "5\n5\n2\n2\n1\nbob-ui\n0\n0\n", firstClient, bookmarks);

        JsonNode saved = JSON.readTree(bookmarks.toFile());
        assertEquals(3, saved.size());
        assertEquals("bob", saved.path("user").asText());
        assertEquals(JSON.valueToTree(Map.of("alice", "alice-ui", "bob", "bob-ui")), saved.path("selected"));
        assertEquals(JSON.valueToTree(List.of(
                Map.of("accountId", "alice-ui", "ownerId", "alice", "kind", "USER"),
                Map.of("accountId", "bob-ui", "ownerId", "bob", "kind", "USER"))), saved.path("wallets"));

        FakeClient reopened = new FakeClient(call -> accounts(call, 123_456));
        String output = run("5\n5\n1\n0\n0\n", reopened, bookmarks);

        assertTrue(output.contains("Người dùng: bob | Ví đang dùng: bob-ui"));
        assertTrue(output.contains("Người dùng: alice | Ví đang dùng: alice-ui"));
        assertTrue(output.contains("Số dư hiện tại: 123.456 VND"));
        assertFalse(output.contains("Số dư hiện tại: 100.000 VND"));
        assertEquals(List.of("bob:/api/accounts/bob-ui", "alice:/api/accounts/alice-ui"),
                reopened.calls.stream().map(call -> call.user + ":" + call.path).toList());
        assertTrue(posts(firstClient).isEmpty());
        assertTrue(posts(reopened).isEmpty());
    }

    @Test
    void restartRecoversThePersistedIntentWithTheSameActorPayloadAndKey(@TempDir Path directory) throws Exception {
        Path bookmarks = directory.resolve("console-wallets.json");
        Path journal = directory.resolve("console-requests.json");
        FakeClient first = new FakeClient(call -> {
            if (call.path.equals("/api/deposits")) {
                JsonNode intent = JSON.readTree(journal.toFile()).path("receipts").get(0);
                assertEquals(call.key, intent.path("key").asText());
                assertEquals(call.user, intent.path("user").asText());
                assertEquals(JSON.readTree(JSON.writeValueAsBytes(call.body)), intent.path("body"));
                assertTrue(intent.path("attempted").asBoolean());
                throw new HttpTimeoutException("Synthetic timeout after submission");
            }
            return accounts(call, 100_000);
        });
        run(openAlice() + "2\n10000\n1\n\n0\n", first, bookmarks);

        FakeClient second = new FakeClient(call -> switch (call.path) {
            case "/api/deposits", "/api/payments/" + PAYMENT_ID -> payment("PENDING", false);
            default -> accounts(call, 100_000);
        });
        String output = run("6\n1\n1\n0\n0\n0\n", second, bookmarks);

        Call original = posts(first).get(0), recovered = posts(second).get(0);
        assertEquals(original.user, recovered.user);
        assertEquals(original.method, recovered.method);
        assertEquals(original.path, recovered.path);
        assertEquals(original.key, recovered.key);
        assertEquals(JSON.writeValueAsString(posts(first).get(0).body), JSON.writeValueAsString(posts(second).get(0).body));
        assertTrue(output.contains("Gửi lại CHÍNH lệnh cũ"));
        JsonNode savedReceipt = JSON.readTree(journal.toFile()).path("receipts").get(0);
        assertEquals(PAYMENT_ID, savedReceipt.path("id").asText());
        assertTrue(savedReceipt.path("payment").asBoolean());
        assertFalse(Files.readString(journal).contains("unused-test-key"));

        FakeClient completed = new FakeClient(call -> switch (call.path) {
            case "/api/payments/" + PAYMENT_ID -> payment("COMPLETED", true);
            default -> accounts(call, 110_000);
        });
        String afterSecondRestart = run("6\n1\n\n0\n0\n", completed, bookmarks);
        assertTrue(posts(completed).isEmpty(), "A saved receipt id must be queried rather than submitted again");
        assertTrue(afterSecondRestart.contains("HOÀN TẤT — đã ghi nhận tiền"));
    }

    @Test
    void httpTimeoutAndThrottlingRemainUnresolvedAndRetryTheOriginalRequest() {
        for (int status : List.of(408, 429, 503)) {
            AtomicInteger attempts = new AtomicInteger();
            FakeClient client = new FakeClient(call -> switch (call.path) {
                case "/api/transfers" -> attempts.incrementAndGet() == 1
                        ? response(status, Map.of("code", "REQUEST_TIMEOUT")) : transaction("COMPLETED", "TRANSFER", 1_000);
                case "/api/transactions/" + TRANSACTION_ID -> transaction("COMPLETED", "TRANSFER", 1_000);
                default -> accounts(call, 100_000);
            });
            String output = run(openAlice() + "3\n1\nbob-ui\n1000\n1\n\n6\n1\n1\n\n0\n0\n", client);

            assertEquals(2, posts(client).size(), "HTTP " + status);
            assertEquals(posts(client).get(0), posts(client).get(1));
            assertTrue(output.contains("Hệ thống chưa trả kết quả cuối cùng (HTTP " + status + ")"));
            assertTrue(output.contains("Gửi lại CHÍNH lệnh cũ"));
            assertFalse(output.contains("YÊU CẦU BỊ TỪ CHỐI"));
        }
    }

    @Test
    void pendingIntentBlocksANewTransferFromTheSameWalletAfterRestart(@TempDir Path directory) {
        Path bookmarks = directory.resolve("console-wallets.json");
        FakeClient timedOut = new FakeClient(call -> {
            if (call.path.equals("/api/transfers")) throw new HttpTimeoutException("Synthetic timeout");
            return accounts(call, 100_000);
        });
        run(openAlice() + "3\n1\nbob-ui\n1000\n1\n\n0\n", timedOut, bookmarks);

        FakeClient second = new FakeClient(call -> accounts(call, 100_000));
        String output = run("3\n1\nbob-ui\n2000\n1\n\n0\n", second, bookmarks);

        assertTrue(posts(second).isEmpty());
        assertTrue(output.contains("Ví này còn lệnh chưa có kết quả cuối cùng"));
    }

    @Test
    void aRejectedRetryDoesNotResolveAnEarlierTimeout() {
        AtomicInteger attempts = new AtomicInteger();
        FakeClient client = new FakeClient(call -> {
            if (call.path.equals("/api/transfers"))
                return response(attempts.incrementAndGet() == 1 ? 408 : 401, Map.of("code", "INVALID_SIGNATURE"));
            return accounts(call, 100_000);
        });
        String output = run(openAlice() + "3\n1\nbob-ui\n1000\n1\n\n6\n1\n1\n\n1\n0\n0\n"
                + "3\n1\nbob-ui\n2000\n1\n\n0\n", client);

        assertEquals(2, posts(client).size());
        assertEquals(posts(client).get(0), posts(client).get(1));
        assertFalse(output.contains("YÊU CẦU BỊ TỪ CHỐI"));
        assertTrue(output.contains("Ví này còn lệnh chưa có kết quả cuối cùng"));
    }

    @Test
    void corruptedJournalBlocksMoneySubmissionsAndIsNeverOverwritten(@TempDir Path directory) throws Exception {
        Path bookmarks = directory.resolve("console-wallets.json");
        Path journal = directory.resolve("console-requests.json");
        for (String corrupt : List.of("{bad-json", "{\"version\":1,\"receipts\":{}}",
                "{\"version\":1,\"version\":1,\"receipts\":[]}", "{\"version\":1,\"receipts\":[]} {}")) {
            Files.writeString(journal, corrupt);
            FakeClient client = new FakeClient(call -> accounts(call, 100_000));
            String output = run(openAlice() + "2\n10000\n1\n\n0\n", client, bookmarks);

            assertTrue(posts(client).isEmpty());
            assertTrue(output.contains("Đã khóa gửi tiền"));
            assertEquals(corrupt, Files.readString(journal));
        }
    }

    @Test
    void failureToWriteTheIntentPreventsTheOutboundPost(@TempDir Path directory) throws Exception {
        Path journal = directory.resolve("console-requests.json");
        FakeClient client = new FakeClient(call -> {
            if (call.path.equals("/api/accounts/alice-ui")) Files.createDirectories(journal);
            return accounts(call, 100_000);
        });

        String output = run(openAlice() + "2\n10000\n1\n\n0\n", client, directory.resolve("console-wallets.json"));

        assertTrue(posts(client).isEmpty());
        assertTrue(output.contains("Không lưu an toàn được lệnh tiền"));
    }

    @Test
    void onlyOneConsoleMayWriteTheRequestJournal(@TempDir Path directory) {
        Path bookmarks = directory.resolve("console-wallets.json");
        FakeClient client = new FakeClient(call -> accounts(call, 100_000));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Scanner scanner = new Scanner("0\n"); PrintStream stream = new PrintStream(output)) {
            WalletConsole first = new WalletConsole(scanner, stream, client, bookmarks);
            try {
                String second = run(openAlice() + "2\n10000\n1\n\n0\n", client, bookmarks);
                assertTrue(posts(client).isEmpty());
                assertTrue(second.contains("Không đọc/khóa được file lệnh đã lưu"));
            } finally {
                first.run();
            }
        }
    }

    @Test
    void settlementIntentAndPaymentReceiptSurviveRestart(@TempDir Path directory) throws Exception {
        Path bookmarks = directory.resolve("console-wallets.json");
        FakeClient first = new FakeClient(call -> switch (call.path) {
            case "/api/deposits", "/api/payments/" + PAYMENT_ID -> payment("PENDING", false);
            case "/api/bank/settle/" + PAYMENT_ID -> throw new HttpTimeoutException("Synthetic settlement timeout");
            default -> accounts(call, 100_000);
        });
        run(openAlice() + "2\n10000\n1\n1\n\n0\n", first, bookmarks);
        JsonNode saved = JSON.readTree(directory.resolve("console-requests.json").toFile()).path("receipts").get(0);
        assertTrue(saved.path("settlementRequested").asBoolean());
        assertEquals(PAYMENT_ID, saved.path("id").asText());

        AtomicBoolean settled = new AtomicBoolean();
        FakeClient second = new FakeClient(call -> switch (call.path) {
            case "/api/payments/" + PAYMENT_ID -> payment(settled.get() ? "COMPLETED" : "PENDING", settled.get());
            case "/api/bank/settle/" + PAYMENT_ID -> { settled.set(true); yield response(200, Map.of("status", "accepted")); }
            default -> accounts(call, 110_000);
        });
        String output = run("6\n1\n1\n\n0\n0\n", second, bookmarks);
        assertEquals(List.of("/api/bank/settle/" + PAYMENT_ID), posts(second).stream().map(Call::path).toList());
        assertTrue(output.contains("Lần xác nhận trước chưa có kết quả cuối cùng"));
        assertTrue(output.contains("HOÀN TẤT — đã ghi nhận tiền"));
    }

    private static String openAlice() { return "5\n2\n1\nalice-ui\n0\n"; }

    private static String run(String input, FakeClient client) {
        return run(input, client, null);
    }

    private static String run(String input, FakeClient client, Path bookmarks) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Scanner scanner = new Scanner(input); PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            new WalletConsole(scanner, stream, client, bookmarks).run();
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static Response accounts(Call call, long aliceBalance) {
        assertEquals("GET", call.method, "Only explicit test branches may submit a request");
        if (call.path.equals("/api/accounts/bank-clearing"))
            return response(403, Map.of("code", "NOT_WALLET_OWNER"));
        String owner = switch (call.path) {
            case "/api/accounts/alice-ui" -> "alice";
            case "/api/accounts/bob-ui" -> "bob";
            default -> throw new AssertionError("Unexpected request: " + call);
        };
        assertTrue(call.user.equals(owner) || call.user.equals("admin"), "Wrong identity for selected wallet: " + call);
        return response(200, Map.of("accountId", owner + "-ui", "ownerId", owner, "kind", "USER",
                "balance", owner.equals("alice") ? aliceBalance : 20_000L));
    }

    private static Response payment(String status, boolean settled) {
        return response(200, Map.of("id", PAYMENT_ID, "status", status, "kind", "DEPOSIT", "amount", 10_000L,
                "account_id", "alice-ui", "transaction_id", settled ? TRANSACTION_ID : ""));
    }

    private static Response transaction(String status, String kind, long amount) {
        return response(200, Map.of("id", TRANSACTION_ID, "status", status, "kind", kind,
                "amount", amount, "source_account", "alice-ui", "destination_account", "bob-ui",
                "journal_id", "journal-ui", "error_code", status.equals("REJECTED") ? "INSUFFICIENT_FUNDS" : ""));
    }

    private static Response response(int status, Object body) { return new Response(status, JSON.valueToTree(body)); }

    private static List<Call> posts(FakeClient client) {
        return client.calls.stream().filter(call -> call.method.equals("POST")).toList();
    }

    private static int occurrences(String text, String value) { return text.split(java.util.regex.Pattern.quote(value), -1).length - 1; }

    private record Call(String user, String method, String path, JsonNode body, String key) { }

    @FunctionalInterface
    private interface Responder { Response respond(Call call) throws Exception; }

    private static final class FakeClient extends WalletApiClient {
        private final List<Call> calls = new CopyOnWriteArrayList<>();
        private final Responder responder;

        FakeClient(Responder responder) {
            super("http://127.0.0.1:1", user -> "unused-test-key");
            this.responder = responder;
        }

        @Override
        public Response request(String user, String method, String path, Object body, String key) throws Exception {
            Call call = new Call(user, method, path, JSON.valueToTree(body), key);
            calls.add(call);
            return responder.respond(call);
        }
    }
}
