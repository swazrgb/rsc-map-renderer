package openrsc.bot.api.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.function.Supplier;
import openrsc.bot.api.packets.AddFriendPacket;
import openrsc.bot.api.packets.AppearanceUpdatePacket;
import openrsc.bot.api.packets.AtObject2Packet;
import openrsc.bot.api.packets.AtObjectPacket;
import openrsc.bot.api.packets.AtWallObject2Packet;
import openrsc.bot.api.packets.AtWallObjectPacket;
import openrsc.bot.api.packets.AttackNpcPacket;
import openrsc.bot.api.packets.AttackPlayerPacket;
import openrsc.bot.api.packets.AuctionBuyPacket;
import openrsc.bot.api.packets.AuctionCancelPacket;
import openrsc.bot.api.packets.AuctionClosePacket;
import openrsc.bot.api.packets.AuctionCollectPacket;
import openrsc.bot.api.packets.AuctionCreatePacket;
import openrsc.bot.api.packets.AuctionOpenPacket;
import openrsc.bot.api.packets.AuctionOptionPacket;
import openrsc.bot.api.packets.AuctionRefreshPacket;
import openrsc.bot.api.packets.BankClosePacket;
import openrsc.bot.api.packets.BankInsertPacket;
import openrsc.bot.api.packets.BankPresetLoadPacket;
import openrsc.bot.api.packets.BankPresetSavePacket;
import openrsc.bot.api.packets.BankSwapPacket;
import openrsc.bot.api.packets.CancelBatchPacket;
import openrsc.bot.api.packets.CastOnGroundItemPacket;
import openrsc.bot.api.packets.CastOnInventoryItemPacket;
import openrsc.bot.api.packets.CastOnNpcPacket;
import openrsc.bot.api.packets.CastOnPlayerPacket;
import openrsc.bot.api.packets.CastOnSceneryPacket;
import openrsc.bot.api.packets.CastOnSelfPacket;
import openrsc.bot.api.packets.ChatPacket;
import openrsc.bot.api.packets.CombatStylePacket;
import openrsc.bot.api.packets.CommandPacket;
import openrsc.bot.api.packets.DepositAllPacket;
import openrsc.bot.api.packets.DepositAllWornPacket;
import openrsc.bot.api.packets.DepositPacket;
import openrsc.bot.api.packets.DialogueReplyPacket;
import openrsc.bot.api.packets.DropPacket;
import openrsc.bot.api.packets.DuelAcceptFirstPacket;
import openrsc.bot.api.packets.DuelConfirmPacket;
import openrsc.bot.api.packets.DuelDeclinePacket;
import openrsc.bot.api.packets.DuelOfferPacket;
import openrsc.bot.api.packets.DuelRulesPacket;
import openrsc.bot.api.packets.GameSettingPacket;
import openrsc.bot.api.packets.InterfaceOption;
import openrsc.bot.api.packets.ItemCommandPacket;
import openrsc.bot.api.packets.ItemEquipPacket;
import openrsc.bot.api.packets.ItemRemoveToBankPacket;
import openrsc.bot.api.packets.ItemUnequipFromEquipmentPacket;
import openrsc.bot.api.packets.ItemUnequipPacket;
import openrsc.bot.api.packets.LogoutPacket;
import openrsc.bot.api.packets.NpcCommand1Packet;
import openrsc.bot.api.packets.NpcCommand2Packet;
import openrsc.bot.api.packets.PickupGroundItemPacket;
import openrsc.bot.api.packets.PlayerDuelPacket;
import openrsc.bot.api.packets.PlayerFollowPacket;
import openrsc.bot.api.packets.PrayerActivatedPacket;
import openrsc.bot.api.packets.PrayerDeactivatedPacket;
import openrsc.bot.api.packets.PrivacySettingsPacket;
import openrsc.bot.api.packets.PrivateMessagePacket;
import openrsc.bot.api.packets.QuickTradePacket;
import openrsc.bot.api.packets.RemoveFriendPacket;
import openrsc.bot.api.packets.ShopBuyPacket;
import openrsc.bot.api.packets.ShopClosePacket;
import openrsc.bot.api.packets.ShopSellPacket;
import openrsc.bot.api.packets.SkipTutorialPacket;
import openrsc.bot.api.packets.SleepWordPacket;
import openrsc.bot.api.packets.TalkToNpcPacket;
import openrsc.bot.api.packets.TradeAcceptFirstPacket;
import openrsc.bot.api.packets.TradeConfirmPacket;
import openrsc.bot.api.packets.TradeDeclinePacket;
import openrsc.bot.api.packets.TradeOfferPacket;
import openrsc.bot.api.packets.TradeRequestPacket;
import openrsc.bot.api.packets.UseItemOnBoundaryPacket;
import openrsc.bot.api.packets.UseItemOnGroundItemPacket;
import openrsc.bot.api.packets.UseItemOnItemPacket;
import openrsc.bot.api.packets.UseItemOnNpcPacket;
import openrsc.bot.api.packets.UseItemOnObjectPacket;
import openrsc.bot.api.packets.UseItemOnPlayerPacket;
import openrsc.bot.api.packets.UseWornItemOnBoundaryPacket;
import openrsc.bot.api.packets.WalkToEntityPacket;
import openrsc.bot.api.packets.WalkToPointPacket;
import openrsc.bot.api.packets.WithdrawPacket;
import openrsc.bot.api.packets.WornItemCommandPacket;
import openrsc.bot.core.codec.ChatCipher;
import openrsc.bot.core.codec.RscPackets;
import openrsc.bot.core.world.Opcode;
import openrsc.gamedata.geometry.Point;
import org.jetbrains.annotations.NotNull;

public class OpenRscCodec extends AbstractPacketCodec<ByteBuf> {

    public static final int MAX_ROUTE_TILES = 26;

    private final Supplier<WireLayout> layout;

    public OpenRscCodec() {
        this(() -> WireLayout.AUTHENTIC);
    }

    public OpenRscCodec(@NotNull Supplier<WireLayout> layout) {
        this.layout = layout;
    }

    protected @NotNull WireLayout layout() {
        return layout.get();
    }

    @Override
    protected @NotNull ByteBuf begin() {
        ByteBuf out = Unpooled.buffer(64);
        out.writeShort(0);
        return out;
    }

    @Override
    protected @NotNull ByteBuf finish(@NotNull ByteBuf out) {
        out.setShort(0, out.writerIndex() - 2);
        return out;
    }

    protected void smart(@NotNull ByteBuf out, int v) {
        RscPackets.writeSmart08_16(out, v);
    }

    protected void str(@NotNull ByteBuf out, @NotNull String s) {
        RscPackets.writeNlString(out, s);
    }

    protected void chatText(@NotNull ByteBuf out, @NotNull String s) {
        smart(out, s.length());
        out.writeBytes(ChatCipher.encipher(s));
    }

    protected void opcode(@NotNull ByteBuf out, @NotNull Opcode opcode) {
        out.writeByte(opcode.wire());
    }

    protected void route(@NotNull ByteBuf out, @NotNull List<Point> waypoints) {
        Point start = waypoints.getFirst();
        out.writeShort(start.x());
        out.writeShort(start.y());

        int tiles = Math.min(waypoints.size(), MAX_ROUTE_TILES);
        for (int i = 1; i < tiles; i++) {
            Point step = waypoints.get(i);
            out.writeByte(step.x() - start.x());
            out.writeByte(step.y() - start.y());
        }
    }

    @Override
    protected void walkToPoint(@NotNull WalkToPointPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_WALK_TO_POINT);
        route(out, p.waypoints());
    }

    @Override
    protected void walkToEntity(@NotNull WalkToEntityPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_WALK_TO_ENTITY);
        route(out, p.waypoints());
    }

    @Override
    protected void atObject(@NotNull AtObjectPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_AT_OBJECT);
        out.writeShort(p.x());
        out.writeShort(p.y());
    }

    @Override
    protected void atObject2(@NotNull AtObject2Packet p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_AT_OBJECT2);
        out.writeShort(p.x());
        out.writeShort(p.y());
    }

    @Override
    protected void atWallObject(@NotNull AtWallObjectPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_AT_WALL_OBJECT);
        out.writeShort(p.x());
        out.writeShort(p.y());
        out.writeByte(p.direction());
    }

    @Override
    protected void atWallObject2(@NotNull AtWallObject2Packet p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_AT_WALL_OBJECT2);
        out.writeShort(p.x());
        out.writeShort(p.y());
        out.writeByte(p.direction());
    }

    @Override
    protected void talkToNpc(@NotNull TalkToNpcPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_TALK_TO_NPC);
        out.writeShort(p.npcServerIndex());
    }

    @Override
    protected void npcCommand1(@NotNull NpcCommand1Packet p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_NPC_COMMAND1);
        out.writeShort(p.npcServerIndex());
    }

    @Override
    protected void npcCommand2(@NotNull NpcCommand2Packet p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_NPC_COMMAND2);
        out.writeShort(p.npcServerIndex());
    }

    @Override
    protected void quickTrade(@NotNull QuickTradePacket p, @NotNull ByteBuf out) {
        opcode(out, p.command() == 2 ? Opcode.OPCODE_NPC_COMMAND2 : Opcode.OPCODE_NPC_COMMAND1);
        out.writeShort(p.npcServerIndex());
    }

    @Override
    protected void attackNpc(@NotNull AttackNpcPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_ATTACK_NPC);
        out.writeShort(p.npcServerIndex());
    }

    @Override
    protected void attackPlayer(@NotNull AttackPlayerPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_ATTACK_PLAYER);
        out.writeShort(p.playerServerIndex());
    }

    @Override
    protected void playerFollow(@NotNull PlayerFollowPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_PLAYER_FOLLOW);
        out.writeShort(p.playerServerIndex());
    }

    @Override
    protected void playerDuel(@NotNull PlayerDuelPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_PLAYER_DUEL);
        out.writeShort(p.playerServerIndex());
    }

    @Override
    protected void itemCommand(@NotNull ItemCommandPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_ITEM_COMMAND);
        out.writeShort(p.slot());
        out.writeInt(1);
        out.writeByte(0);
    }

    @Override
    protected void wornItemCommand(@NotNull WornItemCommandPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_ITEM_COMMAND);
        out.writeShort(-1);
        out.writeInt(1);
        out.writeShort(p.itemId());
        out.writeByte(0);
    }

    @Override
    protected void itemEquip(@NotNull ItemEquipPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_ITEM_EQUIP);
        out.writeShort(p.slot());
    }

    @Override
    protected void itemUnequip(@NotNull ItemUnequipPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_ITEM_UNEQUIP);
        out.writeShort(p.slot());
    }

    @Override
    protected void itemUnequipFromEquipment(@NotNull ItemUnequipFromEquipmentPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_ITEM_UNEQUIP_FROM_EQUIPMENT);
        out.writeByte(equipmentTabIndex(p.wieldPosition()));
    }

    @Override
    protected void itemRemoveToBank(@NotNull ItemRemoveToBankPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_ITEM_REMOVE_TO_BANK);
        out.writeByte(equipmentTabIndex(p.wieldPosition()));
    }

    protected static int equipmentTabIndex(int wieldPosition) {
        return switch (wieldPosition) {
            case 0, 1, 2, 3, 4 -> wieldPosition;
            case 5, 6, 7 -> wieldPosition - 5;
            case 8, 9, 10, 11, 12, 13 -> wieldPosition - 3;
            default -> throw new UnframeablePacketException("no equipment slot " + wieldPosition);
        };
    }

    @Override
    protected void drop(@NotNull DropPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_DROP);
        out.writeShort(p.slot());
        if (layout().dropX()) {
            out.writeInt(p.amount() > 1 ? Integer.MAX_VALUE : p.amount());
        } else {
            out.writeShort(Math.min(p.amount(), Short.MAX_VALUE));
        }
    }

    @Override
    protected void pickupGroundItem(@NotNull PickupGroundItemPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_PICKUP_GROUND_ITEM);
        out.writeShort(p.x());
        out.writeShort(p.y());
        out.writeShort(p.itemId());
    }

    @Override
    protected void useItemOnItem(@NotNull UseItemOnItemPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_USE_ITEM_ON_ITEM);
        out.writeShort(p.slot());
        out.writeShort(p.targetSlot());
    }

    @Override
    protected void useItemOnNpc(@NotNull UseItemOnNpcPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_USE_ITEM_ON_NPC);
        out.writeShort(p.npcServerIndex());
        out.writeShort(p.slot());
    }

    @Override
    protected void useItemOnPlayer(@NotNull UseItemOnPlayerPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_USE_ITEM_ON_PLAYER);
        out.writeShort(p.playerServerIndex());
        out.writeShort(p.slot());
    }

    @Override
    protected void useItemOnObject(@NotNull UseItemOnObjectPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_USE_ITEM_ON_OBJECT);
        out.writeShort(p.x());
        out.writeShort(p.y());
        out.writeShort(p.slot());
    }

    @Override
    protected void useItemOnBoundary(@NotNull UseItemOnBoundaryPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_USE_ITEM_ON_BOUNDARY);
        out.writeShort(p.x());
        out.writeShort(p.y());
        out.writeByte(p.direction());
        out.writeShort(p.slot());
    }

    @Override
    protected void useWornItemOnBoundary(@NotNull UseWornItemOnBoundaryPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_USE_ITEM_ON_BOUNDARY);
        out.writeShort(p.x());
        out.writeShort(p.y());
        out.writeByte(p.direction());
        out.writeShort(-1);
        out.writeShort(p.itemId());
    }

    @Override
    protected void useItemOnGroundItem(@NotNull UseItemOnGroundItemPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_USE_ITEM_ON_GROUND_ITEM);
        out.writeShort(p.x());
        out.writeShort(p.y());
        out.writeShort(p.slot());
        out.writeShort(p.groundItemId());
    }

    @Override
    protected void castOnSelf(@NotNull CastOnSelfPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_CAST_ON_SELF);
        out.writeShort(p.spellId());
    }

    @Override
    protected void castOnNpc(@NotNull CastOnNpcPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_CAST_ON_NPC);
        out.writeShort(p.spellId());
        out.writeShort(p.npcServerIndex());
    }

    @Override
    protected void castOnPlayer(@NotNull CastOnPlayerPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_CAST_ON_PLAYER);
        out.writeShort(p.spellId());
        out.writeShort(p.playerServerIndex());
    }

    @Override
    protected void castOnScenery(@NotNull CastOnSceneryPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_CAST_ON_SCENERY);
        out.writeShort(p.spellId());
        out.writeShort(p.x());
        out.writeShort(p.y());
    }

    @Override
    protected void castOnGroundItem(@NotNull CastOnGroundItemPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_CAST_ON_GROUND_ITEM);
        out.writeShort(p.spellId());
        out.writeShort(p.x());
        out.writeShort(p.y());
        out.writeShort(p.itemId());
    }

    @Override
    protected void castOnInventoryItem(@NotNull CastOnInventoryItemPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_CAST_ON_INVENTORY_ITEM);
        out.writeShort(p.spellId());
        out.writeShort(p.slot());
    }

    @Override
    protected void prayerActivated(@NotNull PrayerActivatedPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_PRAYER_ACTIVATED);
        out.writeByte(p.prayerId());
    }

    @Override
    protected void prayerDeactivated(@NotNull PrayerDeactivatedPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_PRAYER_DEACTIVATED);
        out.writeByte(p.prayerId());
    }

    @Override
    protected void combatStyle(@NotNull CombatStylePacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_COMBAT_STYLE);
        out.writeByte(p.style());
    }

    @Override
    protected void gameSetting(@NotNull GameSettingPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_GAME_SETTINGS_CHANGED);
        out.writeByte(p.index());
        out.writeByte(p.value());
    }

    @Override
    protected void privacySettings(@NotNull PrivacySettingsPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_PRIVACY_SETTINGS_CHANGED);
        out.writeByte(p.blockChat());
        out.writeByte(p.blockPrivate());
        out.writeByte(p.blockTrade());
        out.writeByte(p.blockDuel());
    }

    @Override
    protected void deposit(@NotNull DepositPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_DEPOSIT);
        out.writeShort(p.itemId());
        out.writeInt(p.amount());
    }

    @Override
    protected void withdraw(@NotNull WithdrawPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_WITHDRAW);
        out.writeShort(p.itemId());
        out.writeInt(p.amount());
        if (layout().bankNotes()) {
            out.writeByte(p.noted() ? 1 : 0);
        } else if (p.noted()) {
            throw new UnframeablePacketException("a noted withdraw needs want_bank_notes: " + p);
        }
    }

    @Override
    protected void bankClose(@NotNull BankClosePacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_BANK_CLOSE);
    }

    @Override
    protected void depositAll(@NotNull DepositAllPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_BANK_DEPOSIT_ALL_FROM_INVENTORY);
    }

    @Override
    protected void depositAllWorn(@NotNull DepositAllWornPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_BANK_DEPOSIT_ALL_FROM_EQUIPMENT);
    }

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/parsers/impl/PayloadCustomParser.java#L884-L890
    @Override
    protected void bankSwap(@NotNull BankSwapPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_INTERFACE_OPTION);
        out.writeByte(InterfaceOption.BANK_SWAP.id());
        out.writeInt(p.slot());
        out.writeInt(p.to());
    }

    @Override
    protected void bankInsert(@NotNull BankInsertPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_INTERFACE_OPTION);
        out.writeByte(InterfaceOption.BANK_INSERT.id());
        out.writeInt(p.slot());
        out.writeInt(p.to());
    }

    @Override
    protected void bankPresetSave(@NotNull BankPresetSavePacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_BANK_SAVE_PRESET);
        out.writeShort(p.presetSlot());
    }

    @Override
    protected void bankPresetLoad(@NotNull BankPresetLoadPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_BANK_LOAD_PRESET);
        out.writeShort(p.presetSlot());
    }

    @Override
    protected void shopBuy(@NotNull ShopBuyPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_SHOP_BUY);
        out.writeShort(p.catalogId());
        out.writeShort(p.shopAmount());
        out.writeShort(p.amount());
    }

    @Override
    protected void shopSell(@NotNull ShopSellPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_SHOP_SELL);
        out.writeShort(p.catalogId());
        out.writeShort(p.shopAmount());
        out.writeShort(p.amount());
    }

    @Override
    protected void shopClose(@NotNull ShopClosePacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_SHOP_CLOSE);
    }

    @Override
    protected void tradeRequest(@NotNull TradeRequestPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_TRADE_REQUEST);
        out.writeShort(p.playerServerIndex());
    }

    @Override
    protected void tradeOffer(@NotNull TradeOfferPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_TRADE_OFFER);
        out.writeByte(p.offers().size());
        for (TradeOfferPacket.Offer offer : p.offers()) {
            out.writeShort(offer.itemId());
            out.writeInt(offer.amount());
            out.writeShort(offer.noted() ? 1 : 0);
        }
    }

    @Override
    protected void tradeAcceptFirst(@NotNull TradeAcceptFirstPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_TRADE_ACCEPT_FIRST);
    }

    @Override
    protected void tradeConfirm(@NotNull TradeConfirmPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_TRADE_CONFIRM);
    }

    @Override
    protected void tradeDecline(@NotNull TradeDeclinePacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_TRADE_DECLINE);
    }

    @Override
    protected void duelOffer(@NotNull DuelOfferPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_DUEL_OFFER);
        out.writeByte(p.offers().size());
        for (TradeOfferPacket.Offer offer : p.offers()) {
            out.writeShort(offer.itemId());
            out.writeInt(offer.amount());
            out.writeShort(offer.noted() ? 1 : 0);
        }
    }

    @Override
    protected void duelRules(@NotNull DuelRulesPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_DUEL_RULES);
        out.writeByte(p.noRetreat() ? 1 : 0);
        out.writeByte(p.noMagic() ? 1 : 0);
        out.writeByte(p.noPrayer() ? 1 : 0);
        out.writeByte(p.noWeapons() ? 1 : 0);
    }

    @Override
    protected void duelAcceptFirst(@NotNull DuelAcceptFirstPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_DUEL_ACCEPT_FIRST);
    }

    @Override
    protected void duelConfirm(@NotNull DuelConfirmPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_DUEL_CONFIRM);
    }

    @Override
    protected void duelDecline(@NotNull DuelDeclinePacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_DUEL_DECLINE);
    }

    @Override
    protected void dialogueReply(@NotNull DialogueReplyPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_DIALOGUE_REPLY);
        out.writeByte(p.index());
    }

    @Override
    protected void chat(@NotNull ChatPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_CHAT);
        chatText(out, p.text());
    }

    @Override
    protected void command(@NotNull CommandPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_COMMAND);
        str(out, p.command());
    }

    @Override
    protected void privateMessage(@NotNull PrivateMessagePacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_SEND_PRIVATE_MESSAGE);
        str(out, p.recipient());
        chatText(out, p.text());
    }

    @Override
    protected void addFriend(@NotNull AddFriendPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_ADD_FRIEND);
        str(out, p.name());
    }

    @Override
    protected void removeFriend(@NotNull RemoveFriendPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_REMOVE_FRIEND);
        str(out, p.name());
    }

    static final int REGULAR_ACCOUNT = 0;

    static final int WORLD_XP_RATE = 0;

    @Override
    protected void appearanceUpdate(@NotNull AppearanceUpdatePacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_APPEARANCE_UPDATE);
        out.writeByte(p.headRestrictions());
        out.writeByte(p.headType());
        out.writeByte(p.bodyType());
        out.writeByte(2);
        out.writeByte(p.hairColour());
        out.writeByte(p.topColour());
        out.writeByte(p.trouserColour());
        out.writeByte(p.skinColour());
        out.writeByte(REGULAR_ACCOUNT);
        out.writeByte(WORLD_XP_RATE);
    }

    @Override
    protected void skipTutorial(@NotNull SkipTutorialPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_SKIP_TUTORIAL);
    }

    @Override
    protected void sleepWord(@NotNull SleepWordPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_SLEEPWORD);
        str(out, p.word());
    }

    @Override
    protected void logout(@NotNull LogoutPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_LOGOUT);
    }

    @Override
    protected void cancelBatch(@NotNull CancelBatchPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_INTERFACE_OPTION);
        out.writeByte(InterfaceOption.CANCEL_BATCH.id());
    }

    private void auctionOption(@NotNull AuctionOptionPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_INTERFACE_OPTION);
        out.writeByte(InterfaceOption.AUCTION.id());
        out.writeByte(p.option());
    }

    @Override
    protected void auctionBuy(@NotNull AuctionBuyPacket p, @NotNull ByteBuf out) {
        auctionOption(p, out);
        out.writeInt(p.auctionId());
        out.writeInt(p.amount());
    }

    @Override
    protected void auctionCreate(@NotNull AuctionCreatePacket p, @NotNull ByteBuf out) {
        auctionOption(p, out);
        out.writeInt(p.itemId());
        out.writeInt(p.amount());
        out.writeInt(p.totalPrice());
    }

    @Override
    protected void auctionCancel(@NotNull AuctionCancelPacket p, @NotNull ByteBuf out) {
        auctionOption(p, out);
        out.writeInt(p.auctionId());
    }

    @Override
    protected void auctionRefresh(@NotNull AuctionRefreshPacket p, @NotNull ByteBuf out) {
        auctionOption(p, out);
    }

    @Override
    protected void auctionClose(@NotNull AuctionClosePacket p, @NotNull ByteBuf out) {
        auctionOption(p, out);
    }

    @Override
    protected void auctionOpen(@NotNull AuctionOpenPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_NPC_COMMAND1);
        out.writeShort(p.npcServerIndex());
    }

    @Override
    protected void auctionCollect(@NotNull AuctionCollectPacket p, @NotNull ByteBuf out) {
        opcode(out, Opcode.OPCODE_NPC_COMMAND2);
        out.writeShort(p.npcServerIndex());
    }
}
