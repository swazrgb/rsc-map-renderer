package openrsc.bot.api;

import java.util.List;
import org.jetbrains.annotations.Nullable;

public record BankOpsOut(
        List<String> unanswered,
        @Nullable SessionTick oldestSent,
        @Nullable SessionTick closeSent) {

    public static final BankOpsOut NONE = new BankOpsOut(List.of(), null, null);

    public BankOpsOut {
        unanswered = List.copyOf(unanswered);
    }

    public boolean any() {
        return oldestSent != null || closeSent != null;
    }
}
