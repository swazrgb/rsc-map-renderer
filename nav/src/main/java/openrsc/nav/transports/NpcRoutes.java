package openrsc.nav.transports;

import static openrsc.nav.transports.spec.NpcTalk.at;
import static openrsc.nav.transports.spec.NpcTalk.command;
import static openrsc.nav.transports.spec.NpcTalk.commandFerry;
import static openrsc.nav.transports.spec.NpcTalk.ferry;
import static openrsc.nav.transports.spec.NpcTalk.gatedFerry;
import static openrsc.nav.transports.spec.NpcTalk.npcs;
import static openrsc.nav.transports.spec.NpcTalk.searchedFerry;
import static openrsc.nav.transports.spec.NpcTalk.showItem;
import static openrsc.nav.transports.spec.NpcTalk.spendItem;
import static openrsc.nav.transports.spec.NpcTalk.talk;
import static openrsc.nav.transports.spec.Rows.notQuestAtLeast;
import static openrsc.nav.transports.spec.Rows.onlyIn;
import static openrsc.nav.transports.spec.Rows.questAt;
import static openrsc.nav.transports.spec.Rows.questAtLeast;
import static openrsc.nav.transports.spec.Rows.questComplete;
import static openrsc.nav.transports.spec.WorldGate.feature;
import static openrsc.nav.transports.spec.WorldGate.rule;

import java.util.ArrayList;
import java.util.List;
import openrsc.bot.api.quest.QuestId;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.ServerRules;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.NpcId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/**
 * Conversations and NPC commands that move the player: gliders, charter ships, carts, ferrymen,
 * guides and guards.
 */
public enum NpcRoutes implements TransportDef {
    /**
     * At stages 8 and 9 the Grand Tree's pilot offers only Karamja, and crashes in its south at
     * (425,764).
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/grandtree/GnomeGlider.java#L139">GnomeGlider.onTalkNpc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/grandtree/GnomeGlider.java#L160">GnomeGlider.onTalkNpc</a>
     */
    GLIDER_ESCAPE_KARAMJA_STAGE_8(
            glider(NpcId.GNOME_PILOT_GRANDTREE, 414, 2995, 425, 764, grandTreeStage(8), "karamja")),
    GLIDER_ESCAPE_KARAMJA_STAGE_9(
            glider(NpcId.GNOME_PILOT_GRANDTREE, 414, 2995, 425, 764, grandTreeStage(9), "karamja")),
    GLIDER_TO_KARAMJA(glider(NpcId.GNOME_PILOT_GRANDTREE, 414, 2995, 389, 753, grandTree(), "karamja")),
    /**
     * Varrock's glider crashed on landing, so no glider flies back from there.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/grandtree/GnomeGlider.java#L34">GnomeGlider.onTalkNpc</a>
     */
    GLIDER_TO_VARROCK(glider(NpcId.GNOME_PILOT_GRANDTREE, 414, 2995, 58, 504, grandTree(), "varrock")),
    GLIDER_TO_AL_KHARID(glider(NpcId.GNOME_PILOT_GRANDTREE, 414, 2995, 88, 664, grandTree(), "al kharid")),
    GLIDER_TO_WHITE_WOLF_MOUNTAIN(glider(NpcId.GNOME_PILOT_GRANDTREE, 414, 2995, 400, 461, grandTree(), "white wolf")),
    GLIDER_KARAMJA_TO_GRAND_TREE(glider(NpcId.GNOME_PILOT_KARAMJA, 389, 753, 414, 2995, grandTree(), "ok then")),
    GLIDER_AL_KHARID_TO_GRAND_TREE(glider(NpcId.GNOME_PILOT_ALKHARID, 88, 662, 414, 2995, grandTree(), "ok then")),
    GLIDER_WHITE_WOLF_MOUNTAIN_TO_GRAND_TREE(
            glider(NpcId.GNOME_PILOT_WHITEMOUNTAIN, 401, 461, 414, 2995, grandTree(), "ok then")),

    SHIP_PORT_SARIM_TO_KARAMJA(ferry(
            npcs(NpcId.CAPTAIN_TOBIAS.id(), NpcId.SEAMAN_LORRIS.id(), NpcId.SEAMAN_THRESNOR.id()),
            at(268, 648),
            324,
            713,
            ItemId.COINS.id(),
            NpcRoutes.FARE,
            NpcRoutes.SHIP_COST,
            new String[] {"yes please"})),
    /**
     * Customs confiscate any Karamja rum, and then do not let the player board.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/karamja/BoatFromKaramja.java#L43">BoatFromKaramja.talkToOfficer</a>
     */
    SHIP_KARAMJA_TO_PORT_SARIM(searchedFerry(
            npcs(NpcId.CUSTOMS_OFFICER.id()),
            at(325, 713),
            269,
            648,
            ItemId.COINS.id(),
            NpcRoutes.FARE,
            ItemId.KARAMJA_RUM.id(),
            NpcRoutes.SHIP_COST,
            new String[] {"can i board"},
            new String[] {"search away"},
            new String[] {"ok"})),

    SHIP_BRIMHAVEN_TO_ARDOUGNE(searchedFerry(
            npcs(NpcId.CUSTOMS_OFFICIAL.id()),
            at(467, 649),
            538,
            617,
            ItemId.COINS.id(),
            NpcRoutes.FARE,
            ItemId.KARAMJA_RUM.id(),
            NpcRoutes.SHIP_COST,
            new String[] {"can i board"},
            new String[] {"search away"},
            new String[] {"ok"})),
    SHIP_PORT_KHAZARD_TO_BRIMHAVEN(ferry(
            npcs(NpcId.DOCKY.id()),
            at(540, 703),
            467,
            647,
            ItemId.COINS.id(),
            NpcRoutes.FARE,
            NpcRoutes.SHIP_COST,
            new String[] {"where are you travelling"},
            new String[] {"ok"})),
    SHIP_ARDOUGNE_TO_BRIMHAVEN(ferry(
            npcs(NpcId.CAPTAIN_BARNABY.id()),
            at(538, 616),
            467,
            651,
            ItemId.COINS.id(),
            NpcRoutes.FARE,
            NpcRoutes.SHIP_COST,
            new String[] {"yes please"})),

    /**
     * The boatman lands the player on the world's respawn location, (120,648) on both worlds.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/tutorial/boatman.java#L26">boatman.onTalkNpc</a>
     */
    BOAT_TUTORIAL_ISLAND_TO_LUMBRIDGE(talk(
            npcs(NpcId.BOATMAN.id()),
            List.of(Point.of(226, 753), Point.of(227, 754), Point.of(230, 757)),
            120,
            648,
            Prereq.ALWAYS,
            200,
            new String[] {"ready to go"})),

    /**
     * Femi smuggles the player in for 1,000 coins, which he keeps, or free for the favour of the
     * barrel ({@code helped_femi}). The favour is not observed, so the fare is always planned; the
     * answer picks the paying option, which a player holding the favour is never offered.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/grandtree/GrandTree.java#L923">GrandTree.onTalkNpc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/grandtree/GrandTree.java#L963">GrandTree.onTalkNpc</a>
     */
    FEMIS_SMUGGLE_CART_INTO_THE_GNOME_STRONGHOLD_STAGE_10(gatedFerry(
            npcs(NpcId.FEMI.id()),
            List.of(Point.of(708, 534), Point.of(703, 532)),
            708,
            510,
            ItemId.COINS.id(),
            1000,
            questAt(QuestId.GRAND_TREE, 10),
            200,
            new String[] {"here you go"})),

    /**
     * The monk searches the player and refuses passage to anyone carrying a weapon or armour.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/portsarim/MonkOfEntrana.java#L410">MonkOfEntrana.onTalkNpc</a>
     */
    PORT_SARIM_ENTRANA_SHIP_MONK(talk(
            npcs(NpcId.MONK_OF_ENTRANA_PORTSARIM.id()),
            at(264, 660),
            418,
            570,
            Prereq.fact(Fact.notCarryingCombatGear()),
            200,
            new String[] {"ready"})),

    TEMPLE_OF_IKOV_LAVA_CROSSING_WINELDA(
            ferry(npcs(NpcId.WINELDA.id()), at(552, 3292), 557, 3290, ItemId.LIMPWURT_ROOT.id(), 20, 150)),

    // Where Holgart rows to depends on the stage and on where the player stands (ashore, Kent's
    // island included, or on the platform), never on which of his three spawns is asked. The row out
    // to the platform puts out a lit torch.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/SeaSlug.java#L345
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L329
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/SeaSlug.java#L611
    WITCHAVEN_FISHING_PLATFORM_HOLGART(talk(
            npcs(NpcId.HOLGART_LAND.id()),
            at(516, 612),
            495,
            618,
            questAtLeast(QuestId.SEA_SLUG, 3),
            150,
            new String[] {"will you take me there", "lets do it"})),
    /**
     * At stage 6 he rows back without a menu.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/SeaSlug.java#L542">SeaSlug.onTalkNpc</a>
     */
    FISHING_PLATFORM_WITCHAVEN_HOLGART(talk(
            npcs(NpcId.HOLGART_PLATFORM.id()),
            at(493, 615),
            515,
            613,
            Prereq.any(
                    questAt(QuestId.SEA_SLUG, 3),
                    questAt(QuestId.SEA_SLUG, 5),
                    questAt(QuestId.SEA_SLUG, 6),
                    questComplete(QuestId.SEA_SLUG)),
            150,
            new String[] {"go back"})),
    /**
     * At stage 4 the platform's only way off is this row to the island.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/SeaSlug.java#L460">SeaSlug.onTalkNpc</a>
     */
    FISHING_PLATFORM_KENTS_ISLAND_HOLGART(
            talk(npcs(NpcId.HOLGART_PLATFORM.id()), at(493, 615), 512, 639, questAt(QuestId.SEA_SLUG, 4), 150)),
    KENTS_ISLAND_FISHING_PLATFORM_HOLGART(talk(
            npcs(NpcId.HOLGART_ISLAND.id()),
            at(512, 638),
            495,
            618,
            questAtLeast(QuestId.SEA_SLUG, 4),
            150,
            new String[] {"will you take me there", "lets do it"})),

    /**
     * Omart's ladder crosses at stages 3 to 5, the first crossing moving the quest to stage 4; from
     * stage 6 on he will not use it again.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/BioHazard.java#L267">BioHazard.onTalkNpc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/BioHazard.java#L288">BioHazard.onTalkNpc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/BioHazard.java#L310">BioHazard.onTalkNpc</a>
     */
    OMART_ROPE_LADDER_EAST_WEST_ARDOUGNE(talk(
            npcs(NpcId.OMART.id()),
            at(624, 616),
            624,
            606,
            Prereq.any(questAt(QuestId.BIOHAZARD, 3), questAt(QuestId.BIOHAZARD, 4), questAt(QuestId.BIOHAZARD, 5)),
            150,
            new String[] {"lets do it"})),

    KILRON_ROPE_LADDER_WEST_EAST_ARDOUGNE(talk(
            npcs(NpcId.KILRON.id()), at(629, 613), 622, 611, questAtLeast(QuestId.BIOHAZARD, 4), 150, new String[] {
                "yes i do"
            })),

    ELKOY_MAZE_GUIDE_BATTLEFIELD_SIDE_GNOME_VILLAGE(talk(
            npcs(NpcId.ELKOY.id()),
            at(622, 675),
            644,
            697,
            questAtLeast(QuestId.TREE_GNOME_VILLAGE, 6),
            390,
            new String[] {"yes please"})),

    /**
     * The block lands on the delivered crate inside Handelmort's mansion at stage 2 or once the quest
     * is done, and before that beside the crate still at the RPDT depot.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/TribalTotem.java#L310">TribalTotem.inventedDialogue</a>
     */
    CROMPERTY_TELEPORT_BLOCK_HANDELMORT_MANSION(talk(
            npcs(NpcId.WIZARD_CROMPERTY.id()),
            at(545, 576),
            560,
            588,
            questAtLeast(QuestId.TRIBAL_TOTEM, 2),
            200,
            new String[] {"what have you invented"},
            new String[] {"teleported please"},
            new String[] {"teleport me"})),

    CROMPERTY_TELEPORT_BLOCK_RPDT_DEPOT(talk(
            npcs(NpcId.WIZARD_CROMPERTY.id()),
            at(545, 576),
            558,
            617,
            notQuestAtLeast(QuestId.TRIBAL_TOTEM, 2),
            200,
            new String[] {"what have you invented"},
            new String[] {"teleported please"},
            new String[] {"teleport me"})),

    /**
     * The answers get the player jailed, then refuse the fine twice, which sends them free to the Port
     * Sarim jail.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/alkharid/ShantayPassNpcs.java#L422">ShantayPassNpcs.sendToPortSarim</a>
     */
    SHANTAY_PORT_SARIM_PRISON(talk(
            npcs(NpcId.SHANTAY.id()),
            at(63, 731),
            281,
            665,
            Prereq.ALWAYS,
            910,
            new String[] {"what is this place"},
            new String[] {"definitely an outlaw"},
            new String[] {"not having my money"},
            new String[] {"do your worst"})),

    TENT_GUARD_SHOW_TECHNICAL_PLANS(showItem(
            npcs(NpcId.BEDABIN_NOMAD_GUARD.id()),
            at(170, 794),
            171,
            792,
            ItemId.TECHNICAL_PLANS.id(),
            Prereq.any(questAt(QuestId.TOURIST_TRAP, 6), questAt(QuestId.TOURIST_TRAP, 7)),
            150)),

    /**
     * Aubury stores {@code essence_entrance} 0, so the mine's exit portal brings the player back to
     * him ({@link Portals#PORTAL_RAW_ESSENCE_MINE_VARROCK}).
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/custom/quests/members/RuneMysteries.java#L353">RuneMysteries.auburyDialog</a>
     */
    AUBURY_TELEPORT_RUNE_STONE_MINE(onlyIn(
            feature(WorldProfile.Feature.RUNECRAFT),
            command(npcs(NpcId.AUBURY.id()), at(102, 523), 695, 22, questComplete(QuestId.RUNE_MYSTERIES), 40, 1))),
    /**
     * Sedridor stores {@code essence_entrance} 1, so the mine's exit portal lands in his basement
     * ({@link Portals#PORTAL_RAW_ESSENCE_MINE_WIZARDS_TOWER}).
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/custom/quests/members/RuneMysteries.java#L88">RuneMysteries.sedridorDialog</a>
     */
    SEDRIDOR_TELEPORT_RUNE_STONE_MINE(onlyIn(
            feature(WorldProfile.Feature.RUNECRAFT),
            command(npcs(NpcId.SEDRIDOR.id()), at(222, 3516), 695, 22, questComplete(QuestId.RUNE_MYSTERIES), 50, 1))),

    /**
     * Refused below 100 total level.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/varrock/Auctioneers.java#L88">Auctioneers.onOpNpc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/varrock/Auctioneers.java#L132">Auctioneers.onOpNpc</a>
     */
    AUCTION_CLERK_TELEPORT_VARROCK(onlyIn(
            feature(WorldProfile.Feature.AUCTION_NPCS),
            commandFerry(
                    npcs(NpcId.AUCTION_CLERK.id()),
                    List.of(
                            Point.of(217, 450),
                            Point.of(501, 451),
                            Point.of(101, 512),
                            Point.of(151, 501),
                            Point.of(441, 494),
                            Point.of(283, 568),
                            Point.of(331, 553),
                            Point.of(582, 574),
                            Point.of(553, 610),
                            Point.of(219, 636),
                            Point.of(90, 694),
                            Point.of(369, 715),
                            Point.of(588, 756),
                            Point.of(402, 853),
                            Point.of(713, 1450),
                            Point.of(445, 3370),
                            Point.of(175, 3527)),
                    133,
                    508,
                    ItemId.COINS.id(),
                    1000,
                    Prereq.fact(Fact.totalLevelAtLeast(100)),
                    NpcRoutes.AUCTION_TELEPORT_COST,
                    2,
                    new String[] {"yes please"}))),

    /**
     * Once the quest is done the guard is occupied, unless the world keeps locked post-quest regions
     * open.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/watchtower/WatchTowerMechanism.java#L308">WatchTowerMechanism.onUseNpc</a>
     */
    WATCHTOWER_ENCLAVE_NIGHTSHADE_AFTER_QUEST(onlyIn(
            rule(ServerRules::postQuestRegionsOpen),
            spendItem(
                    npcs(NpcId.OGRE_GUARD_CAVE_ENTRANCE.id()),
                    at(662, 788),
                    647,
                    3644,
                    ItemId.NIGHTSHADE.id(),
                    questComplete(QuestId.WATCHTOWER),
                    150))),
    ;

    /**
     * The command's duration; the 1,000 coins gate the teleport rather than price it.
     */
    private static final int AUCTION_TELEPORT_COST = 60;

    /**
     * Every charter ship's fare, in coins.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/portsarim/PortSarimSailor.java#L46">PortSarimSailor.travel</a>
     */
    private static final int FARE = 30;

    /**
     * A charter crossing's cost, well above its dialogue's duration; the fare gates it rather than pricing it.
     */
    private static final int SHIP_COST = 1000;

    /**
     * A glider leg's cost, above {@link #SHIP_COST}: a pilot talks to one player at a time and every
     * route runs through the Grand Tree's pilot, so a flight is mostly a wait.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/NpcTalkTo.java#L49">NpcTalkTo.executeInternal</a>
     */
    private static final int GLIDER_COST = 2000;

    private static TransportSpec glider(NpcId pilot, int x, int z, int destX, int destZ, Prereq gate, String answer) {
        return talk(npcs(pilot.id()), at(x, z), destX, destZ, gate, NpcRoutes.GLIDER_COST, new String[] {answer});
    }

    private static Prereq grandTree() {
        return Prereq.fact(Fact.questComplete(QuestId.GRAND_TREE.id()));
    }

    private static Prereq grandTreeStage(int stage) {
        return Prereq.fact(Fact.questEquals(QuestId.GRAND_TREE.id(), stage));
    }

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    NpcRoutes(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (NpcRoutes r : values()) {
            out.addAll(r.expand(env));
        }
        return out;
    }
}
