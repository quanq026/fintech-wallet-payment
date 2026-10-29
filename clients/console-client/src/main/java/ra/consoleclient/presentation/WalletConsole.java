package ra.consoleclient.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ra.common.validation.InputRules;
import ra.consoleclient.client.WalletApiClient;
import ra.consoleclient.client.WalletApiClient.Response;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;

import static ra.consoleclient.presentation.ConsoleDisplay.*;

public class WalletConsole {
    private final Scanner input;
    private final PrintStream out;
    private final WalletApiClient client;
    private final ConsoleDisplay display;
    private final Path bookmarks;
    private final Path receiptFile;
    private final String fixedUser;
    private final LiveConsoleMonitor liveMonitor;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, JsonNode> wallets = new LinkedHashMap<>();
    private final Map<String, String> selectedWallets = new HashMap<>();
    private final List<Receipt> receipts = new ArrayList<>();
    private String user = "alice";
    private boolean receiptStoreReady = true;
    private FileChannel receiptLockChannel;
    private FileLock receiptLock;
    private boolean liveInput;

    public WalletConsole(Scanner input, PrintStream out, WalletApiClient client) {
        this(input, out, client, bookmarkFile(), "alice");
    }

    public WalletConsole(Scanner input, PrintStream out, WalletApiClient client, Path bookmarks) {
        this(input, out, client, bookmarks, null, null);
    }

    public WalletConsole(Scanner input, PrintStream out, WalletApiClient client, Path legacyBookmarks, String identity) {
        this(input, out, client, profileBookmarks(legacyBookmarks, identity), InputRules.client(identity), legacyBookmarks);
    }

    private WalletConsole(Scanner input, PrintStream out, WalletApiClient client, Path bookmarks,
                          String identity, Path legacyBookmarks) {
        this.input = input;
        this.out = out;
        this.client = client;
        this.display = new ConsoleDisplay(out);
        this.bookmarks = bookmarks;
        this.receiptFile = bookmarks == null ? null : bookmarks.resolveSibling("console-requests.json");
        this.fixedUser = identity;
        this.liveMonitor = identity == null ? null : new LiveConsoleMonitor(client, out);
        loadBookmarks();
        if (identity != null) user = identity;
        loadReceipts();
        if (legacyBookmarks != null && receiptStoreReady) importLegacy(legacyBookmarks);
    }

    public static void main(String[] args) {
        if (args.length > 1 || (args.length == 1 && !Set.of("alice", "bob", "admin").contains(args[0]))) {
            System.err.println("Tài khoản cần là alice, bob hoặc admin.");
            return;
        }
        new WalletConsole(new Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8),
                System.out, new WalletApiClient(), bookmarkFile(), args.length == 0 ? "alice" : args[0]).run();
    }

    public void run() {
        if (liveMonitor != null) {
            liveMonitor.primeNotifications(user);
            liveMonitor.start();
        }
        try {
            while (true) {
                display.title("FINTECH WALLET | Ví điện tử");
                out.println("Người dùng: " + user + " | Ví đang dùng: " + selectedWallets.getOrDefault(user, "chưa chọn"));
                showHomeBalance();
                if (!selectedWallets.containsKey(user))
                    out.println("Chưa chọn ví. Mở 'Ví & người dùng' để tạo hoặc chọn ví.");
                out.println("1. Tổng quan ví             Thông tin và số dư ví đang dùng");
                out.println("2. Nạp tiền                 Nạp thêm tiền vào ví");
                out.println("3. Chuyển tiền              Gửi tiền sang ví người khác");
                out.println("4. Thanh toán / rút tiền    Trả cho cửa hàng hoặc rút về ngân hàng");
                out.println(fixedUser == null ? "5. Ví & người dùng          Số dư, tạo/chọn ví, đổi Alice/Bob/admin"
                        : "5. Ví của tôi              Số dư, tạo/chọn ví và lịch sử");
                out.println("6. Giao dịch & thông báo    Xem kết quả, tiếp tục lệnh đang chờ");
                if (user.equals("admin")) out.println("7. Tạo ví cửa hàng");
                out.println("0. Thoát");
                if (liveMonitor != null) out.println("Số dư ví tự cập nhật mỗi 2 giây khi chờ tại menu này.");
                int choice;
                liveInput = true;
                try { choice = choice(user.equals("admin") ? 7 : 6); }
                finally { liveInput = false; }
                if (choice == 0) break;
                perform(() -> {
                    switch (choice) {
                        case 1 -> overview();
                        case 2 -> payment("DEPOSIT");
                        case 3 -> transfer();
                        case 4 -> paymentMenu();
                        case 5 -> walletMenu();
                        case 6 -> activityMenu();
                        case 7 -> { createWallet("MERCHANT"); pause(); }
                    }
                });
            }
        } catch (EndOfInput ignored) {
            out.println("\nĐã hết dữ liệu nhập.");
        } finally {
            if (liveMonitor != null) liveMonitor.close();
            closeReceiptStore();
        }
        out.println("Đã đóng console.");
    }

    private void overview() throws Exception {
        display.title("TỔNG QUAN VÍ | " + user);
        if (selectedWallets.containsKey(user)) {
            JsonNode account = currentAccount();
            display.account(account);
            if (liveMonitor != null)
                liveMonitor.seedBalance(user, text(account, "accountId"), account.path("balance").longValue());
        }
        else out.println("Chưa chọn ví. Mở 'Ví & người dùng' để tạo hoặc chọn ví.");
        liveInput = true;
        try { pause(); }
        finally { liveInput = false; }
    }

    private void walletMenu() {
        while (true) {
            display.title("VÍ & NGƯỜI DÙNG | " + user);
            String selected = selectedWallets.get(user);
            out.println(selected == null ? "Chưa chọn ví. Tạo ví mới hoặc mở ví đã có." : "Ví đang dùng: " + selected);
            String[] items = fixedUser == null
                    ? new String[]{"Tạo ví cá nhân", "Mở / chọn ví đã có", "Xem số dư ví đang dùng", "Xem tiền vào / ra ví", "Đổi người dùng"}
                    : new String[]{"Tạo ví cá nhân", "Mở / chọn ví đã có", "Xem số dư ví đang dùng", "Xem tiền vào / ra ví"};
            int option = menu(items);
            if (option == 0) return;
            perform(() -> {
                switch (option) {
                    case 1 -> { createWallet("USER"); pause(); }
                    case 2 -> selectWallet();
                    case 3 -> { display.account(currentAccount()); pause(); }
                    case 4 -> { showEntries(); pause(); }
                    case 5 -> changeUser();
                }
            });
        }
    }

    private void changeUser() {
        display.title("ĐỔI NGƯỜI DÙNG");
        out.println("Alice/Bob dùng ví cá nhân. Admin có thể tạo ví cửa hàng.");
        int option = menu("Alice", "Bob", "Admin");
        if (option != 0) {
            user = List.of("alice", "bob", "admin").get(option - 1);
            saveBookmarks();
            out.println("Đã chọn " + user + ". Ví của từng người được nhớ riêng.");
        }
    }

    private void createWallet(String kind) throws Exception {
        display.title(kind.equals("MERCHANT") ? "TẠO VÍ CỬA HÀNG" : "TẠO VÍ CÁ NHÂN");
        String suggested = user + "-" + UUID.randomUUID().toString().substring(0, 8);
        String id = identifier("Mã ví (Enter dùng " + suggested + ")", suggested);
        var account = data(user, "POST", "/api/accounts", Map.of("accountId", id, "ownerId", user, "kind", kind));
        rememberWallet(account);
        selectedWallets.put(user, id);
        saveBookmarks();
        out.println("Đã mở/tạo và chọn ví. Số dư hiện tại:");
        display.account(account);
    }

    private void selectWallet() throws Exception {
        List<JsonNode> known = wallets.values().stream()
                .filter(w -> user.equals("admin") || text(w, "ownerId").equals(user)).toList();
        display.title("CHỌN VÍ | " + user);
        out.println("Các ví đã dùng trên máy này. Chọn bằng số để mở lại.");
        for (int i = 0; i < known.size(); i++)
            out.println((i + 1) + ". " + text(known.get(i), "accountId") + " — " + kind(text(known.get(i), "kind")));
        out.println((known.size() + 1) + ". Nhập mã một ví đã có");
        out.println("0. Quay lại");
        int option = choice(known.size() + 1);
        if (option == 0) throw new Cancelled();
        String id = option <= known.size() ? text(known.get(option - 1), "accountId") : identifier("Mã ví", null);
        var account = data(user, "GET", "/api/accounts/" + id, null);
        rememberWallet(account);
        selectedWallets.put(user, id);
        saveBookmarks();
        display.account(account);
    }

    private JsonNode currentAccount() throws Exception {
        if (!selectedWallets.containsKey(user)) {
            out.println("Cần chọn một ví để tiếp tục.");
            int option = menu("Tạo ví cá nhân mới", "Mở / chọn ví đã có");
            if (option == 0) throw new Cancelled();
            if (option == 1) createWallet("USER"); else selectWallet();
        }
        var account = data(user, "GET", "/api/accounts/" + selectedWallets.get(user), null);
        rememberWallet(account);
        return account;
    }

    private void rememberWallet(JsonNode account) { wallets.put(text(account, "accountId"), account); }

    private void showHomeBalance() {
        String id = selectedWallets.get(user);
        if (id == null) return;
        try {
            Response response = client.request(user, "GET", "/api/accounts/" + id, null, null);
            if (response.successful()) {
                rememberWallet(response.body());
                out.println("Số dư hiện tại: " + money(response.body().path("balance").asLong()));
                if (liveMonitor != null)
                    liveMonitor.seedBalance(user, id, response.body().path("balance").longValue());
            } else {
                out.println("Chưa xem được số dư. Mở 'Ví & người dùng' để chọn lại hoặc kiểm tra ví.");
            }
        } catch (Exception ignored) {
            out.println("Chưa cập nhật được số dư. Kiểm tra kết nối và thử lại sau.");
        }
    }

    private static Path bookmarkFile() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("scripts/load-env.ps1"))) root = root.getParent();
        return (root == null ? Path.of("").toAbsolutePath() : root).resolve("work/console-wallets.json");
    }

    private static Path profileBookmarks(Path legacyBookmarks, String identity) {
        InputRules.client(identity);
        Objects.requireNonNull(legacyBookmarks, "Console storage path is required");
        return legacyBookmarks.resolveSibling("console").resolve(identity).resolve("console-wallets.json");
    }

    private void importLegacy(Path legacyBookmarks) {
        if (Files.exists(receiptFile) && Files.exists(bookmarks)) return;
        Path legacyRequests = legacyBookmarks.resolveSibling("console-requests.json");
        Path legacyLock = legacyBookmarks.resolveSibling("console-requests.lock");
        try (FileChannel channel = FileChannel.open(legacyLock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = null;
            for (int attempt = 0; attempt < 40 && lock == null; attempt++) {
                try { lock = channel.tryLock(); }
                catch (java.nio.channels.OverlappingFileLockException ignored) { }
                if (lock == null) Thread.sleep(50);
            }
            if (lock == null) throw new IOException("The previous console is still open");
            try {
                if (!Files.exists(receiptFile)) {
                    receipts.addAll(readReceipts(legacyRequests).stream().filter(receipt -> receipt.user.equals(user)).toList());
                    saveReceipts();
                }
                if (!Files.exists(bookmarks)) {
                    loadBookmarks(legacyBookmarks);
                    user = fixedUser;
                    saveBookmarks();
                }
            } finally { lock.release(); }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            receiptStoreReady = false;
            out.println("Chưa khôi phục xong lệnh cũ. Đã khóa gửi tiền; đóng console cũ rồi mở lại.");
        } catch (IOException | RuntimeException error) {
            receiptStoreReady = false;
            out.println("Chưa khôi phục an toàn được lệnh cũ. Đã khóa gửi tiền; giữ dữ liệu và đóng console cũ trước khi mở lại.");
        }
    }

    private void loadBookmarks() {
        loadBookmarks(bookmarks);
    }

    private void loadBookmarks(Path source) {
        if (source == null || !Files.isRegularFile(source)) return;
        try {
            if (Files.size(source) > 262144) throw new IOException("Bookmark file too large");
            JsonNode saved = json.readTree(source.toFile());
            for (JsonNode wallet : saved.path("wallets")) {
                InputRules.identifier(text(wallet, "accountId"));
                InputRules.client(text(wallet, "ownerId"));
                if (!Set.of("USER", "MERCHANT", "CLEARING").contains(text(wallet, "kind"))) throw new IOException("Invalid wallet type");
                rememberWallet(wallet);
            }
            for (String owner : List.of("alice", "bob", "admin")) {
                if (fixedUser != null && !owner.equals(fixedUser)) continue;
                String id = text(saved.path("selected"), owner);
                if (wallets.containsKey(id) && (owner.equals("admin") || text(wallets.get(id), "ownerId").equals(owner)))
                    selectedWallets.put(owner, id);
            }
            user = InputRules.client(text(saved, "user"));
        } catch (Exception ignored) {
            wallets.clear();
            selectedWallets.clear();
            out.println("Không đọc được danh sách ví đã nhớ. Bạn vẫn có thể mở ví bằng mã; dữ liệu trên máy chủ còn nguyên.");
        }
    }

    private void saveBookmarks() {
        if (bookmarks == null) return;
        if (fixedUser != null && (!receiptStoreReady || receiptLock == null || !receiptLock.isValid())) return;
        Path temporary = null;
        try {
            var known = wallets.values().stream().map(w -> Map.of("accountId", text(w, "accountId"),
                    "ownerId", text(w, "ownerId"), "kind", text(w, "kind"))).toList();
            Files.createDirectories(bookmarks.toAbsolutePath().getParent());
            temporary = Files.createTempFile(bookmarks.toAbsolutePath().getParent(), "console-wallets-", ".tmp");
            json.writeValue(temporary.toFile(), Map.of("user", user, "wallets", known, "selected", selectedWallets));
            try { Files.move(temporary, bookmarks, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, bookmarks, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ignored) {
            out.println("Không lưu được danh sách chọn nhanh; các ví vẫn nằm trên máy chủ.");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private void loadReceipts() {
        if (receiptFile == null) return;
        try {
            Files.createDirectories(receiptFile.toAbsolutePath().getParent());
            receiptLockChannel = FileChannel.open(receiptFile.resolveSibling("console-requests.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            receiptLock = receiptLockChannel.tryLock();
            if (receiptLock == null) throw new IOException("Console request store is already open");
            List<Receipt> restored = readReceipts(receiptFile);
            if (fixedUser != null && restored.stream().anyMatch(receipt -> !receipt.user.equals(fixedUser)))
                throw new IOException("Receipt belongs to a different console account");
            receipts.addAll(restored);
        } catch (Exception ignored) {
            receiptStoreReady = false;
            out.println("Không đọc/khóa được file lệnh đã lưu. Đã khóa gửi tiền để tránh giao dịch trùng.");
            out.println("Giữ nguyên work/console-requests.json để kiểm tra; nếu console khác đang mở, hãy đóng rồi chạy lại.");
        }
    }

    private List<Receipt> readReceipts(Path source) throws IOException {
        if (!Files.exists(source)) return List.of();
        if (!Files.isRegularFile(source) || Files.size(source) > 1_048_576)
            throw new IOException("Invalid console request store");
        ObjectMapper reader = new ObjectMapper()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JsonNode saved = reader.readTree(source.toFile());
        if (saved == null || !saved.isObject() || saved.size() != 2 || !saved.path("version").isInt() || saved.path("version").intValue() != 1
                || !saved.path("receipts").isArray() || saved.path("receipts").size() > 1000)
            throw new IOException("Invalid console request store");
        List<Receipt> restored = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode entry : saved.path("receipts")) {
            Receipt receipt = readReceipt(entry);
            if (receipt.key != null && !keys.add(receipt.user + ":" + receipt.key))
                throw new IOException("Duplicate console request key");
            restored.add(receipt);
        }
        return restored;
    }

    private Receipt readReceipt(JsonNode entry) throws IOException {
        if (!entry.isObject() || entry.size() != 11 || !entry.path("body").isObject()
                || !entry.path("payment").isBoolean() || !entry.path("retryAllowed").isBoolean()
                || !entry.path("attempted").isBoolean() || !entry.path("settlementRequested").isBoolean())
            throw new IOException("Invalid console receipt");
        Set<String> fields = Set.of("user", "kind", "path", "body", "key", "payment", "id", "data",
                "retryAllowed", "attempted", "settlementRequested");
        var names = entry.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) throw new IOException("Invalid console receipt field");
        for (String field : List.of("user", "kind", "path", "id"))
            if (!entry.path(field).isTextual()) throw new IOException("Invalid console receipt text");
        String actor = InputRules.client(text(entry, "user"));
        String operation = text(entry, "kind"), path = text(entry, "path"), id = text(entry, "id");
        if (!Set.of("TRANSFER", "DEPOSIT", "PAYMENT", "WITHDRAWAL").contains(operation))
            throw new IOException("Invalid console operation");
        if (!id.isEmpty()) InputRules.uuid(id);
        boolean payment = entry.path("payment").booleanValue();
        JsonNode payload = entry.path("body");
        if (!payload.path("amount").isIntegralNumber() || !payload.path("amount").canConvertToLong())
            throw new IOException("Invalid console amount");
        InputRules.amount(payload.path("amount").longValue());
        InputRules.identifier(text(payload, payment ? "accountId" : "sourceAccount"));
        String key = entry.path("key").isNull() ? null : InputRules.uuid(text(entry, "key"));
        if (path.isEmpty()) {
            if (key != null || id.isEmpty() || payload.size() != 2)
                throw new IOException("Invalid receipt lookup");
        } else {
            String expectedPath = switch (operation) {
                case "TRANSFER" -> "/api/transfers";
                case "DEPOSIT" -> "/api/deposits";
                case "WITHDRAWAL" -> "/api/withdrawals";
                default -> "/api/payments";
            };
            if (!path.equals(expectedPath) || key == null || payment == operation.equals("TRANSFER"))
                throw new IOException("Invalid console request route");
            if (operation.equals("TRANSFER")) {
                if (payload.size() != 4 || !text(payload, "kind").equals("TRANSFER"))
                    throw new IOException("Invalid transfer payload");
                InputRules.identifier(text(payload, "destinationAccount"));
            } else if (operation.equals("PAYMENT")) {
                if (payload.size() != 3) throw new IOException("Invalid payment payload");
                InputRules.identifier(text(payload, "destination"));
            } else if (payload.size() != 2) throw new IOException("Invalid bank payload");
        }
        Receipt receipt = new Receipt(actor, operation, path,
                json.convertValue(payload, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() { }),
                key, payment);
        receipt.id = id;
        receipt.retryAllowed = entry.path("retryAllowed").booleanValue();
        receipt.attempted = entry.path("attempted").booleanValue();
        receipt.settlementRequested = entry.path("settlementRequested").booleanValue();
        receipt.data = entry.path("data").isNull() ? null : entry.path("data");
        if (receipt.data != null && (!receipt.data.isObject() || !receiptData(receipt.data).equals(receipt.data)))
            throw new IOException("Invalid receipt state");
        if (!id.isEmpty() && !id.equals(text(receipt.data, "id")))
            throw new IOException("Inconsistent receipt id");
        if (receipt.settlementRequested && (!payment || !operation.equals("DEPOSIT") || id.isEmpty()))
            throw new IOException("Invalid settlement state");
        return receipt;
    }

    private JsonNode receiptData(JsonNode data) {
        if (data == null) return null;
        var safe = json.createObjectNode();
        for (String field : List.of("id", "kind", "status", "account_id", "destination", "source_account",
                "destination_account", "transaction_id", "journal_id", "error_code", "code", "error")) {
            JsonNode value = data.path(field);
            if (value.isTextual() && value.textValue().length() <= 100) safe.set(field, value);
        }
        if (data.path("amount").isIntegralNumber() && data.path("amount").canConvertToLong())
            safe.set("amount", data.path("amount"));
        return safe;
    }

    private void saveReceipts() {
        if (!receiptStoreReady) throw new UserMessage("File lệnh chưa an toàn. Đã khóa gửi tiền; giữ file và chạy lại sau khi kiểm tra.");
        if (receiptFile == null) return;
        Path temporary = null;
        try {
            while (receipts.size() > 200) {
                Receipt finished = receipts.stream().filter(receipt -> !receipt.unresolved()).findFirst().orElse(null);
                if (finished == null) break;
                receipts.remove(finished);
            }
            if (receipts.size() > 1000) throw new IOException("Console request store is full");
            var entries = receipts.stream().map(receipt -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("user", receipt.user); entry.put("kind", receipt.kind); entry.put("path", receipt.path);
                entry.put("body", receipt.body); entry.put("key", receipt.key); entry.put("payment", receipt.payment());
                entry.put("id", receipt.id); entry.put("data", receiptData(receipt.data));
                entry.put("retryAllowed", receipt.retryAllowed); entry.put("attempted", receipt.attempted);
                entry.put("settlementRequested", receipt.settlementRequested);
                return entry;
            }).toList();
            byte[] bytes = json.writeValueAsBytes(Map.of("version", 1, "receipts", entries));
            if (bytes.length > 1_048_576) throw new IOException("Console request store is full");
            temporary = Files.createTempFile(receiptFile.toAbsolutePath().getParent(), "console-requests-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, receiptFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            receiptStoreReady = false;
            throw new UserMessage("Không lưu an toàn được lệnh tiền. Đã khóa gửi tiền; giữ work/console-requests.json để khôi phục.");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private void closeReceiptStore() {
        try { if (receiptLock != null) receiptLock.release(); } catch (IOException ignored) { }
        try { if (receiptLockChannel != null) receiptLockChannel.close(); } catch (IOException ignored) { }
    }

    private void showEntries() throws Exception {
        JsonNode account = currentAccount();
        display.account(account);
        display.entries(data(user, "GET", "/api/accounts/" + text(account, "accountId") + "/entries", null));
    }

    private void paymentMenu() throws Exception {
        display.title("THANH TOÁN / RÚT TIỀN");
        int option = menu("Thanh toán cho ví cửa hàng", "Rút tiền về ngân hàng");
        if (option == 1) payment("PAYMENT");
        if (option == 2) payment("WITHDRAWAL");
    }

    private void payment(String kind) throws Exception {
        display.title(kind(kind).toUpperCase(Locale.ROOT) + " | 0 ở ô nhập để hủy");
        var account = currentAccount();
        String source = text(account, "accountId");
        display.account(account);
        if (!kind.equals("PAYMENT")) checkBankReady();
        if (kind.equals("DEPOSIT")) out.println("Gồm 2 bước: tạo lệnh nạp, rồi xác nhận ngân hàng ngay trên màn hình này.");
        String destination = kind.equals("PAYMENT") ? destination("MERCHANT", source) : "bank-clearing";
        long amount = amount();
        out.println("\nKiểm tra: " + kind(kind) + " " + money(amount) + " | Ví " + source);
        if (!kind.equals("DEPOSIT")) out.println("Nơi nhận: " + destination);
        if (!confirm("Gửi lệnh " + kind(kind).toLowerCase(Locale.ROOT))) return;
        Map<String, Object> body = new LinkedHashMap<>(Map.of("accountId", source, "amount", amount));
        if (kind.equals("PAYMENT")) body.put("destination", destination);
        String path = switch (kind) {
            case "DEPOSIT" -> "/api/deposits";
            case "WITHDRAWAL" -> "/api/withdrawals";
            default -> "/api/payments";
        };
        Receipt receipt = newReceipt(kind, path, body);
        send(receipt);
        receiptScreen(receipt);
    }

    private void transfer() throws Exception {
        display.title("CHUYỂN TIỀN | 0 ở ô nhập để hủy");
        JsonNode account = currentAccount();
        String source = text(account, "accountId");
        display.account(account);
        String destination = destination("USER", source);
        long amount = amount();
        out.println("\nTừ ví " + source + " sang ví " + destination + ": " + money(amount));
        if (!confirm("Xác nhận chuyển tiền")) return;
        Receipt receipt = newReceipt("TRANSFER", "/api/transfers", Map.of("sourceAccount", source,
                "destinationAccount", destination, "amount", amount, "kind", "TRANSFER"));
        send(receipt);
        receiptScreen(receipt);
    }

    private String destination(String requiredKind, String source) {
        List<JsonNode> known = wallets.values().stream().filter(w -> text(w, "kind").equals(requiredKind)
                && !text(w, "accountId").equals(source)).toList();
        out.println("\nChọn " + (requiredKind.equals("MERCHANT") ? "cửa hàng" : "ví nhận") + ":");
        out.println("Một tài khoản có thể có nhiều ví. Hãy kiểm tra đúng mã ví nhận.");
        for (int i = 0; i < known.size(); i++)
            out.println((i + 1) + ". " + text(known.get(i), "ownerId") + " | " + text(known.get(i), "accountId"));
        out.println((known.size() + 1) + ". Nhập mã ví khác");
        out.println("0. Hủy");
        int option = choice(known.size() + 1);
        if (option == 0) throw new Cancelled();
        String id = option <= known.size() ? text(known.get(option - 1), "accountId") : identifier("Mã ví nhận", null);
        if (id.equals(source)) throw new UserMessage("Ví nhận phải khác ví đang dùng.");
        return id;
    }

    private Receipt newReceipt(String kind, String path, Map<String, Object> body) {
        if (!receiptStoreReady) throw new UserMessage("File lệnh chưa an toàn. Đã khóa gửi tiền; giữ file và chạy lại sau khi kiểm tra.");
        String source = Objects.toString(body.get(kind.equals("TRANSFER") ? "sourceAccount" : "accountId"), "");
        if (receipts.stream().anyMatch(previous -> previous.user.equals(user) && previous.unresolved()
                && source.equals(Objects.toString(previous.body.get(previous.payment() ? "accountId" : "sourceAccount"), ""))))
            throw new UserMessage("Ví này còn lệnh chưa có kết quả cuối cùng. Mở menu 6 để tiếp tục lệnh cũ trước khi gửi lệnh mới.");
        Receipt receipt = new Receipt(user, kind, path, new LinkedHashMap<>(body), UUID.randomUUID().toString());
        receipts.add(receipt);
        saveReceipts();
        return receipt;
    }

    private void send(Receipt receipt) throws Exception {
        boolean previousUncertainty = receipt.attempted && receipt.retryAllowed && receipt.id.isEmpty();
        receipt.attempted = true;
        saveReceipts();
        out.println("Đang gửi yêu cầu...");
        Response response = client.request(receipt.user, "POST", receipt.path, receipt.body, receipt.key);
        receipt.data = response.body();
        receipt.retryAllowed = previousUncertainty || unresolvedHttp(response.status());
        if (!response.successful() && !isTransactionResult(response.body())) {
            saveReceipts();
            throw failure(response);
        }
        String id = text(response.body(), "id");
        try { InputRules.uuid(id); }
        catch (IllegalArgumentException ignored) {
            receipt.retryAllowed = true;
            saveReceipts();
            throw new IOException("Thiếu mã biên nhận hợp lệ.");
        }
        receipt.id = id;
        saveReceipts();
        refresh(receipt);
    }

    private void refresh(Receipt receipt) throws Exception {
        if (receipt.id.isEmpty()) return;
        String path = (receipt.payment() ? "/api/payments/" : "/api/transactions/") + receipt.id;
        for (int attempt = 0; attempt < 12; attempt++) {
            receipt.data = data(receipt.user, "GET", path, null);
            if (!text(receipt.data, "kind").isEmpty()) receipt.kind = text(receipt.data, "kind");
            saveReceipts();
            String status = text(receipt.data, "status");
            if (status.equals("COMPLETED") || status.equals("REJECTED") || waitingForBank(receipt)) return;
            if (attempt < 11) Thread.sleep(250);
        }
    }

    private void receiptScreen(Receipt receipt) throws Exception {
        while (true) {
            display.title("BIÊN NHẬN | " + kind(receipt.kind));
            if (receipt.id.isEmpty()) {
                if (!receipt.retryAllowed) {
                    out.println("Yêu cầu đã bị từ chối. " + error(text(receipt.data, "code"))); pause(); return;
                }
                out.println("Chưa có mã biên nhận từ máy chủ. Chưa thể xác định tiền đã chuyển hay chưa.");
                out.println("Lệnh gốc đã được giữ; gửi lại sẽ giữ cùng mã chống lặp và cùng nội dung.");
                int option = menu("Gửi lại CHÍNH lệnh cũ để lấy kết quả");
                if (option == 0) return;
                send(receipt);
                continue;
            }
            display.receipt(receipt.data, receipt.payment());
            if (waitingForBank(receipt)) {
                if (receipt.settlementRequested)
                    out.println("Lần xác nhận trước chưa có kết quả cuối cùng. Tiếp tục cùng biên nhận để tránh tạo lệnh mới.");
                out.println("\nBước tiếp theo: xác nhận thanh toán tại ngân hàng.");
                int option = menu("Xác nhận ngân hàng và kiểm tra tiền vào ví", "Chỉ làm mới trạng thái");
                if (option == 0) return;
                if (option == 1) settle(receipt); else refresh(receipt);
            } else if (text(receipt.data, "status").equals("COMPLETED") || text(receipt.data, "status").equals("REJECTED")) {
                if (text(receipt.data, "status").equals("REJECTED") && receipt.payment()
                        && !text(receipt.data, "transaction_id").isEmpty())
                    out.println("Admin có thể tra mã giao dịch để xem lý do: " + text(receipt.data, "transaction_id"));
                String account = Objects.toString(receipt.body.get(receipt.payment() ? "accountId" : "sourceAccount"), "");
                if (!account.isEmpty()) {
                    var latest = data(receipt.user, "GET", "/api/accounts/" + account, null);
                    out.println("Số dư ví " + account + " hiện tại: " + money(latest.path("balance").asLong()));
                    rememberWallet(latest);
                }
                while (true) {
                    String next = read("Enter: về menu | 1: xem tiền vào/ra của ví | 0: về menu");
                    if (next.isEmpty() || next.equals("0")) break;
                    if (next.equals("1") && !account.isEmpty())
                        display.entries(data(receipt.user, "GET", "/api/accounts/" + account + "/entries", null));
                    else out.println("Nhấn Enter để về menu hoặc nhập 1 để xem lịch sử ví.");
                }
                return;
            } else {
                int option = menu("Kiểm tra lại kết quả của lệnh này");
                if (option == 0) return;
                refresh(receipt);
            }
        }
    }

    private boolean waitingForBank(Receipt receipt) {
        return receipt.kind.equals("DEPOSIT") && text(receipt.data, "status").equals("PENDING")
                && text(receipt.data, "transaction_id").isEmpty();
    }

    private void settle(Receipt receipt) throws Exception {
        receipt.settlementRequested = true;
        saveReceipts();
        out.println("Đang xác nhận ngân hàng và chờ tiền vào ví...");
        data(receipt.user, "POST", "/api/bank/settle/" + receipt.id, Map.of());
        refresh(receipt);
    }

    private void activityMenu() {
        while (true) {
            display.title("GIAO DỊCH & THÔNG BÁO | " + user);
            List<Receipt> visible = new ArrayList<>(receipts.stream().filter(r -> r.user.equals(user)).toList());
            Collections.reverse(visible);
            out.println("Biên nhận đã lưu (chọn số để cập nhật/tiếp tục lệnh):");
            if (visible.isEmpty()) out.println("Chưa có lệnh nào. Các giao dịch của bạn sẽ được hiển thị tại đây.");
            for (int i = 0; i < visible.size(); i++) {
                Receipt receipt = visible.get(i);
                out.println((i + 1) + ". " + kind(receipt.kind) + " | "
                        + money(((Number) receipt.body.getOrDefault("amount", 0L)).longValue()) + " | "
                        + (receipt.id.isEmpty() && !receipt.retryAllowed ? "YÊU CẦU BỊ TỪ CHỐI" : status(receipt.data)));
            }
            out.println((visible.size() + 1) + ". Tra cứu bằng mã biên nhận / mã giao dịch đã lưu");
            out.println((visible.size() + 2) + ". Xem thông báo");
            out.println("0. Quay lại");
            int option = choice(visible.size() + 2);
            if (option == 0) return;
            perform(() -> {
                if (option <= visible.size()) {
                    Receipt receipt = visible.get(option - 1);
                    try { refresh(receipt); }
                    catch (InterruptedException error) { throw error; }
                    catch (Exception error) {
                        out.println("Chưa cập nhật được giao dịch. Đang hiển thị biên nhận đã lưu; trạng thái có thể chưa mới nhất.");
                    }
                    receiptScreen(receipt);
                } else if (option == visible.size() + 1) lookupReceipt();
                else notifications();
            });
        }
    }

    private void lookupReceipt() throws Exception {
        int option = menu("Biên nhận nạp / rút / thanh toán", "Giao dịch chuyển tiền");
        if (option == 0) return;
        String id;
        while (true) {
            id = form("Dán mã", null);
            try { InputRules.uuid(id); break; }
            catch (IllegalArgumentException ignored) { out.println("Mã cần đủ 36 ký tự UUID, chữ thường và dấu gạch ngang. Hãy dán lại mã biên nhận."); }
        }
        Receipt receipt = new Receipt(user, option == 1 ? "PAYMENT" : "TRANSFER", "", Map.of(), null);
        receipt.id = id;
        refresh(receipt);
        receipt.kind = text(receipt.data, "kind");
        receipt.body = Map.of("amount", receipt.data.path("amount").asLong(),
                receipt.payment() ? "accountId" : "sourceAccount", text(receipt.data, receipt.payment() ? "account_id" : "source_account"));
        receipts.add(receipt);
        saveReceipts();
        receiptScreen(receipt);
    }

    private void notifications() throws Exception {
        display.title("THÔNG BÁO | " + user);
        JsonNode values = data(user, "GET", "/api/notifications", null);
        if (values.isEmpty()) out.println("Chưa có thông báo. Sự kiện có thể tới sau giao dịch vài giây; quay lại để cập nhật.");
        for (JsonNode value : values) {
            JsonNode event = json.readTree(text(value, "payload"));
            out.println(text(value, "created_at") + " | " + kind(text(event, "kind")) + " " + money(event.path("amount").asLong()));
            out.println("  " + text(event, "source") + " -> " + text(event, "destination"));
        }
        pause();
    }

    private void checkBankReady() throws Exception {
        Response response = client.request(user, "GET", "/api/accounts/bank-clearing", null, null);
        if (response.status() == 404)
            throw new UserMessage("Dịch vụ ngân hàng chưa sẵn sàng. Vui lòng thử lại sau. Chưa gửi lệnh tiền.");
        if (response.status() == 403 && text(response.body(), "code").equals("NOT_WALLET_OWNER")) return;
        if (!response.successful()) throw failure(response);
        if (!text(response.body(), "kind").equals("CLEARING"))
            throw new UserMessage("Tài khoản ngân hàng chưa đủ điều kiện nạp/rút. Liên hệ quản trị viên để kiểm tra.");
    }

    private JsonNode data(String actor, String method, String path, Object body) throws Exception {
        Response response = client.request(actor, method, path, body, null);
        if (!response.successful()) throw failure(response);
        return response.body();
    }

    private UserMessage failure(Response response) {
        if (unresolvedHttp(response.status()))
            return new UserMessage("Hệ thống chưa trả kết quả cuối cùng (HTTP " + response.status()
                    + "). Nếu vừa gửi lệnh tiền, mở menu 6 để kiểm tra/tiếp tục cùng lệnh cũ.");
        String code = text(response.body(), "error");
        if (code.isEmpty()) code = text(response.body(), "code");
        if (code.isEmpty() && response.status() == 403) code = "NOT_WALLET_OWNER";
        if (code.isEmpty() && response.status() == 404) code = "NOT_FOUND";
        return new UserMessage(error(code) + " (HTTP " + response.status() + ")");
    }

    private boolean unresolvedHttp(int status) { return status >= 500 || status == 408 || status == 429; }

    private boolean isTransactionResult(JsonNode value) {
        return !text(value, "id").isEmpty() && text(value, "status").equals("REJECTED");
    }

    private String read(String prompt) {
        synchronized (out) {
            out.print(prompt + " > ");
            out.flush();
            if (liveMonitor != null)
                liveMonitor.setContext(user, selectedWallets.get(user), prompt + " > ", liveInput);
        }
        try {
            if (!input.hasNextLine()) throw new EndOfInput();
            return input.nextLine().trim();
        } finally {
            if (liveMonitor != null)
                liveMonitor.setContext(user, selectedWallets.get(user), "", false);
        }
    }

    private String form(String prompt, String defaultValue) {
        String value = read(prompt + " | 0: hủy");
        if (value.equals("0")) throw new Cancelled();
        return value.isEmpty() && defaultValue != null ? defaultValue : value;
    }

    private String identifier(String prompt, String defaultValue) {
        while (true) {
            try { return InputRules.identifier(form(prompt, defaultValue)); }
            catch (IllegalArgumentException ignored) { out.println("Mã ví dùng 1–100 chữ không dấu, số, dấu - hoặc _. Hãy nhập lại."); }
        }
    }

    private long amount() {
        while (true) {
            String value = form("Số tiền VND (ví dụ 10000 = 10.000 VND)", null);
            try {
                if (!value.matches("[0-9]{1,16}")) throw new IllegalArgumentException();
                return InputRules.amount(Long.parseLong(value));
            } catch (IllegalArgumentException ignored) {
                out.println("Nhập số nguyên từ 1 đến " + money(InputRules.MAX_AMOUNT) + ", không dùng dấu chấm/phẩy. 0 để hủy.");
            }
        }
    }

    private int menu(String... items) {
        for (int i = 0; i < items.length; i++) out.println((i + 1) + ". " + items[i]);
        out.println("0. Quay lại");
        return choice(items.length);
    }

    private int choice(int max) {
        while (true) {
            String value = read("Chọn số");
            try {
                int result = Integer.parseInt(value);
                if (result >= 0 && result <= max) return result;
            } catch (NumberFormatException ignored) { }
            out.println("Chọn một số từ 0 đến " + max + ".");
        }
    }

    private boolean confirm(String action) {
        out.println("1. " + action + "\n0. Hủy (hoặc Enter)");
        while (true) {
            String value = read("Xác nhận");
            if (value.equals("1")) return true;
            if (value.isEmpty() || value.equals("0")) { out.println("Đã hủy, chưa gửi lệnh."); return false; }
            out.println("Nhập 1 để xác nhận; 0 hoặc Enter để hủy.");
        }
    }

    private void pause() { read("Nhấn Enter để quay lại"); }

    private void perform(Action action) {
        try { action.run(); }
        catch (Cancelled ignored) { out.println("Đã quay lại. Các bước đã hoàn tất vẫn được lưu."); }
        catch (EndOfInput error) { throw error; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new EndOfInput(); }
        catch (UserMessage | IllegalStateException error) { out.println("\n" + error.getMessage()); pause(); }
        catch (IOException error) {
            out.println("\nChưa nhận được kết quả từ hệ thống. Kiểm tra kết nối và thử lại sau.");
            out.println("Nếu vừa gửi lệnh tiền: mở menu 6 để kiểm tra/gửi lại chính lệnh cũ, tránh tạo lệnh mới.");
            pause();
        } catch (Exception error) {
            out.println("\nKhông đọc được kết quả. Mở menu 6 kiểm tra biên nhận; không tạo lại lệnh tiền để thử.");
            pause();
        }
    }

    @FunctionalInterface
    private interface Action { void run() throws Exception; }

    private static final class Receipt {
        final String user;
        String kind;
        final String path;
        Map<String, Object> body;
        final String key;
        final boolean payment;
        String id = "";
        JsonNode data;
        boolean retryAllowed = true;
        boolean attempted;
        boolean settlementRequested;

        Receipt(String user, String kind, String path, Map<String, Object> body, String key) {
            this(user, kind, path, body, key, !kind.equals("TRANSFER"));
        }

        Receipt(String user, String kind, String path, Map<String, Object> body, String key, boolean payment) {
            this.user = user;
            this.kind = kind;
            this.path = path;
            this.body = body;
            this.key = key;
            this.payment = payment;
        }
        boolean payment() { return payment; }
        boolean unresolved() {
            return id.isEmpty() ? retryAllowed : !Set.of("COMPLETED", "REJECTED").contains(text(data, "status"));
        }
    }

    private static final class Cancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
    private static final class EndOfInput extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
    private static final class UserMessage extends RuntimeException {
        private static final long serialVersionUID = 1L;
        UserMessage(String message) { super(message); }
    }
}
