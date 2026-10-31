# Recipe camper: camps NPC spawns and attacks each one on the tick it respawns.
#
# Copy this file into Plutonium's scripts directory and paste the recipe the map viewer generated
# into the account file (see example-account.toml):
#
#   [script]
#   name = "bob_map_camper.py"
#
#   [script.settings]
#   recipe = '''
#   ...
#   '''
#
# Melee only. What it does is in RECIPE.md, next to the map viewer's script generator.

import time

FLOOR_HEIGHT = 944
HITS = 3
MAX_NUMBER = 2147483647
SLEEPING_BAG_ID = 1263
BANKER_IDS = [95, 224, 268, 540, 617, 792]
# The server sends us the NPCs whose straight-line distance, rounded down, is at most 15: under 16
# tiles. From within this, squared, a diagonal step cannot take an NPC out of view, so one that
# leaves view from this close died
# https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L369
# https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L409
VIEW_SQ = 212
ROUTE_REACH = 30
SKIP_TICKS = 50
BANK_TILES = 3
APPROACH_TILES = 5
LOOT_TILES = 4
# a due target this close to coming back, with the player at its spawn, is not left
DUE_SOON_TICKS = 5
# a target that may not be where it is looked for costs this many ticks more, as does a due one
# whose walk takes more than WALK_SLACK_TICKS longer than its wait
UNSURE_TICKS = 6
WALK_SLACK_TICKS = 5
# the hunted target is kept while less than this many ticks worse than the best
KEEP_TICKS = 2
WALK_DONE_TILES = 2
# deposits or withdrawals still to do this many ticks after they were sent were not taken
BANK_TICKS = 3
# The tick's objects, wall objects and ground items arrive after on_server_tick's NPC update, and
# Plutonium's event loop may run loop() between them: act this long after the hook, polling fast.
SETTLE_MS = 20
POLL_MS = 10
STEP_LOG_TICKS = 20
# a walk round the block NPCs searches the box of the player and its tile grown by this
SEARCH_TILES = 16
OP_WALK = 187
OP_ATTACK_NPC = 190
# the client's collision flags
WALL_NORTH_BIT = 1
WALL_EAST_BIT = 2
WALL_SOUTH_BIT = 4
WALL_WEST_BIT = 8
FULL_BLOCK_BITS = 16 | 32 | 64
SOURCE_SOUTH = 1
SOURCE_WEST = 2
SOURCE_NORTH = 4
SOURCE_EAST = 8
SETTING_LINES = ["world", "camp", "bank", "style", "eat", "sleep", "food", "loot", "bury", "block"]
STEP_KINDS = ["walk", "door", "gate", "obj", "wall", "cut", "usewall", "useobj"]
# Plutonium does not say how big scenery is: from its own ObjectDefs.json, each object that is
# not one tile, or that the client walks into rather than beside (type 2 or 3), as id, width,
# height, and 1 for walked into.
OBJECT_SIZES = [
    2, 2, 2, 0, 9, 4, 1, 0, 11, 1, 2, 0, 14, 2, 3, 0, 15, 2, 2, 0, 19, 2, 1, 0, 23, 2, 1, 0, 26,
    2, 2, 0, 28, 3, 3, 0, 29, 2, 1, 0, 30, 2, 2, 0, 39, 2, 2, 0, 40, 2, 2, 0, 41, 2, 3, 0, 42,
    2, 3, 0, 43, 2, 3, 0, 44, 2, 3, 0, 47, 1, 2, 0, 48, 1, 2, 0, 52, 2, 2, 0, 53, 2, 2, 0, 54,
    2, 3, 0, 56, 1, 2, 0, 57, 1, 2, 1, 58, 1, 2, 1, 59, 1, 2, 1, 60, 1, 2, 1, 63, 1, 2, 1, 64,
    1, 2, 1, 67, 1, 2, 0, 68, 2, 2, 0, 69, 2, 2, 0, 71, 1, 2, 0, 74, 1, 3, 0, 84, 1, 2, 0, 85,
    1, 2, 0, 86, 2, 2, 0, 93, 1, 2, 1, 94, 1, 2, 1, 118, 2, 2, 0, 119, 1, 2, 0, 120, 2, 2, 0,
    134, 2, 2, 0, 135, 2, 2, 0, 136, 2, 2, 0, 137, 1, 2, 1, 138, 1, 2, 1, 140, 1, 2, 0, 141, 1,
    2, 0, 142, 1, 2, 1, 144, 2, 1, 0, 151, 2, 2, 0, 152, 2, 2, 0, 155, 5, 3, 0, 156, 2, 3, 0,
    157, 5, 3, 0, 161, 5, 3, 0, 162, 2, 3, 0, 163, 5, 3, 0, 173, 2, 2, 0, 174, 1, 2, 0, 175, 1,
    2, 0, 178, 2, 2, 0, 180, 1, 2, 1, 181, 1, 2, 1, 200, 2, 1, 0, 202, 2, 2, 0, 203, 2, 2, 0,
    208, 3, 2, 0, 213, 2, 2, 0, 216, 2, 2, 0, 224, 5, 3, 0, 225, 5, 3, 0, 226, 1, 1, 1, 232, 1,
    1, 1, 233, 5, 3, 0, 234, 5, 3, 0, 235, 2, 2, 0, 238, 5, 3, 0, 239, 2, 3, 0, 240, 5, 3, 0,
    241, 5, 3, 0, 242, 2, 3, 0, 243, 5, 3, 0, 246, 2, 2, 0, 252, 1, 2, 1, 253, 1, 2, 1, 254, 1,
    2, 1, 256, 1, 2, 1, 258, 1, 2, 0, 259, 1, 2, 0, 260, 1, 2, 1, 263, 1, 2, 0, 264, 1, 2, 0,
    273, 2, 1, 0, 274, 2, 1, 0, 280, 2, 2, 0, 282, 2, 2, 0, 287, 3, 3, 0, 292, 5, 3, 0, 293, 5,
    3, 0, 296, 2, 1, 0, 299, 2, 1, 0, 302, 2, 2, 0, 305, 1, 2, 1, 306, 2, 2, 0, 307, 2, 2, 0,
    308, 2, 2, 0, 309, 2, 2, 0, 311, 1, 2, 1, 314, 2, 2, 0, 319, 1, 2, 1, 320, 5, 3, 0, 321, 5,
    3, 0, 322, 2, 2, 0, 323, 2, 2, 0, 324, 2, 2, 0, 325, 2, 2, 0, 326, 2, 2, 0, 327, 2, 2, 0,
    331, 2, 3, 0, 341, 2, 2, 0, 342, 2, 3, 0, 343, 2, 2, 0, 346, 1, 2, 1, 347, 1, 2, 1, 356, 1,
    2, 1, 357, 1, 2, 1, 358, 1, 2, 1, 359, 2, 3, 0, 360, 2, 3, 0, 366, 2, 2, 0, 369, 2, 3, 0,
    370, 2, 3, 0, 371, 1, 2, 1, 372, 1, 2, 1, 375, 2, 1, 0, 377, 2, 1, 0, 378, 2, 2, 0, 381, 1,
    2, 0, 382, 1, 2, 0, 384, 5, 3, 0, 385, 2, 3, 0, 386, 5, 3, 0, 388, 4, 1, 0, 389, 2, 1, 0,
    390, 2, 2, 0, 392, 1, 2, 1, 393, 3, 1, 0, 406, 2, 2, 0, 411, 1, 2, 1, 417, 2, 2, 0, 422, 1,
    2, 1, 434, 1, 2, 0, 435, 1, 2, 0, 436, 1, 2, 0, 440, 1, 2, 0, 441, 1, 2, 0, 442, 1, 2, 1,
    443, 1, 2, 1, 444, 2, 2, 0, 445, 1, 3, 0, 448, 2, 1, 0, 450, 1, 2, 1, 451, 1, 2, 0, 452, 1,
    2, 0, 453, 1, 2, 0, 454, 2, 2, 0, 455, 2, 2, 0, 457, 1, 2, 1, 461, 1, 2, 1, 465, 1, 2, 1,
    466, 2, 2, 0, 467, 2, 4, 0, 468, 1, 2, 1, 469, 1, 2, 0, 470, 1, 2, 0, 471, 1, 2, 1, 472, 1,
    2, 1, 480, 1, 2, 1, 486, 1, 2, 1, 491, 1, 2, 0, 494, 2, 2, 0, 497, 1, 2, 1, 499, 1, 2, 0,
    500, 1, 2, 0, 503, 2, 2, 0, 504, 1, 2, 1, 506, 1, 2, 0, 507, 1, 2, 0, 508, 1, 2, 1, 509, 1,
    2, 0, 510, 1, 2, 0, 513, 1, 2, 1, 514, 1, 2, 1, 526, 0, 0, 0, 528, 1, 0, 0, 563, 1, 2, 1,
    572, 1, 2, 1, 574, 0, 0, 0, 575, 0, 0, 0, 576, 0, 0, 0, 577, 1, 2, 1, 578, 0, 0, 0, 579, 0,
    0, 0, 580, 0, 0, 0, 581, 0, 0, 0, 582, 2, 2, 0, 583, 1, 2, 1, 591, 2, 1, 0, 593, 2, 2, 0,
    594, 2, 2, 0, 595, 2, 2, 0, 596, 2, 3, 0, 611, 1, 2, 1, 612, 1, 2, 1, 613, 2, 3, 0, 619, 1,
    2, 0, 620, 1, 2, 0, 621, 2, 3, 0, 623, 1, 2, 0, 624, 1, 2, 0, 625, 2, 1, 0, 626, 1, 2, 1,
    629, 2, 3, 0, 630, 2, 3, 0, 633, 2, 1, 0, 636, 2, 1, 0, 641, 1, 2, 0, 647, 2, 1, 0, 653, 2,
    1, 0, 660, 1, 2, 1, 662, 2, 2, 0, 663, 2, 2, 0, 664, 2, 2, 0, 665, 2, 2, 0, 666, 2, 2, 0,
    667, 2, 2, 0, 669, 2, 1, 0, 670, 2, 1, 0, 677, 2, 1, 0, 682, 2, 2, 0, 683, 2, 1, 0, 689, 2,
    2, 0, 694, 2, 2, 0, 695, 2, 2, 0, 702, 1, 2, 1, 703, 1, 2, 1, 704, 1, 2, 1, 711, 1, 2, 1,
    712, 1, 2, 1, 720, 3, 1, 0, 721, 3, 1, 0, 722, 3, 1, 0, 723, 3, 1, 0, 724, 2, 2, 0, 725, 3,
    1, 0, 726, 3, 1, 0, 727, 3, 1, 0, 728, 2, 1, 0, 767, 2, 1, 0, 768, 2, 3, 0, 769, 2, 3, 0,
    772, 2, 2, 0, 773, 2, 2, 0, 774, 1, 3, 0, 780, 5, 3, 0, 781, 5, 3, 0, 794, 1, 2, 1, 796, 2,
    1, 0, 797, 2, 1, 0, 815, 2, 1, 0, 816, 2, 1, 0, 817, 2, 1, 0, 819, 2, 2, 0, 820, 2, 2, 0,
    821, 2, 2, 0, 822, 2, 2, 0, 823, 2, 2, 0, 824, 2, 2, 0, 830, 2, 2, 0, 841, 2, 1, 0, 843, 2,
    1, 0, 844, 2, 1, 0, 845, 2, 1, 0, 846, 2, 1, 0, 847, 2, 1, 0, 867, 2, 2, 0, 875, 3, 1, 0,
    878, 1, 2, 0, 890, 2, 1, 0, 891, 1, 3, 0, 892, 1, 3, 0, 893, 1, 3, 0, 894, 1, 3, 0, 895, 1,
    3, 0, 896, 1, 3, 0, 897, 1, 3, 0, 898, 1, 3, 0, 899, 1, 3, 0, 900, 1, 3, 0, 901, 1, 3, 0,
    902, 1, 3, 0, 903, 1, 3, 0, 904, 1, 3, 0, 905, 1, 3, 0, 906, 1, 3, 0, 907, 1, 3, 0, 908, 1,
    3, 0, 909, 1, 3, 0, 910, 1, 3, 0, 916, 2, 2, 0, 918, 3, 3, 0, 926, 1, 2, 1, 931, 1, 2, 0,
    932, 1, 2, 1, 939, 2, 1, 0, 941, 1, 3, 0, 955, 3, 1, 0, 958, 1, 2, 1, 963, 3, 1, 0, 964, 3,
    1, 0, 966, 1, 2, 0, 974, 2, 2, 0, 980, 2, 2, 0, 982, 2, 2, 0, 983, 2, 1, 0, 988, 1, 2, 1,
    989, 1, 2, 1, 1004, 1, 2, 0, 1019, 1, 2, 1, 1020, 1, 2, 1, 1023, 2, 1, 0, 1025, 2, 3, 0,
    1026, 2, 2, 0, 1032, 2, 1, 0, 1033, 1, 2, 1, 1035, 1, 2, 0, 1044, 3, 1, 0, 1045, 3, 1, 0,
    1046, 2, 2, 0, 1052, 2, 2, 0, 1053, 1, 2, 0, 1068, 1, 2, 1, 1069, 1, 2, 1, 1070, 1, 2, 1,
    1074, 1, 2, 0, 1078, 1, 2, 0, 1079, 1, 2, 1, 1080, 1, 2, 1, 1081, 1, 2, 0, 1089, 1, 2, 1,
    1090, 1, 2, 0, 1095, 1, 2, 0, 1107, 2, 2, 0, 1108, 2, 2, 0, 1109, 2, 2, 0, 1110, 2, 2, 0,
    1111, 2, 2, 0, 1112, 2, 2, 0, 1113, 2, 2, 0, 1114, 2, 3, 0, 1116, 2, 2, 0, 1117, 2, 2, 0,
    1123, 2, 3, 0, 1124, 2, 3, 0, 1125, 2, 3, 0, 1126, 2, 2, 0, 1130, 2, 2, 0, 1140, 1, 2, 1,
    1141, 2, 2, 0, 1143, 2, 2, 0, 1145, 3, 1, 0, 1146, 2, 2, 0, 1149, 1, 2, 0, 1155, 2, 2, 0,
    1158, 3, 1, 0, 1159, 2, 2, 0, 1160, 1, 2, 1, 1162, 1, 2, 0, 1165, 1, 2, 1, 1166, 2, 2, 0,
    1171, 2, 2, 0, 1172, 2, 2, 0, 1173, 2, 2, 0, 1174, 2, 2, 0, 1175, 2, 2, 0, 1177, 2, 1, 0,
    1179, 5, 3, 0, 1180, 2, 3, 0, 1181, 5, 3, 0, 1182, 1, 2, 0, 1183, 2, 2, 0, 1184, 2, 2, 0,
    1185, 2, 2, 0
]
# keyed by str(id): gpython's dict takes only string keys
OBJECT_SIZE = {}
for _i in range(0, len(OBJECT_SIZES), 4):
    OBJECT_SIZE[str(OBJECT_SIZES[_i])] = OBJECT_SIZES[_i + 1:_i + 4]
# Plutonium does not say which items stack: Uranium's stackable items, from the server's
# ItemDefs.json (Uranium patches none of them).
STACKABLE = set([
    10, 11, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 46, 190, 280, 281, 380, 381, 419,
    517, 518, 519, 520, 521, 528, 529, 530, 531, 532, 533, 534, 535, 536, 574, 592, 619, 628, 629,
    630, 631, 637, 638, 639, 640, 641, 642, 643, 644, 645, 646, 647, 669, 670, 671, 672, 673, 674,
    711, 712, 713, 715, 723, 783, 784, 785, 786, 790, 796, 825, 985, 1013, 1014, 1015, 1024, 1030,
    1041, 1062, 1063, 1064, 1065, 1066, 1067, 1068, 1069, 1070, 1071, 1118, 1122, 1123, 1124, 1125,
    1126, 1127, 1182, 1254, 1270, 1271, 1272, 1273, 1274, 1275])


class Target:
    def __init__(self, line, sid, npc_id, x, z, respawn, min_x, min_z, max_x, max_z):
        self.line = line
        self.sid = sid
        self.npc_id = npc_id
        self.x = x
        self.z = z
        self.respawn = respawn
        self.min_x = min_x
        self.min_z = min_z
        self.max_x = max_x
        self.max_z = max_z
        # the tick it is due back; -1 means it is not due
        self.due = -1
        self.skip_until = -1
        # where it was, and whether it was fighting, when last in view
        self.last_x = x
        self.last_z = z
        self.fighting = False
        # the tile it was last seen on when it walked out of view; -1 for none
        self.tile_x = -1
        self.tile_z = -1
        # the ticks spent waiting within one tile of its spawn
        self.wait = 0


class Item:
    def __init__(self, line, item_id, low, target, wear):
        self.line = line
        self.id = item_id
        self.min = low
        self.target = target
        self.wear = wear


class Step:
    def __init__(self, kind, line, text, x, z):
        self.kind = kind
        self.line = line
        self.text = text
        self.x = x
        self.z = z
        self.dir = -1
        self.closed = -1
        self.cmd = 0
        self.item = -1
        self.land_x = -1
        self.land_z = -1

    def uses_item(self):
        return self.kind == "usewall" or self.kind == "useobj" or (self.kind == "cut" and self.item != 0)


class Recipe:
    def __init__(self):
        self.world = ""
        self.tick_ms = 640
        self.targets = []
        self.camp_x = -1
        self.camp_z = -1
        self.camp_min_x = -1
        self.camp_min_z = -1
        self.camp_max_x = -1
        self.camp_max_z = -1
        self.bank_x = -1
        self.bank_z = -1
        self.style = -1
        self.eat = -1
        # the fatigue in percent to sleep at; -1 for never
        self.sleep = -1
        self.food = []
        self.items = []
        self.loot = []
        self.bury = []
        self.block = []
        self.routes = {}

    def item(self, item_id):
        for it in self.items:
            if it.id == item_id:
                return it
        return None


def recipe_error(line, message):
    return ValueError("line %d: %s" % (line, message))


def number(w, line):
    ok = len(w) > 0
    for c in w:
        if c not in "0123456789":
            ok = False
    if not ok or int(w) > MAX_NUMBER:
        raise recipe_error(line, "%s is not a number" % w)
    return int(w)


def numbers(words, count, line):
    if len(words) - 1 != count:
        raise recipe_error(line, "%s takes %d numbers" % (words[0], count))
    return [number(w, line) for w in words[1:]]


def in_range(word, n, low, high, line):
    if n < low or n > high:
        raise recipe_error(line, "%s is %d to %d" % (word, low, high))


def check_box(a, line):
    # a[0..3] are minX minZ maxX maxZ
    if a[0] > a[2] or a[1] > a[3]:
        raise recipe_error(line, "the box's min is above its max")


def parse_step(words, line, text):
    kind = words[0]
    if kind == "walk":
        a = numbers(words, 2, line)
        return Step(kind, line, text, a[0], a[1])
    if kind == "door":
        a = numbers(words, 4, line)
        in_range("dir", a[2], 0, 3, line)
        st = Step(kind, line, text, a[0], a[1])
        st.dir = a[2]
        st.closed = a[3]
        return st
    if kind == "gate":
        a = numbers(words, 3, line)
        st = Step(kind, line, text, a[0], a[1])
        st.closed = a[2]
        return st
    if kind == "obj":
        a = numbers(words, 5, line)
        in_range("cmd", a[2], 1, 2, line)
        st = Step(kind, line, text, a[0], a[1])
        st.cmd = a[2]
        st.land_x = a[3]
        st.land_z = a[4]
        return st
    if kind == "wall":
        a = numbers(words, 6, line)
        in_range("dir", a[2], 0, 3, line)
        in_range("cmd", a[3], 1, 2, line)
        st = Step(kind, line, text, a[0], a[1])
        st.dir = a[2]
        st.cmd = a[3]
        st.land_x = a[4]
        st.land_z = a[5]
        return st
    if kind == "cut":
        a = numbers(words, 5, line)
        in_range("dir", a[2], 0, 3, line)
        st = Step(kind, line, text, a[0], a[1])
        st.dir = a[2]
        st.closed = a[3]
        st.item = a[4]
        return st
    if kind == "usewall":
        a = numbers(words, 6, line)
        in_range("dir", a[2], 0, 3, line)
        in_range("itemId", a[3], 1, MAX_NUMBER, line)
        st = Step(kind, line, text, a[0], a[1])
        st.dir = a[2]
        st.item = a[3]
        st.land_x = a[4]
        st.land_z = a[5]
        return st
    if kind == "useobj":
        a = numbers(words, 5, line)
        in_range("itemId", a[2], 1, MAX_NUMBER, line)
        st = Step(kind, line, text, a[0], a[1])
        st.item = a[2]
        st.land_x = a[3]
        st.land_z = a[4]
        return st
    raise recipe_error(line, "unknown instruction %s" % kind)


def parse_recipe(text):
    r = Recipe()
    route = None
    route_line = 0
    steps = []
    seen_lines = {}
    line = 0
    last_line = 0
    for raw in text.split("\n"):
        line += 1
        if len(raw.strip()) > 0:
            last_line = line
        cut = raw.find("#")
        if cut >= 0:
            raw = raw[:cut]
        words = raw.split()
        if len(words) == 0:
            continue
        key = words[0]
        if route is not None:
            if key == "end":
                numbers(words, 0, line)
                if len(steps) == 0:
                    raise recipe_error(line, "route %s has no steps" % route)
                r.routes[route] = steps
                route = None
                continue
            steps.append(parse_step(words, line, raw.strip()))
            continue
        if key in SETTING_LINES:
            if key in seen_lines:
                raise recipe_error(line, "%s is already on line %d" % (key, seen_lines[key]))
            seen_lines[key] = line
        if key == "world":
            if len(words) != 3:
                raise recipe_error(line, "world takes 1 numbers")
            r.tick_ms = number(words[2], line)
            in_range("world", r.tick_ms, 1, MAX_NUMBER, line)
            r.world = words[1]
            if r.world != "uranium":
                raise recipe_error(line, "world %s is not uranium" % r.world)
        elif key == "target":
            a = numbers(words, 9, line)
            for t in r.targets:
                if t.sid == a[0]:
                    raise recipe_error(line, "target is already on line %d" % t.line)
            check_box(a[5:9], line)
            r.targets.append(Target(line, a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8]))
        elif key == "camp":
            a = numbers(words, 6, line)
            check_box(a[2:6], line)
            r.camp_x, r.camp_z = a[0], a[1]
            r.camp_min_x, r.camp_min_z, r.camp_max_x, r.camp_max_z = a[2], a[3], a[4], a[5]
        elif key == "bank":
            a = numbers(words, 2, line)
            r.bank_x, r.bank_z = a[0], a[1]
        elif key == "style":
            a = numbers(words, 1, line)
            in_range("style", a[0], 0, 3, line)
            r.style = a[0]
        elif key == "eat":
            r.eat = numbers(words, 1, line)[0]
        elif key == "sleep":
            a = numbers(words, 1, line)
            in_range("sleep", a[0], 1, 100, line)
            r.sleep = a[0]
        elif key == "food":
            r.food = [number(w, line) for w in words[1:]]
        elif key == "item":
            wear = len(words) == 5 and words[4] == "wear"
            if wear:
                words = words[:4]
            a = numbers(words, 3, line)
            it = r.item(a[0])
            if it is not None:
                raise recipe_error(line, "item is already on line %d" % it.line)
            if a[1] > a[2]:
                raise recipe_error(line, "min is above target")
            r.items.append(Item(line, a[0], a[1], a[2], wear))
        elif key == "loot":
            r.loot = [number(w, line) for w in words[1:]]
        elif key == "bury":
            r.bury = [number(w, line) for w in words[1:]]
        elif key == "block":
            r.block = [number(w, line) for w in words[1:]]
        elif key == "route" and len(words) == 2 and (words[1] == "bank" or words[1] == "camp"):
            if "route " + words[1] in seen_lines:
                raise recipe_error(line, "route is already on line %d" % seen_lines["route " + words[1]])
            seen_lines["route " + words[1]] = line
            route = words[1]
            route_line = line
            steps = []
        else:
            raise recipe_error(line, "unknown instruction %s" % key)
    if route is not None:
        raise recipe_error(route_line, "route %s has no end" % route)
    if len(r.targets) == 0:
        raise recipe_error(last_line, "no target line")
    if r.camp_x < 0:
        raise recipe_error(last_line, "no camp line")
    if r.bank_x < 0:
        raise recipe_error(last_line, "no bank line")
    for name in ["bank", "camp"]:
        if name not in r.routes:
            raise recipe_error(last_line, "no route %s line" % name)
    for name in ["bank", "camp"]:
        for st in r.routes[name]:
            if not st.uses_item():
                continue
            it = r.item(st.item)
            if it is None or it.min < 1:
                raise recipe_error(st.line, "%s needs an item line for %d with min 1 or more" % (st.kind, st.item))
    bag = r.item(SLEEPING_BAG_ID)
    if r.sleep >= 0 and (bag is None or bag.min < 1):
        raise recipe_error(seen_lines["sleep"], "sleep needs an item line for %d with min 1 or more" % SLEEPING_BAG_ID)
    return r


R = None
by_sid = {}
stopped = False
died = False
# server updates counted, moved on by the time logged out on a login
tick = 0
acted_tick = -1
# set by the first update after the script starts or logs in; nothing is decided before it
updated = False
hook_ms = 0
update_ms = 0
logged_in = False
# the targets in view this tick, by server index
in_view = {}
started = False
camping = False
# the target hunted, and the tile it hunts at (-1 for none), as last decided
camp_target = None
hunt_x = -1
hunt_z = -1
chasing = False
attacked_blocker = False
# the server index of the last block NPC attacked as in the way
blocker_sid = -1
pre_attack_tick = -1
standing = False
out_of_food = False
# [deposits or withdrawals, the tick they were first sent] while some are still to do
bank_batch = None
route_name = None
route_pos = -1
step_best = -1
step_tick = 0
step_log_tick = 0
far = None
far_route = None
far_tried = set()
start_xp = []


def stop(message):
    global stopped
    log(message)
    if stopped:
        return
    stopped = True
    set_autologin(False)
    stop_script()


def ensure_recipe():
    global R, by_sid, start_xp
    if R is not None:
        return True
    if stopped:
        return False
    text = getattr(settings, "recipe", None)
    if not isinstance(text, str):
        stop("Error: no recipe = '''...''' under [script.settings]")
        return False
    try:
        r = parse_recipe(text)
    except ValueError as e:
        stop(str(e))
        return False
    by_sid = {}
    for t in r.targets:
        by_sid[str(t.sid)] = t
    start_xp = [get_experience(i) for i in range(4)]
    R = r
    log("Camping %d spawns around %d,%d; banking at %d,%d" % (len(r.targets), r.camp_x, r.camp_z, r.bank_x, r.bank_z))
    return True


def item_name(item_id):
    # Plutonium's item table may end before an id the recipe names
    try:
        return get_item_name(item_id)
    except Exception:
        return "item %d" % item_id


def tiles(x1, z1, x2, z2):
    return max(abs(x1 - x2), abs(z1 - z2))


def nearer(d, x, z, item_id, best_d, best):
    # nearest by tiles, ties to the lower x, z and id
    if best is None or d < best_d:
        return True
    if d > best_d:
        return False
    if x != best.x:
        return x < best.x
    if z != best.z:
        return z < best.z
    return item_id < best.id


# gpython cannot hash a tuple: a set or dict of tiles is keyed by strings
def tile_key(x, z):
    return "%d %d" % (x, z)


def in_camp(x, z):
    return R.camp_min_x <= x <= R.camp_max_x and R.camp_min_z <= z <= R.camp_max_z


def near_camp(x, z):
    return R.camp_min_x - 1 <= x <= R.camp_max_x + 1 and R.camp_min_z - 1 <= z <= R.camp_max_z + 1


def floor_of(z):
    return z // FLOOR_HEIGHT


def box_in_view(t, x, z):
    return (within_view(x, z, t.min_x, t.min_z) and within_view(x, z, t.min_x, t.max_z) and
            within_view(x, z, t.max_x, t.min_z) and within_view(x, z, t.max_x, t.max_z))


def within_view(x, z, tx, tz):
    return (tx - x) * (tx - x) + (tz - z) * (tz - z) <= VIEW_SQ


def walk(x, z):
    # False when the client's walking cannot reach the tile; sends nothing standing on it
    if get_x() == x and get_z() == z:
        return True
    if not is_reachable(x, z):
        return False
    # round the block NPCs in view, or, with no way round them, attacking the one in the way;
    # else with the client's walking
    if not way_round(x, z, x, z, False, True):
        walk_to(x, z)
    return True


def use_bag():
    # Right before an attack on a target, after the tick's walk, which the bag keeps while it drops
    # an attack sent before it: sleeps with fatigue at the recipe's percent, as the server lets a
    # sleeping player walk and attack, and wakes only a player victim
    # https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemActionHandler.java#L84-L88
    # https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/Sleeping.java#L41
    # https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L771
    if R.sleep < 0 or is_sleeping():
        return
    if get_fatigue() < R.sleep or not has_inventory_item(SLEEPING_BAG_ID):
        return
    use_sleeping_bag()


def use_area(st):
    # The area the stock client walks to for the step's object or wall, by the object's size, turn
    # and type and the wall's side: [min x, min z, max x, max z, beside].
    x = st.x
    z = st.z
    if st.kind != "gate" and st.kind != "obj" and st.kind != "useobj":
        if st.dir == 0:
            return [x, z - 1, x, z, False]
        if st.dir == 1:
            return [x - 1, z, x, z, False]
        return [x, z, x, z, True]
    o = get_object_from_coords(x, z)
    if o is None:
        return [x, z, x, z, True]
    size = OBJECT_SIZE.get(str(o.id), [1, 1, 0])
    w = size[0]
    h = size[1]
    if o.dir != 0 and o.dir != 4:
        w = size[1]
        h = size[0]
    if size[2] == 0:
        return [x, z, x + w - 1, z + h - 1, True]
    if o.dir == 0:
        w += 1
        x -= 1
    if o.dir == 2:
        h += 1
    if o.dir == 6:
        z -= 1
        h += 1
    if o.dir == 4:
        w += 1
    return [x, z, x + w - 1, z + h - 1, False]


def approach(st):
    # Before a click or use on the step's object or wall: walks round the block NPCs to where it is
    # used from, or attacks the one in the way; True when it did, and the click waits.
    a = use_area(st)
    return way_round(a[0], a[1], a[2], a[3], a[4], False)


def way_round(x1, z1, x2, z2, beside, walking):
    # Finds the path to the area, or beside it, as the client does, with the tile of each block NPC
    # in the search box counted as blocked, and sends it as the client sends a walk. With no way
    # round them, attacks the block NPC not fighting on the first tile of the path the client's own
    # walking takes. False, sending nothing, when no block NPC is in the box, when there is no way
    # round and no NPC to attack, or, before a click (walking False), when the player is already
    # there.
    global attacked_blocker, blocker_sid
    if len(R.block) == 0:
        return False
    px = get_x()
    pz = get_z()
    min_x = min(px, x1) - SEARCH_TILES
    min_z = min(pz, z1) - SEARCH_TILES
    w = max(px, x2) + SEARCH_TILES - min_x + 1
    h = max(pz, z2) + SEARCH_TILES - min_z + 1
    marks = []
    for n in get_npcs():
        if n is None or n.id not in R.block:
            continue
        if n.x < min_x or n.z < min_z or n.x >= min_x + w or n.z >= min_z + h:
            continue
        if n.x == px and n.z == pz:
            continue
        # a walk to an NPC's tile walks to that NPC
        if walking and n.x == x1 and n.z == z1:
            continue
        marks.append((n.x - min_x) * h + n.z - min_z)
    if len(marks) == 0:
        return False
    plain = [0] * (w * h)
    for i in range(w):
        for j in range(h):
            plain[i * h + j] = get_collision_flags_at(min_x + i, min_z + j)
    flags = list(plain)
    for m in marks:
        flags[m] = flags[m] | FULL_BLOCK_BITS
    start_x = px - min_x
    start_z = pz - min_z
    dest = [x1 - min_x, z1 - min_z, x2 - min_x, z2 - min_z]
    path = find_path(flags, w, h, start_x, start_z, dest[0], dest[1], dest[2], dest[3], beside)
    if path is None:
        own = find_path(plain, w, h, start_x, start_z, dest[0], dest[1], dest[2], dest[3], beside)
        if own is None or (own[0] == start_x and own[1] == start_z):
            return False
        n = blocker_on(px + sign(own[0] - start_x), pz + sign(own[1] - start_z))
        if n is None:
            return False
        create_packet(OP_ATTACK_NPC)
        write_short(n.sid)
        send_packet()
        attacked_blocker = True
        blocker_sid = n.sid
        return True
    if len(path) == 2 and path[0] == start_x and path[1] == start_z:
        return False
    # the turns from the first to the tile, at most 25 after the first, as the client sends them
    first_x = path[0] + min_x
    first_z = path[1] + min_z
    create_packet(OP_WALK)
    write_short(first_x)
    write_short(first_z)
    i = 1
    while i < len(path) // 2 and i <= 25:
        write_byte((path[2 * i] + min_x - first_x) & 0xFF)
        write_byte((path[2 * i + 1] + min_z - first_z) & 0xFF)
        i += 1
    send_packet()
    return True


def sign(n):
    # gpython cannot subtract one bool from another
    if n > 0:
        return 1
    if n < 0:
        return -1
    return 0


def blocker_on(x, z):
    # the block NPC not fighting on the tile, the lowest id then index first; None for none
    best = None
    for n in get_npcs():
        if n is None or n.id not in R.block or n.x != x or n.z != z or n.in_combat():
            continue
        if best is None or n.id < best.id or (n.id == best.id and n.sid < best.sid):
            best = n
    return best


def find_path(flags, w, h, start_x, start_z, dest_x1, dest_z1, dest_x2, dest_z2, beside):
    # The stock client's breadth-first search, over flags[x * h + z]: the tiles where the path
    # turns, from the first to the first tile it reaches in the area (dest_x1, dest_z1) to
    # (dest_x2, dest_z2), or, beside, next to it with no wall between, as x, z pairs; None when it
    # cannot be reached. A start already there gives just the start.
    west = FULL_BLOCK_BITS | WALL_WEST_BIT
    east = FULL_BLOCK_BITS | WALL_EAST_BIT
    south = FULL_BLOCK_BITS | WALL_SOUTH_BIT
    north = FULL_BLOCK_BITS | WALL_NORTH_BIT
    source = [0] * (w * h)
    queue = [start_x * h + start_z]
    source[start_x * h + start_z] = 99
    read = 0
    dest_x = -1
    dest_z = -1
    while read < len(queue):
        at = queue[read]
        read += 1
        x = at // h
        z = at % h
        in_x = x >= dest_x1 and x <= dest_x2
        in_z = z >= dest_z1 and z <= dest_z2
        if in_x and in_z or beside and (
                in_z and x > 0 and x - 1 >= dest_x1 and x - 1 <= dest_x2
                and flags[at - h] & WALL_WEST_BIT == 0
                or in_z and x < w - 1 and x + 1 >= dest_x1 and x + 1 <= dest_x2
                and flags[at + h] & WALL_EAST_BIT == 0
                or in_x and z > 0 and z - 1 >= dest_z1 and z - 1 <= dest_z2
                and flags[at - 1] & WALL_SOUTH_BIT == 0
                or in_x and z < h - 1 and z + 1 >= dest_z1 and z + 1 <= dest_z2
                and flags[at + 1] & WALL_NORTH_BIT == 0):
            dest_x = x
            dest_z = z
            break
        if x > 0 and source[at - h] == 0 and flags[at - h] & west == 0:
            queue.append(at - h)
            source[at - h] = SOURCE_WEST
        if x < w - 1 and source[at + h] == 0 and flags[at + h] & east == 0:
            queue.append(at + h)
            source[at + h] = SOURCE_EAST
        if z > 0 and source[at - 1] == 0 and flags[at - 1] & south == 0:
            queue.append(at - 1)
            source[at - 1] = SOURCE_SOUTH
        if z < h - 1 and source[at + 1] == 0 and flags[at + 1] & north == 0:
            queue.append(at + 1)
            source[at + 1] = SOURCE_NORTH
        if (x > 0 and z > 0 and flags[at - 1] & south == 0 and flags[at - h] & west == 0
                and flags[at - h - 1] & (south | west) == 0 and source[at - h - 1] == 0):
            queue.append(at - h - 1)
            source[at - h - 1] = SOURCE_SOUTH | SOURCE_WEST
        if (x < w - 1 and z > 0 and flags[at - 1] & south == 0 and flags[at + h] & east == 0
                and flags[at + h - 1] & (south | east) == 0 and source[at + h - 1] == 0):
            queue.append(at + h - 1)
            source[at + h - 1] = SOURCE_SOUTH | SOURCE_EAST
        if (x > 0 and z < h - 1 and flags[at + 1] & north == 0 and flags[at - h] & west == 0
                and flags[at - h + 1] & (north | west) == 0 and source[at - h + 1] == 0):
            queue.append(at - h + 1)
            source[at - h + 1] = SOURCE_NORTH | SOURCE_WEST
        if (x < w - 1 and z < h - 1 and flags[at + 1] & north == 0 and flags[at + h] & east == 0
                and flags[at + h + 1] & (north | east) == 0 and source[at + h + 1] == 0):
            queue.append(at + h + 1)
            source[at + h + 1] = SOURCE_NORTH | SOURCE_EAST
    if dest_x == -1:
        return None
    # back from the tile, keeping it and each tile where the direction changes
    x = dest_x
    z = dest_z
    turns = [x, z]
    prev = source[x * h + z]
    src = prev
    while x != start_x or z != start_z:
        if src != prev:
            prev = src
            turns = turns + [x, z]
        if src & SOURCE_SOUTH != 0:
            z += 1
        elif src & SOURCE_NORTH != 0:
            z -= 1
        if src & SOURCE_WEST != 0:
            x += 1
        elif src & SOURCE_EAST != 0:
            x -= 1
        src = source[x * h + z]
    path = []
    i = len(turns) - 2
    while i >= 0:
        path = path + [turns[i], turns[i + 1]]
        i -= 2
    return path


def observe():
    global in_view
    now = {}
    for n in get_npcs():
        key = str(n.sid)
        t = by_sid.get(key)
        if t is not None and n.id == t.npc_id:
            now[key] = n
    x, z = get_x(), get_z()
    for t in R.targets:
        key = str(t.sid)
        n = now.get(key)
        if n is not None:
            t.fighting = n.in_combat()
            if t.fighting:
                t.due = tick + t.respawn
            else:
                t.due = -1
            t.tile_x = -1
            t.tile_z = -1
            t.last_x = n.x
            t.last_z = n.z
            t.wait = 0
            continue
        left_near = False
        if key in in_view and within_view(x, z, t.last_x, t.last_z):
            # one leaving view while fighting keeps its rolling due
            if not t.fighting:
                t.due = tick + t.respawn
            t.wait = 0
            left_near = True
        elif key in in_view and not t.fighting:
            t.tile_x = t.last_x
            t.tile_z = t.last_z
        if t.tile_x >= 0 and within_view(x, z, t.tile_x, t.tile_z):
            t.tile_x = -1
            t.tile_z = -1
        at_spawn = tiles(x, z, t.x, t.z) <= 1
        # someone else killed it out of sight
        if t.due < 0 or tick - t.due > 1:
            if (at_spawn and t.tile_x < 0) or box_in_view(t, x, z):
                t.due = tick + t.respawn
        if not left_near and at_spawn and camp_target is not None and camp_target.sid == t.sid:
            t.wait += 1
    in_view = now


def pre_attack():
    global pre_attack_tick
    # the attack on a block NPC in the way is this tick's attack
    if not chasing or attacked_blocker or camp_target is None or tick == pre_attack_tick:
        return
    if fighting() or str(camp_target.sid) in in_view:
        return
    use_bag()
    pre_attack_tick = tick
    create_packet(OP_ATTACK_NPC)
    write_short(camp_target.sid)
    send_packet()


def on_server_tick(server_tick):
    global tick, updated, hook_ms, update_ms, logged_in
    if R is None or stopped:
        return
    hook_ms = now_ms()
    if logged_in and update_ms > 0:
        # Plutonium's own count does not move while logged out
        tick += max(1, (hook_ms - update_ms) // R.tick_ms)
    else:
        tick += 1
    logged_in = False
    update_ms = hook_ms
    try:
        observe()
        updated = True
    except Exception as e:
        stop("Error: " + str(e))


def on_server_message(msg):
    global standing
    if msg.find("You have been standing here for") >= 0:
        standing = True


def on_load():
    # logged in again: what was in view did not die, and nothing is known until the next update
    global in_view, updated, logged_in
    in_view = {}
    updated = False
    logged_in = True


def on_init():
    ensure_recipe()


def on_death():
    global died
    died = True


def loop():
    global acted_tick
    if stopped:
        return 1000
    if not ensure_recipe():
        return 1000
    if died:
        stop("Died: start it again")
        return 1000
    # once per server update, after all of it is applied
    if updated and tick != acted_tick and now_ms() - hook_ms >= SETTLE_MS:
        acted_tick = tick
        try:
            act()
            # after this tick's walk: a walk sent after an attack cancels it
            pre_attack()
        except Exception as e:
            stop("Error: " + str(e))
    return POLL_MS


def now_ms():
    return int(time.time() * 1000)


def act():
    global chasing, attacked_blocker, started, camping, standing, route_name, bank_batch, out_of_food
    chasing = False
    attacked_blocker = False
    x, z = get_x(), get_z()
    if not started:
        started = True
        camping = in_camp(x, z) and is_reachable(R.camp_x, R.camp_z)
    if camping and not near_camp(x, z):
        camping = False
        stop_hunting()
    if carried_food() is not None:
        out_of_food = False
    if fighting():
        if get_current_stat(HITS) <= R.eat:
            walk_to(x, z)
        elif breaks_off(x, z):
            tx, tz = break_off_tile(x, z)
            walk_to(tx, tz)
        return
    if is_bank_open():
        if route_name == "bank":
            route_name = None
        bank()
        return
    bank_batch = None
    if is_option_menu():
        answer(0)
        return
    if R.style >= 0 and get_combat_style() != R.style:
        set_combat_style(R.style)
        return
    if eat():
        return
    if equip():
        return
    if bury():
        return
    if standing:
        standing = False
        if step_aside(x, z):
            return
    need = need_bank()
    if need and route_name == "camp":
        route_name = None
    if need or route_name == "bank":
        to_bank(x, z)
        return
    if not camping:
        if not follow("camp"):
            return
        route_name = None
        camping = True
    if loot(x, z):
        return
    hunt(x, z)


def fighting():
    # the client says so, or an NPC fighting stands on the player's tile: the client can say so a
    # tick or two late
    if in_combat():
        return True
    x, z = get_x(), get_z()
    for n in get_npcs():
        if n is not None and n.x == x and n.z == z and n.in_combat():
            return True
    return False


def stop_hunting():
    global camp_target, hunt_x, hunt_z
    camp_target = None
    hunt_x = -1
    hunt_z = -1


def breaks_off(x, z):
    # The opponent is the NPC in view fighting on the player's tile, the lowest server index first:
    # one that is not a target, nor the block NPC last attacked as in the way, is walked away from.
    opp = None
    for n in get_npcs():
        if n is None or n.x != x or n.z != z or not n.in_combat():
            continue
        if opp is None or n.sid < opp.sid:
            opp = n
    if opp is None:
        return False
    t = by_sid.get(str(opp.sid))
    if t is not None and t.npc_id == opp.id:
        return False
    return not (opp.sid == blocker_sid and opp.id in R.block)


def break_off_tile(x, z):
    # the current route step's tile, else the tile it hunts at; its own when there is neither, or
    # the client's walking does not reach it
    tx = x
    tz = z
    if route_name is not None and route_pos < len(R.routes[route_name]):
        tx = R.routes[route_name][route_pos].x
        tz = R.routes[route_name][route_pos].z
    elif hunt_x >= 0:
        tx = hunt_x
        tz = hunt_z
    if not in_local_window(x, z, tx, tz) or not is_reachable(tx, tz):
        return x, z
    return tx, tz


def carried_food():
    for food_id in R.food:
        food = get_inventory_item_by_id(food_id)
        if food is not None:
            return food
    return None


def eat():
    global out_of_food
    if get_current_stat(HITS) > R.eat:
        return False
    food = carried_food()
    if food is None:
        if not out_of_food:
            out_of_food = True
            log("Out of food")
        return False
    use_item(food)
    return True


def equip():
    for it in R.items:
        if not it.wear or is_inventory_item_equipped(it.id):
            continue
        for inv in get_inventory_items():
            if inv.id == it.id and not inv.equipped:
                equip_item(inv)
                return True
    return False


def bury():
    for bone_id in R.bury:
        bone = get_inventory_item_by_id(bone_id)
        if bone is not None:
            use_item(bone)
            return True
    return False


def step_aside(x, z):
    for dx in [-1, 0, 1]:
        for dz in [-1, 0, 1]:
            if (dx != 0 or dz != 0) and walk(x + dx, z + dz):
                return True
    return False


def need_bank():
    for it in R.items:
        if get_inventory_count_by_id(it.id) < it.min:
            return True
    if get_empty_slots() > 0:
        return False
    return len(deposits()) > 0


def deposits():
    keep = [it.id for it in R.items]
    out = []
    seen = []
    for it in get_inventory_items():
        if it.equipped or it.id in keep or it.id in seen:
            continue
        seen.append(it.id)
        n = 0
        for other in get_inventory_items():
            if other.id == it.id and not other.equipped:
                n += other.amount
        out.append([it.id, n])
    return out


def to_bank(x, z):
    global route_name
    if tiles(x, z, R.bank_x, R.bank_z) <= BANK_TILES:
        if route_name == "bank":
            route_name = None
    elif not (route_name == "bank" and route_pos >= len(R.routes["bank"])) and not follow("bank"):
        return
    banker = None
    best_d = 0
    for n in get_npcs():
        if n.id not in BANKER_IDS:
            continue
        d = tiles(x, z, n.x, n.z)
        if nearer(d, n.x, n.z, n.id, best_d, banker):
            banker = n
            best_d = d
    if banker is not None:
        talk_to_npc(banker)
    else:
        walk(R.bank_x, R.bank_z)


def batch(kind):
    # False, once it has stopped, when the deposits or withdrawals were first sent BANK_TICKS ago
    global bank_batch
    if bank_batch is None or bank_batch[0] != kind:
        bank_batch = [kind, tick]
    elif tick - bank_batch[1] >= BANK_TICKS:
        stop("The bank did not take " + kind)
        return False
    return True


def bank():
    global bank_batch
    junk = deposits()
    if len(junk) > 0:
        if batch("deposits"):
            for j in junk:
                deposit(j[0], j[1])
        return
    plan = withdrawals()
    if plan is None:
        return
    if len(plan) > 0:
        if batch("withdrawals"):
            for w in plan:
                withdraw(w[0], w[1])
        return
    bank_batch = None
    close_bank()


def withdrawals():
    # [id, amount] per item in recipe order: each item's min takes its room first, then the rest
    # of the free slots go to the targets in order. None once it has stopped.
    free = get_empty_slots()
    amounts = {}
    for it in R.items:
        have = get_inventory_count_by_id(it.id)
        if have >= it.min:
            continue
        banked = get_bank_count(it.id)
        name = item_name(it.id)
        if have + banked < it.min:
            stop("Out of %s: %d carried, %d in the bank, min %d" % (name, have, banked, it.min))
            return None
        need = it.min - have
        if it.id in STACKABLE:
            slots = 0 if have > 0 else 1
        else:
            slots = need
        if slots > free:
            stop("No room for %s" % name)
            return None
        free -= slots
        amounts[str(it.id)] = need
    plan = []
    for it in R.items:
        key = str(it.id)
        taken = amounts.get(key, 0)
        have = get_inventory_count_by_id(it.id) + taken
        want = min(it.target - have, get_bank_count(it.id) - taken)
        if want > 0:
            if it.id in STACKABLE:
                if have == 0:
                    if free < 1:
                        want = 0
                    else:
                        free -= 1
            else:
                want = min(want, free)
                free -= want
        if taken + max(want, 0) > 0:
            plan.append([it.id, taken + max(want, 0)])
    return plan


def wall_reachable(x, z, wall_dir):
    # the tiles beside the wall, as the client walks to one
    if wall_dir == 0:
        return find_local_path_length(x, z - 1, x, z, False) != -1
    if wall_dir == 1:
        return find_local_path_length(x - 1, z, x, z, False) != -1
    return find_local_path_length(x, z, x, z, True) != -1


def object_reachable(x, z):
    o = get_object_from_coords(x, z)
    if o is not None:
        return is_object_reachable(o)
    return find_local_path_length(x, z, x, z, True) != -1


def step_reachable(st):
    if st.kind == "walk":
        return is_reachable(st.x, st.z)
    if st.kind == "gate" or st.kind == "obj" or st.kind == "useobj":
        return object_reachable(st.x, st.z)
    return wall_reachable(st.x, st.z, st.dir)


def step_sent(st):
    # The server sends Plutonium, a custom client, scenery and walls only within view_distance (2)
    # grid cells of 8 tiles, nearer than a route resumes from: one it was not sent cannot be used yet.
    # https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L1093
    # https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L1186
    if st.kind == "walk":
        return True
    if st.kind == "gate" or st.kind == "obj" or st.kind == "useobj":
        return get_object_from_coords(st.x, st.z) is not None
    return find_wall(st.x, st.z, st.dir) is not None


def resume_step(steps, x, z):
    # [distance, the step it stands for, the step, landing or not] within reach, nearest first, the
    # later on a tie: a step the player is already past is not done again
    found = []
    for i in range(len(steps)):
        st = steps[i]
        if floor_of(st.z) == floor_of(z) and tiles(x, z, st.x, st.z) <= ROUTE_REACH:
            found.append([tiles(x, z, st.x, st.z), i, st, False])
        if st.land_x >= 0 and floor_of(st.land_z) == floor_of(z) and tiles(x, z, st.land_x, st.land_z) <= ROUTE_REACH:
            found.append([tiles(x, z, st.land_x, st.land_z), i + 1, st, True])
    found.sort(key=lambda c: c[0] * 100000 - c[1])
    for c in found:
        st = c[2]
        if c[3]:
            if is_reachable(st.land_x, st.land_z):
                return c[1]
        elif step_sent(st) and step_reachable(st):
            return c[1]
    return -1


def far_reset(name):
    global far, far_route, far_tried
    far = None
    far_route = name
    far_tried = set()


def far_walk(name, steps, x, z):
    """Walks Plutonium's long path to the tile of the route nearest us on our floor that it has a
    path to, one path search a tick, trying the next nearest only when it has none. The walk is
    the walker's, kept going every tick: its paths open no doors, and one walked to its end with the
    route still out of reach is asked for again, to the same tile."""
    global far
    if far_route != name:
        far_reset(name)
    if far is not None:
        path = far["path"]
        path.process()
        if not path.complete():
            path.walk()
            return False
        path = calculate_path_to(far["x"], far["z"])
        if path is None:
            far = None
            return False
        far["path"] = path
        path.process()
        path.walk()
        return False
    left = far_tiles(steps, x, z)
    if len(left) == 0:
        stop("No way to the route")
        return False
    t = left[0]
    far_tried.add(tile_key(t[0], t[1]))
    path = calculate_path_to(t[0], t[1])
    if path is not None:
        far = {"x": t[0], "z": t[1], "path": path}
        log("Route %s: too far to resume, walking to %d,%d" % (name, t[0], t[1]))
        path.process()
        path.walk()
    return False


def far_tiles(steps, x, z):
    out = []
    keys = set()
    for st in steps:
        anchors = [[st.x, st.z]]
        if st.land_x >= 0:
            anchors.append([st.land_x, st.land_z])
        for a in anchors:
            key = tile_key(a[0], a[1])
            if floor_of(a[1]) != floor_of(z) or key in far_tried or key in keys:
                continue
            keys.add(key)
            out.append(a)
    out.sort(key=lambda t: tiles(x, z, t[0], t[1]))
    return out


def set_step(i, fresh):
    global route_pos, step_best, step_tick, step_log_tick
    route_pos = i
    if fresh or i > step_best:
        step_best = i
        step_tick = tick
        step_log_tick = tick + 1


def step_log(name, steps):
    # from the 21st tick without getting past the furthest step reached, every 20 ticks
    global step_log_tick
    t = tick - step_tick
    if t > STEP_LOG_TICKS and tick - step_log_tick >= STEP_LOG_TICKS:
        step_log_tick = tick
        log("Route %s step %d (%s) for %d ticks" % (name, route_pos + 1, steps[route_pos].text, t))


def follow(name):
    # True once the route has reached its last step
    global route_name
    steps = R.routes[name]
    x, z = get_x(), get_z()
    if route_name == name and route_pos < len(steps) and tiles(x, z, steps[route_pos].x, steps[route_pos].z) > ROUTE_REACH:
        route_name = None
    if route_name != name:
        i = resume_step(steps, x, z)
        if i < 0:
            return far_walk(name, steps, x, z)
        far_reset(None)
        route_name = name
        set_step(i, True)
        if i < len(steps):
            log("Route %s: from step %d of %d" % (name, i + 1, len(steps)))
        else:
            log("Route %s: done" % name)
            return True
    elif route_pos >= len(steps):
        return True
    while route_pos < len(steps):
        if not do_step(name, steps, x, z):
            if not stopped:
                step_log(name, steps)
            return False
        set_step(route_pos + 1, False)
    log("Route %s: done" % name)
    return True


def find_wall(x, z, wall_dir):
    for w in get_wall_objects():
        if w.x == x and w.z == z and w.dir == wall_dir:
            return w
    return None


def shut(st):
    # the closed door, gate or web of the step, or None when it is open
    if st.kind == "gate":
        o = get_object_from_coords(st.x, st.z)
    else:
        o = find_wall(st.x, st.z, st.dir)
    if o is not None and o.id == st.closed:
        return o
    return None


def landed(st, x, z):
    if floor_of(st.land_z) == floor_of(st.z):
        return x == st.land_x and z == st.land_z
    return tiles(x, z, st.land_x, st.land_z) <= 1


def do_step(name, steps, x, z):
    # True once the step is done
    st = steps[route_pos]
    it = None
    if st.uses_item():
        it = get_inventory_item_by_id(st.item)
        if it is None:
            stop("Route %s step %d needs %s" % (name, route_pos + 1, item_name(st.item)))
            return False
    if st.kind == "walk":
        if tiles(x, z, st.x, st.z) <= WALK_DONE_TILES:
            return True
        if walk(st.x, st.z):
            return False
        return back_to_door(name, steps, x, z)
    if st.kind == "door" or st.kind == "gate" or st.kind == "cut":
        o = shut(st)
        if o is None:
            return True
        if not step_reachable(st) and door_behind(steps) >= 0:
            return back_to_door(name, steps, x, z)
        if approach(st):
            return False
        if st.kind == "gate":
            at_object(o)
        elif it is None:
            at_wall_object(o)
        else:
            use_item_on_wall_object(it, o)
        return False
    if landed(st, x, z):
        return True
    if not step_reachable(st) and door_behind(steps) >= 0:
        return back_to_door(name, steps, x, z)
    if st.kind == "obj" or st.kind == "useobj":
        o = get_object_from_coords(st.x, st.z)
    else:
        o = find_wall(st.x, st.z, st.dir)
    if o is None or approach(st):
        return False
    if st.kind == "obj":
        if st.cmd == 1:
            at_object(o)
        else:
            at_object2(o)
    elif st.kind == "wall":
        if st.cmd == 1:
            at_wall_object(o)
        else:
            at_wall_object2(o)
    elif st.kind == "useobj":
        use_item_on_object(it, o)
    else:
        use_item_on_wall_object(it, o)
    return False


def door_behind(steps):
    # the last door, gate or cut step before the current one when it is shut (someone shut the door
    # behind the player, or the web grew back), else -1
    back = route_pos - 1
    while back >= 0 and steps[back].kind not in ["door", "gate", "cut"]:
        back -= 1
    if back < 0 or shut(steps[back]) is None:
        return -1
    return back


def back_to_door(name, steps, x, z):
    # the current step cannot be reached: a shut door behind it is done instead, that same tick
    global route_pos
    back = door_behind(steps)
    if back < 0:
        return False
    route_pos = back
    return do_step(name, steps, x, z)


def loot(x, z):
    if len(R.loot) + len(R.bury) == 0:
        return False
    if camp_target is not None and due_soon(camp_target, x, z):
        return False
    full = get_empty_slots() == 0
    best = None
    best_d = 0
    for g in get_ground_items():
        if g.id not in R.loot and g.id not in R.bury:
            continue
        # one on its own tile is taken even outside the camp
        if not in_camp(g.x, g.z) and (g.x != x or g.z != z):
            continue
        if full and not (g.id in STACKABLE and has_inventory_item(g.id)):
            continue
        d = tiles(x, z, g.x, g.z)
        if d > LOOT_TILES:
            continue
        # its own tile, or one beside it in x or z with no wall between; the own tile is nearest
        if nearer(d, g.x, g.z, g.id, best_d, best) and is_ground_item_reachable(g):
            best = g
            best_d = d
    if best is None:
        return False
    # walks to the tile beside it when its own tile cannot be reached, as the stock client does
    pickup_item(best)
    return True


def due_soon(t, x, z):
    if t.due < tick or t.due - tick > DUE_SOON_TICKS:
        return False
    return tiles(x, z, t.x, t.z) <= 1


def live(t):
    # the target's NPC when it is in view, not fighting, inside the camp and reachable, else None
    n = in_view.get(str(t.sid))
    if n is None or n.in_combat() or not in_camp(n.x, n.z) or not is_reachable(n.x, n.z):
        return None
    return n


def time_to(t, n, x, z):
    # ticks until the target can be fought, with tiles as the walking time
    if n is not None:
        return tiles(x, z, n.x, n.z)
    if t.due >= 0:
        walk_ticks = tiles(x, z, t.x, t.z)
        wait_ticks = max(0, t.due - tick)
        if walk_ticks - wait_ticks > WALK_SLACK_TICKS:
            return max(walk_ticks, wait_ticks) + UNSURE_TICKS
        return max(walk_ticks, wait_ticks)
    if t.tile_x >= 0:
        return tiles(x, z, t.tile_x, t.tile_z) + UNSURE_TICKS
    return tiles(x, z, t.x, t.z) + UNSURE_TICKS


def hunt(x, z):
    global camp_target, chasing
    while True:
        scored = []
        for t in R.targets:
            if tick < t.skip_until:
                continue
            n = live(t)
            scored.append([time_to(t, n, x, z), t, n])
        best = None
        for c in scored:
            if best is None or c[0] < best[0]:
                best = c
        if best is None:
            stop_hunting()
            return
        for c in scored:
            if camp_target is not None and c[1].sid == camp_target.sid:
                if c[0] - best[0] < KEEP_TICKS or due_soon(c[1], x, z):
                    best = c
        if go(best[1], best[2], x, z):
            camp_target = best[1]
            chasing = True
            return
        t = best[1]
        log("No path to #%d's spawn at %d,%d; skipping it for %d ticks" % (t.sid, t.x, t.z, SKIP_TICKS))
        t.skip_until = tick + SKIP_TICKS


def go(t, n, x, z):
    # Attacks the live target, walking there first from far away, or waits for it; False when the
    # tile it waits on cannot be walked to.
    global hunt_x, hunt_z
    if n is not None:
        hunt_x = n.x
        hunt_z = n.z
        if tiles(x, z, n.x, n.z) > APPROACH_TILES and walk(n.x, n.z):
            return True
        use_bag()
        attack_npc(n)
        return True
    hunt_x = t.x
    hunt_z = t.z
    if t.wait > t.respawn:
        # alive somewhere in its box, out of view: waits at the corner until the count restarts
        c = far_corner(t, x, z)
        if c is not None:
            hunt_x = c[0]
            hunt_z = c[1]
    elif t.due < 0 and t.tile_x >= 0:
        hunt_x = t.tile_x
        hunt_z = t.tile_z
    if hunt_x != t.x or hunt_z != t.z:
        # a tile or corner it cannot plan a path to gets no walk this tick, and no skip
        if in_local_window(x, z, hunt_x, hunt_z):
            walk(hunt_x, hunt_z)
        return True
    if in_local_window(x, z, t.x, t.z):
        return walk(t.x, t.z)
    return walk_toward(x, z, t.x, t.z)


def far_corner(t, x, z):
    # the corner of its wander box farthest from its spawn that the client's walking reaches, the
    # first in this order on a tie; None for none
    best = None
    best_d = -1
    for c in [[t.min_x, t.min_z], [t.max_x, t.min_z], [t.min_x, t.max_z], [t.max_x, t.max_z]]:
        d = tiles(t.x, t.z, c[0], c[1])
        if d > best_d and in_local_window(x, z, c[0], c[1]) and is_reachable(c[0], c[1]):
            best = c
            best_d = d
    return best


def in_local_window(x, z, tx, tz):
    # Plutonium plans the client's walking only inside the 96 tiles square it loads around the
    # player, whose corner moves in steps of 48 tiles.
    wx = ((x + 24) // 48) * 48 - 48
    wz = ((z + 24) // 48) * 48 - 48
    return wx <= tx < wx + 96 and wz <= tz < wz + 96


def toward(a, b, i, n):
    # a + (b - a) * i / n, the division rounding toward 0
    d = (b - a) * i
    if d < 0:
        return a - (-d) // n
    return a + d // n


def walk_toward(x, z, sx, sz):
    # walks to the tile on the line from the spawn to the player nearest the spawn that is inside
    # the window, inside the camp and reachable; False when there is none
    n = tiles(x, z, sx, sz)
    for i in range(1, n):
        tx = toward(sx, x, i, n)
        tz = toward(sz, z, i, n)
        if in_local_window(x, z, tx, tz) and in_camp(tx, tz) and walk(tx, tz):
            return True
    return False


def on_progress_report():
    if R is None or len(start_xp) < 4:
        return {}
    names = ["Attack", "Defense", "Strength", "Hits"]
    report = {}
    for i in range(4):
        report[names[i] + " XP gained"] = get_experience(i) - start_xp[i]
    return report
