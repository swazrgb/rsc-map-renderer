package openrsc.gamedata.data;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import openrsc.bot.api.Nullable;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Point;

/**
 * Named places: banks, shops, grind spots and prayer altars, each at one tile {@code (x, z)} a player
 * can stand on beside the counter, chest, altar or NPCs it is about.
 *
 * <p>A bank or shop names the NPCs that serve it by server index, so a banker of the same kind in
 * view at another counter is not mistaken for its staff; see {@link #servedBy}.
 */
public enum Location {
    BANK_AL_KHARID(Kind.BANK, "Al Kharid bank", 89, 695, BankArea.AL_KHARID, new int[] {1670, 1673, 1681, 1683}),
    BANK_VARROCK_WEST(Kind.BANK, "Varrock west bank", 151, 505, BankArea.VARROCK_WEST, new int[] {589, 590, 592}),
    BANK_VARROCK_EAST(Kind.BANK, "Varrock east bank", 102, 513, BankArea.VARROCK_EAST, new int[] {568, 570, 571}),
    BANK_FALADOR_WEST(
            Kind.BANK, "Falador west bank", 332, 553, BankArea.FALADOR_WEST, new int[] {865, 866, 868, 869, 870}),
    BANK_FALADOR_EAST(
            Kind.BANK, "Falador east bank", 283, 567, BankArea.FALADOR_EAST, new int[] {845, 846, 847, 848, 849}),
    BANK_EDGEVILLE(Kind.BANK, "Edgeville bank", 219, 449, BankArea.EDGEVILLE, new int[] {380, 382, 383}),
    BANK_DRAYNOR(Kind.BANK, "Draynor bank", 220, 635, BankArea.DRAYNOR, new int[] {1408, 1409, 1410}),
    BANK_SEERS(Kind.BANK, "Seers' Village bank", 501, 450, BankArea.SEERS_VILLAGE, new int[] {465, 466, 467, 468, 469}),
    BANK_CATHERBY(Kind.BANK, "Catherby bank", 440, 492, BankArea.CATHERBY, new int[] {653, 654, 656}),
    BANK_ARDOUGNE_NORTH(Kind.BANK, "East Ardougne north bank", 581, 574, BankArea.EAST_ARDOUGNE_NORTH, new int[] {
        957, 958, 960, 1185
    }),
    BANK_ARDOUGNE_SOUTH(Kind.BANK, "East Ardougne south bank", 552, 613, BankArea.EAST_ARDOUGNE_SOUTH, new int[] {
        1175, 1176, 1178, 1180
    }),
    BANK_YANILLE(Kind.BANK, "Yanille bank", 587, 754, BankArea.YANILLE, new int[] {2016, 2017, 2018}),
    BANK_MAGE_ARENA(Kind.BANK, "Mage Arena bank (Gundai)", 454, 3378, BankArea.MAGE_ARENA, new int[] {2944}),
    BANK_ZANARIS(Kind.BANK, "Zanaris (Lost City) bank", 173, 3524, BankArea.ZANARIS, new int[] {3319, 3320, 3322}),
    BANK_TREE_GNOME_STRONGHOLD(
            Kind.BANK, "Tree Gnome Stronghold bank", 714, 1453, BankArea.TREE_GNOME_STRONGHOLD, new int[] {2397, 2398}),
    BANK_GRAND_TREE(
            Kind.BANK, "Grand Tree Bank", 715, 1401, BankArea.GRAND_TREE_SECOND_FLOOR, new int[] {2361, 2362, 2369, 2370
            }),
    BANK_SHILO(Kind.BANK, "Shilo Village bank", 402, 851, BankArea.SHILO_VILLAGE, new int[] {2191, 2194, 2196}),
    BANK_LEGENDS_GUILD(
            Kind.BANK, "Legends' Guild bank", 514, 2423, BankArea.LEGENDS_GUILD, new int[] {2474, 2475, 2476}),
    BANK_SHANTAY_CHEST(Kind.BANK, "Shantay Pass bank chest", 59, 731, BankArea.SHANTAY_PASS, Point.of(58, 731)),
    BANK_FISHING_GUILD(Kind.BANK, "Fishing Guild bank chest", 604, 505, null, Point.of(605, 505)),
    BANK_CRAFTING_GUILD(Kind.BANK, "Crafting Guild bank chest", 345, 609, null, Point.of(346, 609)),
    // The chest opens only with McGrubor nearby, so he is its staff.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/alkharid/ShantayPassNpcs.java#L477
    BANK_WOODCUTTING_GUILD(
            Kind.BANK, "Woodcutting Guild bank chest (McGrubor)", 555, 455, null, Point.of(556, 455), 3703),

    GENERAL_STORE_LUMBRIDGE(Kind.GENERAL_STORE, "Lumbridge general store", 134, 644, new int[] {1358}),
    GENERAL_STORE_VARROCK(Kind.GENERAL_STORE, "Varrock general store", 127, 517, new int[] {574}),
    GENERAL_STORE_FALADOR(Kind.GENERAL_STORE, "Falador general store", 320, 536, new int[] {853}),
    GENERAL_STORE_AL_KHARID(Kind.GENERAL_STORE, "Al Kharid general store", 55, 684, new int[] {1660}),
    GENERAL_STORE_RIMMINGTON(Kind.GENERAL_STORE, "Rimmington general store", 330, 663, new int[] {1440}),
    GENERAL_STORE_KARAMJA(Kind.GENERAL_STORE, "Karamja general store", 360, 716, new int[] {1708}),
    GENERAL_STORE_EDGEVILLE(Kind.GENERAL_STORE, "Edgeville general store", 225, 442, new int[] {376}),
    GENERAL_STORE_ZANARIS(Kind.GENERAL_STORE, "Zanaris (Lost City) General Store", 166, 3523, new int[] {3318, 3321}),
    GENERAL_STORE_DWARVEN_MINE(Kind.GENERAL_STORE, "Dwarven Mine General Store", 292, 3340, new int[] {2768}),
    GENERAL_STORE_ARDOUGNE(Kind.GENERAL_STORE, "East Ardougne Adventurers' Store", 581, 600, new int[] {1203, 1204}),
    GENERAL_STORE_WEST_ARDOUGNE(
            Kind.GENERAL_STORE, "Chadwell's General Store (West Ardougne)", 683, 589, new int[] {1269}),
    GENERAL_STORE_TREE_GNOME_VILLAGE(
            Kind.GENERAL_STORE, "Bolkoy's General Store (Tree Gnome Village)", 635, 1633, new int[] {2443}),
    GENERAL_STORE_FISHING_TRAWLER(
            Kind.GENERAL_STORE, "Fishing Trawler General Store (Port Khazard)", 553, 708, new int[] {1764}),
    GENERAL_STORE_JIMINUA(Kind.GENERAL_STORE, "Jiminua's Jungle Store (Shilo Village)", 459, 754, new int[] {1981}),
    GENERAL_STORE_SHILO_VILLAGE(Kind.GENERAL_STORE, "Obli's General Store (Shilo Village)", 417, 847, new int[] {2188}),
    GENERAL_STORE_VALAINE_VARROCK(Kind.GENERAL_STORE, "Valaine's House of Help (Varrock)", 150, 1498, new int[] {2399}),
    GENERAL_STORE_BANDIT_CAMP(Kind.GENERAL_STORE, "Noterazzo's General Store (Bandit Camp)", 279, 296, new int[] {242}),
    GENERAL_STORE_MAGE_ARENA(
            Kind.GENERAL_STORE, "Mage Arena Staves (Chamber Guardian)", 471, 3387, new int[] {2945, 2946}),
    GENERAL_STORE_LEGENDS_GUILD(
            Kind.GENERAL_STORE, "Fionella's General Store (Legends' Guild)", 512, 1481, new int[] {2394}),
    GENERAL_STORE_ARHEIN_CATHERBY(Kind.GENERAL_STORE, "Arhein's General Store (Catherby)", 440, 504, new int[] {657}),

    SHOP_GEM_TRADER(Kind.SHOP, "Al Kharid Gem Trader", 81, 667, new int[] {1330}),
    SHOP_LOUIE_LEGS(Kind.SHOP, "Louie's Legs (Al Kharid)", 55, 686, new int[] {1664}),
    SHOP_RANAEL_PLATESKIRT(Kind.SHOP, "Ranael's Plateskirt Shop (Al Kharid)", 54, 694, new int[] {1677}),
    SHOP_SHANTAY_PASS(Kind.SHOP, "Shantay Pass Shop (Al Kharid)", 63, 731, new int[] {1870, 1871, 1872}),
    SHOP_ZEKE_SCIMITARS(Kind.SHOP, "Zeke's Scimitar Shop (Al Kharid)", 75, 676, new int[] {1653}),
    SHOP_BAKER_ARDOUGNE(Kind.SHOP, "Bakers Stall (East Ardougne)", 545, 601, new int[] {1171}),
    SHOP_FUR_TRADER_ARDOUGNE(Kind.SHOP, "Fur Trader (East Ardougne)", 549, 585, new int[] {1156}),
    SHOP_GEM_MERCHANT(Kind.SHOP, "Gem Merchant (East Ardougne)", 551, 598, new int[] {1170}),
    SHOP_KING_LATHAS_WEAPONRY(
            Kind.SHOP, "King Lathas' Weaponry Shop (East Ardougne / Combat Training Camp)", 652, 537, new int[] {967}),
    SHOP_SILVER_MERCHANT(Kind.SHOP, "Silver Stall (Silver Merchant, East Ardougne)", 555, 595, new int[] {1162}),
    SHOP_SPICE_MERCHANT(Kind.SHOP, "Spice Merchant (East Ardougne)", 544, 589, new int[] {1160}),
    SHOP_ZENESHA_PLATE_MAIL_TOP(Kind.SHOP, "Zenesha's Plate Mail Top Shop (East Ardougne)", 556, 603, new int[] {1173}),
    SHOP_PEKSA_HELMETS(Kind.SHOP, "Peksa's Helmet Shop (Barbarian Village)", 236, 510, new int[] {610}),
    SHOP_ALFONSE_THE_WAITER(Kind.SHOP, "Alfonse the Waiter (Shrimp and Parrot, Brimhaven)", 451, 684, new int[] {1734}),
    SHOP_DAVON_AMULET_STORE(Kind.SHOP, "Davon's Amulet Store (Brimhaven)", 444, 706, new int[] {1749}),
    SHOP_CANDLEMAKER(Kind.SHOP, "Candle Shop (Catherby)", 449, 493, new int[] {655}),
    SHOP_HICKTON_ARCHERY(Kind.SHOP, "Hickton's Archery Store (Catherby)", 427, 489, new int[] {650}),
    SHOP_DOMMIK_CRAFTING(Kind.SHOP, "Dommik's Crafting Equipment Shop (Al Kharid)", 51, 675, new int[] {1652}),
    SHOP_ROMMIK_CRAFTING(Kind.SHOP, "Rommik's Crafting Equipment Shop (Rimmington)", 330, 667, new int[] {1446}),
    SHOP_DROGO_MINING_STORE(Kind.SHOP, "Drogo's Mining Store (Dwarven Mine)", 269, 3330, new int[] {2752}),
    SHOP_NURMOF_PICKAXE(Kind.SHOP, "Nurmof's Pickaxe Shop (Dwarven Mine)", 292, 3330, new int[] {2767}),
    SHOP_OZIACH_BODY_ARMOUR(Kind.SHOP, "Oziach's Body Armour Shop (Edgeville)", 244, 443, new int[] {394}),
    SHOP_FRINCOS_VIALS(Kind.SHOP, "Frincos' Vial Shop (Entrana)", 435, 564, new int[] {899}),
    SHOP_CASSIE_SHIELDS(Kind.SHOP, "Cassie's Shields (Falador)", 309, 533, new int[] {852}),
    SHOP_FLYNN_MACES(Kind.SHOP, "Flynn's mace shop (Falador)", 327, 538, new int[] {856}),
    SHOP_HERQUIN_GEMS(Kind.SHOP, "Herquin's Jewel Shop (Falador)", 332, 567, new int[] {874}),
    SHOP_WAYNE_CHAINS(Kind.SHOP, "Wayne's Chains (Falador)", 303, 578, new int[] {1103}),
    SHOP_BLURBERRY_BARMAN(
            Kind.SHOP, "Blurberry's cocktail bar (Tree Gnome Stronghold)", 690, 1403, new int[] {2371, 2373}),
    SHOP_GNOME_WAITER(Kind.SHOP, "Gnome Waiter (Grand Tree restaurant)", 714, 1388, new int[] {2326, 2341}),
    SHOP_GULLUCK_AND_SONS(Kind.SHOP, "Gulluck and Sons (Grand Tree)", 691, 1379, new int[] {2322}),
    SHOP_HECKEL_FUNCH(Kind.SHOP, "Heckel Funch's Grocery Store (Grand Tree)", 685, 1402, new int[] {2366}),
    SHOP_HUDO_GLENFAD_GROCERIES(
            Kind.SHOP, "Hudo Glenfad's Grocery Store (Tree Gnome Stronghold)", 715, 1380, new int[] {2324}),
    SHOP_ROMETTI_FASHION_OUTLET(
            Kind.SHOP, "Rometti's fashion outlet (Tree Gnome Stronghold)", 717, 1409, new int[] {2375}),
    SHOP_GRUDS_HERBLAW_STALL(Kind.SHOP, "Grud's Herblaw Stall (Gu'Tanoth)", 644, 791, new int[] {2150}),
    SHOP_OGRE_TRADER_GENSTORE(Kind.SHOP, "Ogre Trader General Store (Gu'Tanoth)", 652, 791, new int[] {2151}),
    SHOP_FISHING_GUILD(Kind.SHOP, "Fishing Guild Shop (Hemenster)", 600, 517, new int[] {695}),
    SHOP_ZAMBO_RUM(Kind.SHOP, "Zambo's fine wines and spirits (Karamja, Musa Point)", 346, 713, new int[] {1706}),
    SHOP_IRKSOL_RUBY_RING(Kind.SHOP, "Irksol's Ruby Ring Shop (Zanaris)", 107, 3541, new int[] {3312}),
    SHOP_JAKUT_DRAGON_SWORD(Kind.SHOP, "Dragon Sword Shop (Lost City)", 113, 3546, new int[] {3315}),
    SHOP_BETTY_MAGIC_EMPORIUM(Kind.SHOP, "Betty's Magic Emporium (Port Sarim)", 272, 632, new int[] {1420}),
    SHOP_BRIAN_BATTLE_AXES(Kind.SHOP, "Brian's Great Axes (Port Sarim)", 264, 629, new int[] {1418}),
    SHOP_GRUM_GOLD(Kind.SHOP, "Grum's Gold (Jewellers), Port Sarim", 279, 632, new int[] {1421}),
    SHOP_FERNAHEI_FISHING(Kind.SHOP, "Fernahei's Fishing Shop (Shilo Village)", 395, 837, new int[] {2182}),
    SHOP_HELEMOS_HEROES_GUILD(Kind.SHOP, "Helemos' Hero's Guild Shop (Taverley)", 374, 437, new int[] {432}),
    SHOP_JATIX_HERBLAW(Kind.SHOP, "Jatix's Olde Herbalist (Taverley)", 368, 506, new int[] {642}),
    SHOP_AUBURY_RUNES(Kind.SHOP, "Aubury's Rune Shop (Varrock)", 102, 523, new int[] {581}),
    SHOP_HORVIK_ARMOUR(Kind.SHOP, "Horvik's Armour Shop (Varrock)", 116, 502, new int[] {562}),
    SHOP_LOWE_ARCHERY(Kind.SHOP, "Lowe's Archery Store (Varrock)", 115, 515, new int[] {572}),
    SHOP_SCAVVO_RUNE_STORE(Kind.SHOP, "Scavvo's Rune Store (Varrock)", 150, 1505, new int[] {2400}),
    SHOP_TAILOR_FANCY_DRESS(Kind.SHOP, "Tailor's fancy dress (Varrock)", 84, 525, new int[] {556}),
    SHOP_TEA_SELLER(Kind.SHOP, "Tea stall (Varrock)", 90, 519, new int[] {553}),
    SHOP_THESSALIA_CLOTHES(Kind.SHOP, "Thessalia's Clothes Shop (Varrock)", 137, 516, new int[] {575}),
    SHOP_ZAFF_STAFFS(Kind.SHOP, "Zaff's staff shop (Varrock)", 140, 504, new int[] {564}),
    SHOP_FAT_TONY_PIZZA_BASE(Kind.SHOP, "Fat Tony's Pizza Base Shop (Bandit Camp)", 270, 291, new int[] {240}),
    SHOP_FRENITA_COOKING(Kind.SHOP, "Frenita's Cooking Shop (Yanille)", 615, 750, new int[] {2015}),
    SHOP_MAGIC_STORE_OWNER(Kind.SHOP, "Magic Guild Store (Yanille)", 600, 1703, new int[] {2447}),
    SHOP_LUNDAIL_MAGE_ARENA_RUNES(Kind.SHOP, "Lundail's Mage Arena Rune Shop", 440, 3376, new int[] {2943}),
    SHOP_NULODION_CANNON_PARTS(
            Kind.SHOP, "Nulodion's Cannon Parts (Dwarven Mine / Dwarf Cannon site)", 275, 493, new int[] {618}),
    SHOP_SIEGFRIED_ERKLE(Kind.SHOP, "Legends' Guild Shop (Siegfried Erkle)", 510, 2425, new int[] {2477}),
    SHOP_BOBS_AXES(Kind.SHOP, "Bob's Axes (Lumbridge)", 124, 670, new int[] {1376}),
    SHOP_VARROCK_SWORDS(Kind.SHOP, "Varrock Swords", 137, 527, new int[] {583, 586}),
    SHOP_GAIUS_TAVERLEY(Kind.SHOP, "Gaius' Two-Handed Swords (Taverley)", 379, 502, new int[] {640}),
    SHOP_GERRANTS_FISHING(Kind.SHOP, "Gerrant's Fishing Supplies (Port Sarim)", 279, 648, new int[] {1427}),
    SHOP_HARRYS_FISHING(Kind.SHOP, "Harry's Fishing Shack (Catherby)", 418, 489, new int[] {648}),
    SHOP_BARMAID_FALADOR(Kind.SHOP, "Falador Barmaid (Rising Sun Inn)", 319, 549, new int[] {864}),
    SHOP_CAPTAIN_ROVIN(Kind.SHOP, "Captain Rovin", 138, 1406, new int[] {2347}),
    SHOP_HEAD_CHEF(Kind.SHOP, "Head Chef", 178, 484, new int[] {588}),
    SHOP_THURGO(Kind.SHOP, "Thurgo", 291, 713, new int[] {1702}),
    SHOP_SIR_VYVIN(Kind.SHOP, "Sir Vyvin", 319, 2456, new int[] {2478}),
    SHOP_BROTHER_JERED(Kind.SHOP, "Brother Jered", 250, 1410, new int[] {2355}),
    SHOP_KAQEMEEX(Kind.SHOP, "Kaqemeex", 362, 459, new int[] {435}),
    SHOP_MASTER_CRAFTER(Kind.SHOP, "Master Crafter", 348, 606, new int[] {1119}),
    SHOP_ACHETTIES(Kind.SHOP, "Achetties", 372, 443, new int[] {433}),
    SHOP_MASTER_FISHER(Kind.SHOP, "Master Fisher", 586, 526, new int[] {702}),
    SHOP_DOCTOR_ORBON(Kind.SHOP, "Doctor Orbon", 578, 591, new int[] {1200}),
    SHOP_WIZARD_FRUMSCONE(Kind.SHOP, "Wizard Frumscone", 606, 3585, new int[] {3461}),
    SHOP_GUNNJORN(Kind.SHOP, "Gunnjorn", 489, 555, new int[] {914}),
    SHOP_LILY(Kind.SHOP, "Lily", 184, 600, new int[] {3653}),
    SHOP_ROBIN_BANKS(Kind.SHOP, "Robin Banks", 615, 3496, new int[] {3697}),
    SHOP_WYDINS_GROCERY(Kind.SHOP, "Wydin's Grocery (Port Sarim)", 275, 659, new int[] {1431}),

    GRIND_FALADOR_GUARDS(Kind.GRIND_SPOT, "Falador guard ring", 315, 540, 12, NpcId.GUARD),
    GRIND_LUMBRIDGE_GOBLINS(Kind.GRIND_SPOT, "Lumbridge goblin field", 115, 632, 8, NpcId.GOBLIN_LVL7),

    // The Yanille dungeon's chaos altar is left out: recharging there drops the player through a trapdoor.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/prayer/Prayer.java#L37
    ALTAR_VARROCK_NORTH(Kind.ALTAR, "Varrock north altar", 98, 474),
    ALTAR_VARROCK_SOUTH(Kind.ALTAR, "Varrock south altar", 96, 529),
    ALTAR_EDGEVILLE(Kind.ALTAR, "Edgeville altar", 260, 458),
    ALTAR_TAVERLEY_CIRCLE(Kind.ALTAR, "Taverley stone circle (Altar of Guthix)", 361, 461),
    ALTAR_TAVERLEY_NORTH(Kind.ALTAR, "Taverley north altar", 333, 435),
    ALTAR_LUMBRIDGE(Kind.ALTAR, "Lumbridge altar", 109, 667),
    ALTAR_RIMMINGTON(Kind.ALTAR, "Rimmington altar", 303, 689),
    ALTAR_ENTRANA(Kind.ALTAR, "Entrana altar", 411, 561),
    ALTAR_SEERS(Kind.ALTAR, "Seers' Village altar", 523, 476),
    ALTAR_ARDOUGNE_NORTH(Kind.ALTAR, "East Ardougne north altar", 576, 590),
    ALTAR_ARDOUGNE_WEST(Kind.ALTAR, "West Ardougne altar", 698, 649),
    ALTAR_PORT_KHAZARD(Kind.ALTAR, "Port Khazard altar", 587, 666),
    ALTAR_COMBAT_CAMP(Kind.ALTAR, "Combat Training Camp altar", 637, 575),
    ALTAR_BANDIT_CAMP(Kind.ALTAR, "Bandit Camp altar (wilderness 38)", 332, 200),
    ALTAR_WILDERNESS_CHAOS_TEMPLE(Kind.ALTAR, "Wilderness chaos temple altar (wilderness 11)", 115, 365),
    // The one altar that recharges prayer to 2 above its level.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/prayer/Prayer.java#L20
    ALTAR_MONASTERY_UPSTAIRS(Kind.ALTAR, "Edgeville monastery upstairs (monks' altar, +2)", 263, 1408),
    ALTAR_UPSTAIRS_136_1400(Kind.ALTAR, "upstairs altar (136,1400)", 136, 1400),
    ALTAR_UPSTAIRS_368_1380(Kind.ALTAR, "upstairs altar (368,1380)", 368, 1380),
    ALTAR_UPSTAIRS_473_1389(Kind.ALTAR, "upstairs altar (473,1389)", 473, 1389),
    ALTAR_UPSTAIRS_271_2331(Kind.ALTAR, "upstairs altar (271,2331)", 271, 2331),
    ALTAR_UNDERGROUND_724_3450(Kind.ALTAR, "underground altar (724,3450)", 724, 3450),
    ALTAR_UNDERGROUND_767_3441(Kind.ALTAR, "underground altar (767,3441)", 767, 3441);

    public enum Kind {
        /** Bankers, or a bank chest. */
        BANK,
        /**
         * A shop that buys any item, not only its own stock.
         *
         * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Shop.java#L276">Shop.shouldStock</a>
         */
        GENERAL_STORE,
        /** A shop that buys only what it stocks. */
        SHOP,
        /** NPCs to kill, anywhere within {@link #radius} of the tile. */
        GRIND_SPOT,
        /**
         * A prayer altar, which recharges prayer; the server does not regenerate it over time.
         *
         * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/rsc/impl/StatRestorationEvent.java#L50">StatRestorationEvent.run</a>
         */
        ALTAR
    }

    public static final List<Location> BANKS = ofKind(Kind.BANK);
    public static final List<Location> ALTARS = ofKind(Kind.ALTAR);

    private static final Set<Location> RENDEZVOUS_UNSAFE = EnumSet.of(
            BANK_MAGE_ARENA, BANK_FISHING_GUILD, BANK_CRAFTING_GUILD, BANK_WOODCUTTING_GUILD, BANK_FALADOR_WEST);

    /**
     * The banks fit to meet another player at: {@link #BANKS} without the Mage Arena bank in the deep
     * wilderness, the guild chests behind skill-gated doors, and Falador west.
     */
    public static final List<Location> RENDEZVOUS_BANKS =
            BANKS.stream().filter(b -> !RENDEZVOUS_UNSAFE.contains(b)).toList();

    public final Kind kind;
    public final String label;
    public final int x, z;
    /** For a grind spot, how far from the tile, in king moves, its NPCs are hunted; 0 otherwise. */
    public final int radius;

    @SuppressWarnings("ImmutableEnumChecker")
    private final int[] npcIds;

    @SuppressWarnings("ImmutableEnumChecker")
    private final int[] npcIndexes;

    private final @Nullable BankArea bankArea;

    @SuppressWarnings("ImmutableEnumChecker")
    private final @Nullable Point chest;

    Location(Kind kind, String label, int x, int z, NpcId... npcs) {
        this(kind, label, x, z, 0, null, null, new int[0], npcs);
    }

    Location(Kind kind, String label, int x, int z, int radius, NpcId... npcs) {
        this(kind, label, x, z, radius, null, null, new int[0], npcs);
    }

    Location(Kind kind, String label, int x, int z, int[] npcIndexes) {
        this(kind, label, x, z, 0, null, null, npcIndexes);
    }

    Location(Kind kind, String label, int x, int z, BankArea bankArea, int... npcIndexes) {
        this(kind, label, x, z, 0, bankArea, null, npcIndexes);
    }

    Location(Kind kind, String label, int x, int z, @Nullable BankArea bankArea, Point chest, int... npcIndexes) {
        this(kind, label, x, z, 0, bankArea, chest, npcIndexes);
    }

    Location(
            Kind kind,
            String label,
            int x,
            int z,
            int radius,
            @Nullable BankArea bankArea,
            @Nullable Point chest,
            int[] npcIndexes,
            NpcId... npcs) {
        this.kind = kind;
        this.label = label;
        this.x = x;
        this.z = z;
        this.radius = radius;
        this.bankArea = bankArea;
        this.chest = chest;
        this.npcIndexes = npcIndexes.clone();
        this.npcIds = new int[npcs.length];
        for (int i = 0; i < npcs.length; i++) {
            this.npcIds[i] = npcs[i].id();
        }
    }

    public String label() {
        return label;
    }

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    public Point point() {
        return new Point(x, z);
    }

    /** The tile grown by {@link #radius}: a grind spot's hunting ground, else the tile alone. */
    public Area area() {
        return point().grow(radius);
    }

    /** For a bank, the server's area for it; {@code null} for other kinds and the guild chests. */
    public @Nullable BankArea bankArea() {
        return bankArea;
    }

    /**
     * The bank chest this bank opens at, or {@code null} for a bank of bankers. Every bank chest needs
     * a members' world.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/alkharid/ShantayPassNpcs.java#L458">ShantayPassNpcs.onOpLoc</a>
     */
    public @Nullable Point chest() {
        return chest;
    }

    /** Whether this bank is used only through {@code ::b}, standing in its area, never through its bankers. */
    public boolean quickBankOnly() {
        return this == BANK_FALADOR_WEST;
    }

    /**
     * Whether this place exists on {@code data}'s world: a chest bank where its chest is placed, a
     * {@link #quickBankOnly} bank where {@code ::b} is on, and the shops of custom content where that
     * content is.
     */
    public boolean existsIn(ServerData data) {
        if (this == SHOP_LILY) {
            return data.world().has(openrsc.gamedata.WorldProfile.Feature.HARVESTING);
        }
        if (this == SHOP_ROBIN_BANKS) {
            return data.world().customSprites();
        }
        if (quickBankOnly()) {
            return data.world().rules().quickBank();
        }
        if (chest == null) {
            return true;
        }
        for (openrsc.gamedata.defs.SceneryLocs.Loc l : data.sceneryLocs()) {
            if (l.id() == SceneryId.BANK_CHEST.id()
                    && l.pos().x() == chest.x()
                    && l.pos().y() == chest.y()) {
                return true;
            }
        }
        return false;
    }

    /** Whether the tile is inside this bank's area at {@code based_map_data = mapData}; false for a non-bank. */
    public boolean inBank(int x, int z, int mapData) {
        return bankArea != null && bankArea.contains(x, z, mapData);
    }

    /** For a grind spot, the NPC ids hunted there; empty for the other kinds, which have {@link #staffIds}. */
    public int[] npcIds() {
        return npcIds;
    }

    /** The distinct NPC ids of this place's staff, as the spawn table has them at its server indexes. */
    public int[] staffIds(ServerData data) {
        int[] out = new int[npcIndexes.length];
        int n = 0;
        for (int index : npcIndexes) {
            ServerData.NpcSpawn spawn = data.npcSpawnByServerIndex(index);
            if (spawn == null) {
                continue;
            }
            boolean seen = false;
            for (int i = 0; i < n; i++) {
                if (out[i] == spawn.id()) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                out[n++] = spawn.id();
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** The bank whose tile is exactly {@code (x, z)}, or {@code null}. */
    public static Location bankAt(int x, int z) {
        for (Location l : BANKS) {
            if (l.x == x && l.z == z) {
                return l;
            }
        }
        return null;
    }

    /** Whether no NPC serves here: a bank chest without staff, a grind spot or an altar. */
    public boolean isScenery() {
        return npcIndexes.length == 0;
    }

    /** Whether the NPC of static spawn {@code spawn} is this place's staff; false for null. */
    public boolean servedBy(@Nullable ServerData.NpcSpawn spawn) {
        if (spawn == null) {
            return false;
        }
        for (int i : npcIndexes) {
            if (i == spawn.serverIndex()) {
                return true;
            }
        }
        return false;
    }

    /** The server indexes of the NPCs that serve here, as a copy; empty when none do. */
    public int[] npcIndexes() {
        return npcIndexes.clone();
    }

    public static List<Location> ofKind(Kind kind) {
        List<Location> out = new ArrayList<>();
        for (Location l : values()) {
            if (l.kind == kind) {
                out.add(l);
            }
        }
        return out;
    }
}
