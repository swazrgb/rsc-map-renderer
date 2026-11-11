package openrsc.livemap.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The bank, trade or duel screen a bot has open, as the map draws it; the fields of the closed ones are left out. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WindowsDto(
        @Nullable Bank bank,
        @Nullable Trade trade,
        @Nullable Duel duel) {

    /**
     * The open bank, and the moves the world has in it: {@code notes} when a withdrawal may come as bank notes,
     * {@code wornDeposits} when a worn item may be deposited as a carried one, which only one the inventory keeps may;
     * {@code depositAll} and {@code depositWorn} put the whole pack or everything worn in at once, {@code unequip} a worn
     * item straight in, and {@code rearrange} moves the bank's stacks. {@code presets} holds one entry per preset it
     * loads and saves, in slot order; null for a world without them.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Bank.java#L533-L539">Bank.depositItemFromInventory</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/BankHandler.java#L92-L172">BankHandler.process</a>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Bank(
            boolean notes,
            boolean wornDeposits,
            boolean depositAll,
            boolean depositWorn,
            boolean unequip,
            boolean rearrange,
            @Nullable List<Preset> presets) {}

    /**
     * Bank preset {@code slot}: what loading it leaves in the pack and on the body, both empty for one never saved; not
     * {@code known}, and both empty, until the server has sent it.
     */
    public record Preset(int slot, boolean known, List<Stack> pack, List<Stack> worn) {}

    /** A stack on offer; {@code noted} for bank notes. An item that does not stack is offered one to a stack. */
    public record Stack(int id, int amount, boolean noted) {}

    /**
     * An open trade with {@code partner}: the two offers, and both players' accepts. On the confirm screen the offers
     * are those about to change hands, accepted on the first.
     */
    public record Trade(
            String partner,
            List<Stack> mine,
            List<Stack> theirs,
            boolean accepted,
            boolean theyAccepted,
            boolean confirm) {}

    /**
     * An open duel with {@code opponent}: the two stakes, both players' accepts, and its rules, each forbidding what it
     * names. On the confirm screen the stakes are those at risk.
     */
    public record Duel(
            String opponent,
            List<Stack> mine,
            List<Stack> theirs,
            boolean accepted,
            boolean theyAccepted,
            boolean confirm,
            boolean noRetreat,
            boolean noMagic,
            boolean noPrayer,
            boolean noWeapons) {}
}
