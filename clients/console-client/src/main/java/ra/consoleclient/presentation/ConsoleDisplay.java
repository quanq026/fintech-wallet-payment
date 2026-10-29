package ra.consoleclient.presentation;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.PrintStream;
import java.text.NumberFormat;
import java.util.Locale;

final class ConsoleDisplay {
    private final PrintStream out;

    ConsoleDisplay(PrintStream out) {
        this.out = out;
    }

    void title(String text) {
        out.println("\n============================================================");
        out.println(text);
        out.println("============================================================");
    }

    static String money(long value) {
        return NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(value) + " VND";
    }

    static String text(JsonNode value, String field) {
        return value == null ? "" : value.path(field).asText("");
    }

    static String kind(String value) {
        return switch (value) {
            case "DEPOSIT" -> "Nạp tiền";
            case "WITHDRAWAL" -> "Rút tiền";
            case "PAYMENT" -> "Thanh toán cửa hàng";
            case "TRANSFER" -> "Chuyển tiền";
            case "USER" -> "Ví cá nhân";
            case "MERCHANT" -> "Ví cửa hàng";
            case "CLEARING" -> "Tài khoản đối ứng ngân hàng";
            default -> value;
        };
    }

    static String status(JsonNode value) {
        String status = text(value, "status");
        return switch (status) {
            case "COMPLETED" -> "HOÀN TẤT — đã ghi nhận tiền";
            case "REJECTED" -> "BỊ TỪ CHỐI — không thực hiện giao dịch";
            case "PENDING" -> text(value, "kind").equals("DEPOSIT")
                    && text(value, "transaction_id").isEmpty()
                    ? "CHỜ NGÂN HÀNG XÁC NHẬN — chưa có xác nhận hoàn tất"
                    : "ĐANG XỬ LÝ — cần kiểm tra lại";
            default -> "CHƯA CÓ KẾT QUẢ CUỐI CÙNG — cần kiểm tra lại";
        };
    }

    void account(JsonNode value) {
        out.println("Ví       : " + text(value, "accountId"));
        out.println("Chủ ví   : " + text(value, "ownerId"));
        out.println("Loại     : " + kind(text(value, "kind")));
        out.println("Số dư    : " + money(value.path("balance").asLong()));
    }

    void receipt(JsonNode value, boolean payment) {
        out.println("Kết quả  : " + status(value));
        out.println("Thao tác : " + kind(text(value, "kind")));
        out.println("Số tiền  : " + money(value.path("amount").asLong()));
        out.println("Ví       : " + text(value, payment ? "account_id" : "source_account"));
        if (!text(value, "kind").equals("DEPOSIT"))
            out.println("Nơi nhận : " + text(value, payment ? "destination" : "destination_account"));
        out.println((payment ? "Mã biên nhận nạp/rút/thanh toán: " : "Mã giao dịch chuyển tiền: ") + text(value, "id"));
        if (!text(value, "error_code").isEmpty()) out.println("Lý do    : " + error(text(value, "error_code")));
        if (!text(value, "status").equals("COMPLETED") && !text(value, "status").equals("REJECTED"))
            out.println("Mở 'Giao dịch & thông báo' để tiếp tục chính lệnh này; không tạo lệnh mới để thử lại.");
    }

    void entries(JsonNode values) {
        if (values.isEmpty()) {
            out.println("Ví chưa có giao dịch. Hãy nạp tiền trước, rồi quay lại xem lịch sử.");
            return;
        }
        out.printf("%-24s %-20s %s%n", "THỜI GIAN", "BIẾN ĐỘNG", "MÃ BÚT TOÁN");
        for (JsonNode entry : values) {
            long amount = entry.path("amount").asLong();
            out.printf("%-24s %-20s %s%n", text(entry, "createdAt"),
                    (amount > 0 ? "+" : "") + money(amount), text(entry, "journalId"));
        }
        out.println("Dấu +: tiền vào ví. Dấu -: tiền ra khỏi ví.");
    }

    void reconciliation(JsonNode result, JsonNode audit) {
        var ledger = result.path("ledger");
        boolean balanced = ledger.has("totalAmount") && ledger.path("totalAmount").asLong() == 0
                && ledger.path("unbalancedJournals").asLong(-1) == 0
                && ledger.path("negativeUserAccounts").asLong(-1) == 0;
        out.println((balanced ? "[ĐẠT]" : "[CẦN KIỂM TRA]") + " Sổ cái: tổng tiền = 0, không lệch bút toán, không âm ví người dùng.");
        out.println("Bút toán: " + ledger.path("journalCount").asLong() + " | Dòng ghi sổ: " + ledger.path("entryCount").asLong());
        out.println("Tổng toàn hệ thống: " + money(ledger.path("totalAmount").asLong())
                + " (bao gồm tài khoản đối ứng ngân hàng).");
        out.println("Bút toán lệch: " + ledger.path("unbalancedJournals").asLong()
                + " | Ví người dùng bị âm: " + ledger.path("negativeUserAccounts").asLong());
        if (!audit.path("valid").asBoolean()) {
            out.println("[CẦN KIỂM TRA] Chuỗi audit không hợp lệ.");
        } else if (result.path("outboxPending").asLong(-1) != 0
                || audit.path("verifiedEvents").asLong(-1) < ledger.path("journalCount").asLong()) {
            out.println("[ĐANG ĐỒNG BỘ] Audit hợp lệ nhưng sự kiện chưa tới đủ. Đợi vài giây rồi đối soát lại.");
        } else {
            out.println("[ĐẠT] Chuỗi audit hợp lệ; sự kiện đã đồng bộ tại thời điểm kiểm tra.");
        }
        out.println("Sự kiện audit đã kiểm tra: " + audit.path("verifiedEvents").asLong()
                + " | Sự kiện chờ gửi: " + result.path("outboxPending").asLong());
    }

    static String error(String code) {
        return switch (code) {
            case "NOT_WALLET_OWNER", "NOT_PAYMENT_OWNER", "NOT_TRANSACTION_OWNER" ->
                    "Bạn không sở hữu ví/giao dịch này. Chọn đúng người dùng rồi thử lại.";
            case "ADMIN_REQUIRED" -> "Chức năng dành cho admin. Đổi người dùng ở menu chính.";
            case "NOT_FOUND", "ACCOUNT_NOT_FOUND", "PAYMENT_NOT_FOUND", "TRANSACTION_NOT_FOUND" ->
                    "Không tìm thấy mã này. Kiểm tra mã hoặc tạo ví trước.";
            case "ALREADY_EXISTS", "ACCOUNT_ALREADY_EXISTS" -> "Mã ví đã tồn tại. Chọn 'Mở/chọn ví' hoặc dùng mã khác.";
            case "INSUFFICIENT_FUNDS" -> "Ví không đủ tiền. Xem số dư hoặc nạp thêm trước khi thử lại.";
            case "FAILED_PRECONDITION" -> "Ví chưa đủ điều kiện giao dịch. Kiểm tra số dư và trạng thái ví.";
            case "RISK_REJECTED" -> "Quy tắc rủi ro đã từ chối. Admin có thể xem lý do ở 'Cảnh báo rủi ro'.";
            case "MERCHANT_REQUIRED" -> "Ví nhận phải là ví cửa hàng; không dùng ví cá nhân để thanh toán cửa hàng.";
            case "DEPOSIT_REQUIRED" -> "Chỉ lệnh nạp tiền mới cần xác nhận ngân hàng.";
            case "IDEMPOTENCY_PAYLOAD_MISMATCH" -> "Nội dung khác với lệnh đã gửi. Không thể dùng lại mã của lệnh cũ.";
            case "RATE_LIMIT" -> "Gửi quá nhanh. Đợi rồi kiểm tra hoặc gửi lại cùng lệnh trong lịch sử giao dịch.";
            case "DEPENDENCY_UNAVAILABLE", "GATEWAY_UNAVAILABLE", "DEPENDENCY_REJECTED" ->
                    "Dịch vụ chưa xử lý được yêu cầu. Kiểm tra biên nhận trước khi tạo lệnh mới.";
            case "INVALID_SIGNATURE", "UNKNOWN_CLIENT", "REQUEST_EXPIRED", "BAD_SIGNATURE", "EXPIRED_REQUEST", "SIGNATURE_REQUIRED", "INVALID_SIGNATURE_HEADERS" ->
                    "Không xác thực được yêu cầu. Vui lòng thử lại hoặc liên hệ quản trị viên.";
            case "INVALID_PAYLOAD", "INVALID_AMOUNT_OR_ACCOUNT", "INVALID_ARGUMENT", "INVALID_DESTINATION", "INVALID_TRANSFER" ->
                    "Dữ liệu không hợp lệ. Kiểm tra mã ví, ví nhận và số tiền VND nguyên dương.";
            default -> "Yêu cầu chưa thực hiện được. Kiểm tra dữ liệu và trạng thái hệ thống."
                    + (code.matches("[A-Z_]{1,80}") ? " Mã lỗi: " + code : "");
        };
    }
}
