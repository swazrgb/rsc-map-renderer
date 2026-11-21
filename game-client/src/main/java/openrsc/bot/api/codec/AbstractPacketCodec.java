package openrsc.bot.api.codec;

import io.netty.buffer.ByteBuf;
import openrsc.bot.api.Packet;
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
import openrsc.bot.api.packets.CertSwapPacket;
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
import org.jetbrains.annotations.NotNull;

public abstract class AbstractPacketCodec<T> implements PacketCodec {

    protected abstract @NotNull T begin();

    protected abstract @NotNull ByteBuf finish(@NotNull T sink);

    @Override
    public @NotNull ByteBuf frame(@NotNull Packet packet) {
        T out = begin();
        write(packet, out);
        return finish(out);
    }

    protected void write(@NotNull Packet packet, @NotNull T out) {
        switch (packet) {
            case WalkToPointPacket p -> walkToPoint(p, out);
            case WalkToEntityPacket p -> walkToEntity(p, out);
            case AtObjectPacket p -> atObject(p, out);
            case AtObject2Packet p -> atObject2(p, out);
            case AtWallObjectPacket p -> atWallObject(p, out);
            case AtWallObject2Packet p -> atWallObject2(p, out);
            case TalkToNpcPacket p -> talkToNpc(p, out);
            case NpcCommand1Packet p -> npcCommand1(p, out);
            case NpcCommand2Packet p -> npcCommand2(p, out);
            case QuickTradePacket p -> quickTrade(p, out);
            case AttackNpcPacket p -> attackNpc(p, out);
            case AttackPlayerPacket p -> attackPlayer(p, out);
            case PlayerFollowPacket p -> playerFollow(p, out);
            case PlayerDuelPacket p -> playerDuel(p, out);
            case ItemCommandPacket p -> itemCommand(p, out);
            case WornItemCommandPacket p -> wornItemCommand(p, out);
            case ItemEquipPacket p -> itemEquip(p, out);
            case ItemUnequipPacket p -> itemUnequip(p, out);
            case ItemUnequipFromEquipmentPacket p -> itemUnequipFromEquipment(p, out);
            case ItemRemoveToBankPacket p -> itemRemoveToBank(p, out);
            case DropPacket p -> drop(p, out);
            case PickupGroundItemPacket p -> pickupGroundItem(p, out);
            case UseItemOnItemPacket p -> useItemOnItem(p, out);
            case UseItemOnNpcPacket p -> useItemOnNpc(p, out);
            case UseItemOnPlayerPacket p -> useItemOnPlayer(p, out);
            case UseItemOnObjectPacket p -> useItemOnObject(p, out);
            case UseItemOnBoundaryPacket p -> useItemOnBoundary(p, out);
            case UseWornItemOnBoundaryPacket p -> useWornItemOnBoundary(p, out);
            case UseItemOnGroundItemPacket p -> useItemOnGroundItem(p, out);
            case CastOnSelfPacket p -> castOnSelf(p, out);
            case CastOnNpcPacket p -> castOnNpc(p, out);
            case CastOnPlayerPacket p -> castOnPlayer(p, out);
            case CastOnSceneryPacket p -> castOnScenery(p, out);
            case CastOnGroundItemPacket p -> castOnGroundItem(p, out);
            case CastOnInventoryItemPacket p -> castOnInventoryItem(p, out);
            case PrayerActivatedPacket p -> prayerActivated(p, out);
            case PrayerDeactivatedPacket p -> prayerDeactivated(p, out);
            case CombatStylePacket p -> combatStyle(p, out);
            case GameSettingPacket p -> gameSetting(p, out);
            case PrivacySettingsPacket p -> privacySettings(p, out);
            case DepositPacket p -> deposit(p, out);
            case WithdrawPacket p -> withdraw(p, out);
            case BankClosePacket p -> bankClose(p, out);
            case DepositAllPacket p -> depositAll(p, out);
            case BankSwapPacket p -> bankSwap(p, out);
            case BankInsertPacket p -> bankInsert(p, out);
            case DepositAllWornPacket p -> depositAllWorn(p, out);
            case BankPresetSavePacket p -> bankPresetSave(p, out);
            case BankPresetLoadPacket p -> bankPresetLoad(p, out);
            case ShopBuyPacket p -> shopBuy(p, out);
            case ShopSellPacket p -> shopSell(p, out);
            case ShopClosePacket p -> shopClose(p, out);
            case TradeRequestPacket p -> tradeRequest(p, out);
            case TradeOfferPacket p -> tradeOffer(p, out);
            case TradeAcceptFirstPacket p -> tradeAcceptFirst(p, out);
            case TradeConfirmPacket p -> tradeConfirm(p, out);
            case TradeDeclinePacket p -> tradeDecline(p, out);
            case DuelOfferPacket p -> duelOffer(p, out);
            case DuelRulesPacket p -> duelRules(p, out);
            case DuelAcceptFirstPacket p -> duelAcceptFirst(p, out);
            case DuelConfirmPacket p -> duelConfirm(p, out);
            case DuelDeclinePacket p -> duelDecline(p, out);
            case DialogueReplyPacket p -> dialogueReply(p, out);
            case ChatPacket p -> chat(p, out);
            case CommandPacket p -> command(p, out);
            case PrivateMessagePacket p -> privateMessage(p, out);
            case AddFriendPacket p -> addFriend(p, out);
            case RemoveFriendPacket p -> removeFriend(p, out);
            case AppearanceUpdatePacket p -> appearanceUpdate(p, out);
            case SkipTutorialPacket p -> skipTutorial(p, out);
            case SleepWordPacket p -> sleepWord(p, out);
            case LogoutPacket p -> logout(p, out);
            case CancelBatchPacket p -> cancelBatch(p, out);
            case CertSwapPacket p -> certSwap(p, out);
            case AuctionBuyPacket p -> auctionBuy(p, out);
            case AuctionCreatePacket p -> auctionCreate(p, out);
            case AuctionCancelPacket p -> auctionCancel(p, out);
            case AuctionRefreshPacket p -> auctionRefresh(p, out);
            case AuctionClosePacket p -> auctionClose(p, out);
            case AuctionOpenPacket p -> auctionOpen(p, out);
            case AuctionCollectPacket p -> auctionCollect(p, out);
            default -> unknown(packet, out);
        }
    }

    protected void unknown(@NotNull Packet packet, @NotNull T out) {
        throw new UnsupportedPacketException(packet, this);
    }

    protected abstract void walkToPoint(@NotNull WalkToPointPacket p, @NotNull T out);

    protected abstract void walkToEntity(@NotNull WalkToEntityPacket p, @NotNull T out);

    protected abstract void atObject(@NotNull AtObjectPacket p, @NotNull T out);

    protected abstract void atObject2(@NotNull AtObject2Packet p, @NotNull T out);

    protected abstract void atWallObject(@NotNull AtWallObjectPacket p, @NotNull T out);

    protected abstract void atWallObject2(@NotNull AtWallObject2Packet p, @NotNull T out);

    protected abstract void talkToNpc(@NotNull TalkToNpcPacket p, @NotNull T out);

    protected abstract void npcCommand1(@NotNull NpcCommand1Packet p, @NotNull T out);

    protected abstract void npcCommand2(@NotNull NpcCommand2Packet p, @NotNull T out);

    protected abstract void quickTrade(@NotNull QuickTradePacket p, @NotNull T out);

    protected abstract void attackNpc(@NotNull AttackNpcPacket p, @NotNull T out);

    protected abstract void attackPlayer(@NotNull AttackPlayerPacket p, @NotNull T out);

    protected abstract void playerFollow(@NotNull PlayerFollowPacket p, @NotNull T out);

    protected abstract void playerDuel(@NotNull PlayerDuelPacket p, @NotNull T out);

    protected abstract void itemCommand(@NotNull ItemCommandPacket p, @NotNull T out);

    protected abstract void wornItemCommand(@NotNull WornItemCommandPacket p, @NotNull T out);

    protected abstract void itemEquip(@NotNull ItemEquipPacket p, @NotNull T out);

    protected abstract void itemUnequip(@NotNull ItemUnequipPacket p, @NotNull T out);

    protected abstract void itemUnequipFromEquipment(@NotNull ItemUnequipFromEquipmentPacket p, @NotNull T out);

    protected abstract void itemRemoveToBank(@NotNull ItemRemoveToBankPacket p, @NotNull T out);

    protected abstract void drop(@NotNull DropPacket p, @NotNull T out);

    protected abstract void pickupGroundItem(@NotNull PickupGroundItemPacket p, @NotNull T out);

    protected abstract void useItemOnItem(@NotNull UseItemOnItemPacket p, @NotNull T out);

    protected abstract void useItemOnNpc(@NotNull UseItemOnNpcPacket p, @NotNull T out);

    protected abstract void useItemOnPlayer(@NotNull UseItemOnPlayerPacket p, @NotNull T out);

    protected abstract void useItemOnObject(@NotNull UseItemOnObjectPacket p, @NotNull T out);

    protected abstract void useItemOnBoundary(@NotNull UseItemOnBoundaryPacket p, @NotNull T out);

    protected abstract void useWornItemOnBoundary(@NotNull UseWornItemOnBoundaryPacket p, @NotNull T out);

    protected abstract void useItemOnGroundItem(@NotNull UseItemOnGroundItemPacket p, @NotNull T out);

    protected abstract void castOnSelf(@NotNull CastOnSelfPacket p, @NotNull T out);

    protected abstract void castOnNpc(@NotNull CastOnNpcPacket p, @NotNull T out);

    protected abstract void castOnPlayer(@NotNull CastOnPlayerPacket p, @NotNull T out);

    protected abstract void castOnScenery(@NotNull CastOnSceneryPacket p, @NotNull T out);

    protected abstract void castOnGroundItem(@NotNull CastOnGroundItemPacket p, @NotNull T out);

    protected abstract void castOnInventoryItem(@NotNull CastOnInventoryItemPacket p, @NotNull T out);

    protected abstract void prayerActivated(@NotNull PrayerActivatedPacket p, @NotNull T out);

    protected abstract void prayerDeactivated(@NotNull PrayerDeactivatedPacket p, @NotNull T out);

    protected abstract void combatStyle(@NotNull CombatStylePacket p, @NotNull T out);

    protected abstract void gameSetting(@NotNull GameSettingPacket p, @NotNull T out);

    protected abstract void privacySettings(@NotNull PrivacySettingsPacket p, @NotNull T out);

    protected abstract void deposit(@NotNull DepositPacket p, @NotNull T out);

    protected abstract void withdraw(@NotNull WithdrawPacket p, @NotNull T out);

    protected abstract void bankClose(@NotNull BankClosePacket p, @NotNull T out);

    protected abstract void depositAll(@NotNull DepositAllPacket p, @NotNull T out);

    protected abstract void bankSwap(@NotNull BankSwapPacket p, @NotNull T out);

    protected abstract void bankInsert(@NotNull BankInsertPacket p, @NotNull T out);

    protected abstract void depositAllWorn(@NotNull DepositAllWornPacket p, @NotNull T out);

    protected abstract void bankPresetSave(@NotNull BankPresetSavePacket p, @NotNull T out);

    protected abstract void bankPresetLoad(@NotNull BankPresetLoadPacket p, @NotNull T out);

    protected abstract void shopBuy(@NotNull ShopBuyPacket p, @NotNull T out);

    protected abstract void shopSell(@NotNull ShopSellPacket p, @NotNull T out);

    protected abstract void shopClose(@NotNull ShopClosePacket p, @NotNull T out);

    protected abstract void tradeRequest(@NotNull TradeRequestPacket p, @NotNull T out);

    protected abstract void tradeOffer(@NotNull TradeOfferPacket p, @NotNull T out);

    protected abstract void tradeAcceptFirst(@NotNull TradeAcceptFirstPacket p, @NotNull T out);

    protected abstract void tradeConfirm(@NotNull TradeConfirmPacket p, @NotNull T out);

    protected abstract void tradeDecline(@NotNull TradeDeclinePacket p, @NotNull T out);

    protected abstract void duelOffer(@NotNull DuelOfferPacket p, @NotNull T out);

    protected abstract void duelRules(@NotNull DuelRulesPacket p, @NotNull T out);

    protected abstract void duelAcceptFirst(@NotNull DuelAcceptFirstPacket p, @NotNull T out);

    protected abstract void duelConfirm(@NotNull DuelConfirmPacket p, @NotNull T out);

    protected abstract void duelDecline(@NotNull DuelDeclinePacket p, @NotNull T out);

    protected abstract void dialogueReply(@NotNull DialogueReplyPacket p, @NotNull T out);

    protected abstract void chat(@NotNull ChatPacket p, @NotNull T out);

    protected abstract void command(@NotNull CommandPacket p, @NotNull T out);

    protected abstract void privateMessage(@NotNull PrivateMessagePacket p, @NotNull T out);

    protected abstract void addFriend(@NotNull AddFriendPacket p, @NotNull T out);

    protected abstract void removeFriend(@NotNull RemoveFriendPacket p, @NotNull T out);

    protected abstract void appearanceUpdate(@NotNull AppearanceUpdatePacket p, @NotNull T out);

    protected abstract void skipTutorial(@NotNull SkipTutorialPacket p, @NotNull T out);

    protected abstract void sleepWord(@NotNull SleepWordPacket p, @NotNull T out);

    protected abstract void logout(@NotNull LogoutPacket p, @NotNull T out);

    protected abstract void cancelBatch(@NotNull CancelBatchPacket p, @NotNull T out);

    protected abstract void certSwap(@NotNull CertSwapPacket p, @NotNull T out);

    protected abstract void auctionBuy(@NotNull AuctionBuyPacket p, @NotNull T out);

    protected abstract void auctionCreate(@NotNull AuctionCreatePacket p, @NotNull T out);

    protected abstract void auctionCancel(@NotNull AuctionCancelPacket p, @NotNull T out);

    protected abstract void auctionRefresh(@NotNull AuctionRefreshPacket p, @NotNull T out);

    protected abstract void auctionClose(@NotNull AuctionClosePacket p, @NotNull T out);

    protected abstract void auctionOpen(@NotNull AuctionOpenPacket p, @NotNull T out);

    protected abstract void auctionCollect(@NotNull AuctionCollectPacket p, @NotNull T out);

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
