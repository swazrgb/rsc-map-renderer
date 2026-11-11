package openrsc.bot.core.world;

import java.util.EnumSet;
import java.util.Set;
import openrsc.bot.api.Extension;
import openrsc.gamedata.WorldProfile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record OpcodeGate(@NotNull Set<Extension> extensions, boolean byPayload) {

    public static final OpcodeGate AUTHENTIC = new OpcodeGate(Set.of(), false);

    public static final OpcodeGate BY_PAYLOAD = new OpcodeGate(Set.of(), true);

    public OpcodeGate {
        extensions = extensions.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(extensions));
    }

    public static @NotNull OpcodeGate needs(@NotNull Extension first, @NotNull Extension... rest) {
        return new OpcodeGate(EnumSet.of(first, rest), false);
    }

    public @Nullable Extension missingOn(@NotNull WorldProfile world) {
        return Extension.missing(extensions, world);
    }
}
