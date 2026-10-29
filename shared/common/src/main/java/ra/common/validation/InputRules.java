package ra.common.validation;

import java.util.Set;

public final class InputRules {
    public static final long MAX_AMOUNT = 9_000_000_000_000_000L;

    private InputRules() {
    }

    public static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("Identifier must contain 1–100 ASCII letters, digits, _ or -");
        return value;
    }

    public static String uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("UUID must use canonical lowercase format");
        return value;
    }

    public static long amount(long value) {
        if (value <= 0 || value > MAX_AMOUNT)
            throw new IllegalArgumentException("Amount must be an integer from 1 to " + MAX_AMOUNT + " VND");
        return value;
    }

    public static String client(String value) {
        if (value == null || !Set.of("alice", "bob", "admin").contains(value))
            throw new IllegalArgumentException("Unknown demo client");
        return value;
    }
}
