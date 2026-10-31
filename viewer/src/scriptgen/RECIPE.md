# Camp recipes

A camp recipe is what the viewer's "Generate script" panel writes: which NPC spawns to camp, where
the bank is, what to carry, and the two walks between the camp and the bank. Each supported client
has one camper script that reads a recipe and does the rest. The camper is the same for everyone;
only the recipe differs.

| Client    | Camper                                | Where the recipe goes                                                      |
| --------- | ------------------------------------- | -------------------------------------------------------------------------- |
| APOS      | `clients/apos/Bob_MapCamper.java`     | pasted into the camper's window; kept in `recipes/Bob_MapCamper/`          |
| IdleRSC   | `clients/idlersc/Bob_MapCamper.java`  | pasted into the camper's window; kept in `Cache/botconfigs/Bob_MapCamper/` |
| Plutonium | `clients/plutonium/bob_map_camper.py` | `recipe` in the account file's `[script.settings]`                         |

The Java campers keep every recipe started from their window, one file each, named by its first
line, and list them newest first. A saved recipe's name given as the script's parameters starts it
without the window.

## Format

One instruction per line. `#` starts a comment, to the end of the line. Blank lines are ignored.
The first line is a comment naming the recipe, such as `# 12x Pirate @ 268, 104`: how many spawns
of each NPC, and the camp.
Words are separated by spaces. Tiles are server coordinates `x z`, where `z` includes the floor:
floor `n` adds `944 * n` (the first floor up is `z + 944`, a dungeon is `z + 2832`).

### Settings

| Line                                                                        | Meaning                                                                                                                                           |
| --------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| `world <name> <tickMs>`                                                     | The world it was generated for, and its tick length in milliseconds.                                                                              |
| `target <index> <npcId> <x> <z> <respawnTicks> <minX> <minZ> <maxX> <maxZ>` | A spawn to camp: the NPC's server index, its id, its spawn tile, how many ticks after its death it comes back, and the box it wanders in.         |
| `camp <x> <z> <minX> <minZ> <maxX> <maxZ>`                                  | Where the camp is: the tile `route camp` ends on, and the box of tiles the camp covers, every tile the targets wander.                            |
| `bank <x> <z>`                                                              | A tile beside the bank's bankers.                                                                                                                 |
| `style <n>`                                                                 | Combat style: 0 controlled, 1 aggressive, 2 accurate, 3 defensive.                                                                                |
| `eat <hits>`                                                                | Eat when hits are at or below this.                                                                                                               |
| `sleep <percent>`                                                           | Sleep, with a sleeping bag (item 1263), when fatigue is at or above this. Without it, never.                                                      |
| `food <id> ...`                                                             | Foods, eaten in this order.                                                                                                                       |
| `item <id> <min> <target> [wear]`                                           | Carry this item. Below `min`, go to the bank. At the bank, withdraw up to `target`. `wear` equips it.                                             |
| `loot <id> ...`                                                             | Ground items to pick up inside the camp.                                                                                                          |
| `bury <id> ...`                                                             | Pick these up inside the camp, as with `loot`, and bury them.                                                                                     |
| `block <npcId> ...`                                                         | NPCs that stand in the way on this world: the server refuses a step onto their tile. Walks go round them, or attack one in the way (see Walking). |

Each setting line appears at most once, and so does each `target` server index, each `item` id and
each route. A recipe has at least one `target`, a `camp`, a `bank` and both routes, and each route
has at least one step and its `end`. Every number is from 0 to 2147483647; the `world` tick is at
least 1, `sleep` 1 to 100, `style` 0 to 3, a wall `dir` 0 to 3 and a `cmd` 1 or 2; a `usewall` or
`useobj` item is at least 1 (only `cut` takes 0, for a click); an item's `min` is at most its
`target`, and a box's min at most its max. An item a step uses, and the sleeping bag with a `sleep`
line, needs an `item` line with a `min` of at least 1. A camper refuses a recipe that breaks any of
this, or has a line it does not know (a step outside a route is one), with one of the messages
under Messages. Plutonium refuses a `world` other than `uranium`.

### Routes

Two routes: `route bank` walks from the camp to the bank, `route camp` from the bank to the camp.
Each is a list of steps, ended by `end`:

| Step                                             | Meaning                                                                                                                                                                                                                                                              |
| ------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `walk <x> <z>`                                   | Walk to this tile. Steps are at most about 12 tiles apart, so the client's own walking reaches each from the one before.                                                                                                                                             |
| `door <x> <z> <dir> <closedId>`                  | A door anyone can open, on the wall `<dir>` of tile `x z`. If the wall object there has id `closedId`, it is shut: click it ("Open") until it is not. Otherwise walk on.                                                                                             |
| `gate <x> <z> <closedId>`                        | The same for a gate or door that is scenery: if the object at `x z` has id `closedId`, click it.                                                                                                                                                                     |
| `obj <x> <z> <cmd> <landX> <landZ>`              | Click the scenery at `x z` with option `cmd` (1 or 2), until it has landed: on `landX landZ` when that is on the same floor, within a tile of it on another floor.                                                                                                   |
| `wall <x> <z> <dir> <cmd> <landX> <landZ>`       | Click the wall object at `x z` (wall `dir`) with option `cmd`, until it has landed, as for `obj`.                                                                                                                                                                    |
| `cut <x> <z> <dir> <webId> <itemId>`             | A web on the wall `<dir>` of tile `x z`. While the wall object there has id `webId`, use item `itemId` on it; `0` means click it instead, as on worlds where a web is cut by clicking it with a knife or weapon carried. The web goes and the player stays: walk on. |
| `usewall <x> <z> <dir> <itemId> <landX> <landZ>` | Use item `itemId` on the wall object at `x z` (wall `dir`) until it has landed, as for `obj`.                                                                                                                                                                        |
| `useobj <x> <z> <itemId> <landX> <landZ>`        | Use item `itemId` on the scenery at `x z` until it has landed, as for `obj`.                                                                                                                                                                                         |

## What the camper does

A tick is a server tick. The camper decides once per server update, once the client has applied
all of it, and never before the first update after it starts or logs in:

- APOS: a player position update (its `onPlayerCoord` hook) has been handled since the last
  decision, and the client's socket input has nothing left to read.
- IdleRSC, which has no hook: the camper wraps the client's socket input, counts each player
  position update read, and decides once the client finds nothing left to read.
- Plutonium, whose packets reach the script through a queue it cannot see: 20 ms after
  `on_server_tick`. The server writes a tick's packets back to back.

Every count of ticks (respawns, skips, timeouts) counts server updates. What the camper sends lands
in the next tick, whose update already shows the result, so it never waits on anything else: it
sends what is still to do every tick.

Each tick it does the first of these that applies:

1. Fighting (the client says so, or an NPC that is fighting stands on the player's tile: a client
   can say so a tick or two late): with hits at or below `eat`, it retreats by walking to its own
   tile (the server allows that from the opponent's third hit). Fighting an NPC that is not a
   target, other than a `block` NPC it attacked itself as in the way (see Walking), it breaks off:
   it walks to the tile of the current route step, or, with no route under way, the tile it hunts
   at (see Camping); to its own tile when there is neither, when the client's walking does not
   reach that tile, or when it already stands on it. Like the retreat, this walk does not go round
   `block` NPCs. Otherwise nothing. Its opponent is the NPC in view that is fighting on the
   player's tile, the lowest server index when there are several.
2. The bank window is open: banking (see Banking).
3. An option menu is open: it answers the first option. The camper talks only to bankers, and the
   first option opens the bank.
4. The combat style is not `style`: it sets it.
5. Hits at or below `eat`, and a food carried: it eats the first one in `food`'s order. With
   none carried it logs `Out of food`, once until a tick starts with food carried.
6. A `wear` item carried and not worn in any slot: it equips it. The generator offers `wear` only
   for items that can be worn.
7. A `bury` item carried: it buries it.
8. The server said the player has been standing still (a message containing "You have been
   standing here for"): it walks to the first neighbouring tile its walking reaches, trying x − 1
   to x + 1, and for each z − 1 to z + 1; with none, it goes on to the next step.
9. It needs the bank, or `route bank` is under way: within 3 tiles of the bank tile, or with
   `route bank` at its end, it talks to the nearest banker, or, with none in view, walks to the
   bank tile unless it stands on it; otherwise it follows `route bank`. Within 3 tiles of the
   bank tile, or once the bank window has been open, `route bank` is over.
10. It is not camping: it follows `route camp` (see Camping). Reaching its end, it goes on to the
    next step that same tick.
11. Loot (see Camping).
12. Hunt (see Camping).

It needs the bank when an item is below its `min`, or when the inventory is full and holds
something it would deposit. Needing the bank on `route camp` turns back onto `route bank` at once.

Bankers are NPCs 95, 224, 268, 540, 617 and 792. Nearest, everywhere, is by tiles (the larger of
the x and z distances), ties going to the target listed first, then to the lower x, z and id. An
NPC is where the server last put it, not where the client draws it on the way there. A stack is
an item the client marks stackable; Plutonium, whose API does not say, carries the list of
Uranium's stackable items.

### Banking

With the bank window open, it deposits, all in one tick, everything not worn that no `item` line
names. Once the inventory shows that, it withdraws, all in one tick, one withdrawal per item, each
item up to its `target` as far as the free slots go: a stack takes one slot, or none when one is
carried already; every item's `min` is room taken first, then the rest goes to the targets in the
recipe's order. Then it closes the bank window, and step 6 equips what has `wear`. The deposits,
or the withdrawals, still to do 3 ticks after they were sent stop it.

It stops, before withdrawing, when an item's `min` cannot be met: too few carried and in the bank
together, or no room for them.

### Following a route

A route is resumed from wherever the player is: from its step nearest the player on the same floor,
within 30 tiles, that the client's walking reaches (a step on an object or a wall: a tile from
which it can be used, by the object's size and the wall's side, once the client has been sent the
object; IdleRSC and Plutonium, which the server treats as custom clients, are sent scenery only
within 2 regions of 8 tiles). An `obj`, `wall`, `usewall` or `useobj` step's landing counts too,
and stands for the step after it. The later wins a tie, so a step the player is already past is not
done again. A route under way whose current step's tile is more than 30 tiles away is resumed again
the same way. Starting a route forgets the tiles the long-distance walker had no path to.

With no such step, the camper walks with the client's own long-distance walker (APOS's
`PathWalker`, IdleRSC's web walker, Plutonium's long paths, which open no doors) to the route's
step or landing nearest the player on the same floor that the walker has a path to; when the walker
has none, it tries the next nearest. With none left, it stops. Once it has a path, the walk is the
walker's: the camper keeps it going every tick and does not judge it. The walker never holds up a
tick: it is asked for at most one path a tick, and IdleRSC's web walker is asked for its path, which
the camper walks node by node itself, letting the walker's own handler cross only a door, ladder or
other labelled link.

Once started, a route is followed to its last step. A `walk` step is done within 2 tiles of its
tile; its walk is sent every tick until then. A step that clicks or uses an item on an object or
wall does so every tick while the object is there. A wall is the one on that side of the tile,
never another wall on the same tile. A step's item that is not carried stops it, once that step
is the current one.

When the current step cannot be reached (a `walk` step's tile, or any other step's tiles it is used
from, as for resuming, but whether or not the client has been sent the object) and the last `door`, `gate` or `cut` step before it is shut (someone shut
the door behind the player, or the web grew back, as one does 30 seconds after it is cut), that
step becomes the current one, and is done that same tick. Otherwise it stays on the step. It never
gives up on a step: from the 21st tick without getting past the furthest step it reached, it logs
the step every 20 ticks.

## Walking

Every walk the camper sends goes round the `block` NPCs in view, except the retreat in combat, the
walk a client sends itself before an attack, a pick-up or a talk, and the long-distance walker's.
Its search box spans the player and the tile walked to, grown by 16 tiles on every side, and, but
for Plutonium, whose flags cover the whole map, stays inside the area the client has loaded. With a
`block` NPC in view inside the search box, the camper finds the path itself: the stock client's
breadth-first search over the client's collision flags, as the client's own walking does, but
inside the search box and with the tile of each such NPC counted as blocked, except the player's
tile and, for a walk, the tile walked to. A tile the client has no flags for counts as blocked. A
diagonal step past one of them is not taken, as a diagonal past a wall corner is not. It sends that
path as the client sends a walk. With none of them in the search box, it walks with the client's
own walking. The path is found anew every tick the walk is sent: the NPCs move.

With no way round them, the `block` NPC in the way is the one, not fighting, on the first tile of
the path the client's own walking takes: the same search without the NPCs. When several stand
there it is the one with the lowest id, then the lowest server index. The camper attacks it, with
the bare attack and no walk, instead of walking. With no such NPC, it walks with the client's own
walking.

A click or use on a step's object or wall goes the same way. The search box spans the player and
the area it is used from, and the search goes to where the stock client walks for it: for a wall on
side 0 or 1, either tile beside it; for another wall, beside its tile; for scenery of type 2 or 3,
its tiles and the tile in front of it, by its size and turn; for other scenery, beside its tiles.
"Beside" is a tile next to the area, in x or z, with no wall between. Plutonium, whose API does not
give scenery sizes, carries them from its own object definitions. With a `block` NPC in the search
box and the player not yet there, the camper walks the way round instead of clicking, or, with no
way round, attacks the NPC in the way. Otherwise it clicks, and the client walks for the click
itself: once the player is there, with no `block` NPC in the box, or with no way round and no NPC
in the way.

## Camping

The camper camps once `route camp` has reached its last step, or, decided once per start on its
first update, when it starts inside the camp with the camp tile reachable by the client's own
walking. It stops camping when the player is outside the camp box grown by one tile. It fights only
targets inside the camp. A target is in view when the client sees an NPC with its server index and
its id. Near is x² + z² of the distance at most 212, about 14.6 tiles: the server sends the client
the NPCs whose straight-line distance, rounded down, is at most 15, so under 16 tiles, and from
that near a diagonal step cannot take an NPC out of view.

What it keeps, for each target, from every server update, asleep or not: the tick it is due back
(or none), the tile it was last seen on when it walked out of view (or none), and the ticks spent
waiting within one tile of its spawn. IdleRSC sees only what the client shows when it polls, so
updates applied between two polls count as one seen update, with the waiting count going up by
their number.

- In view and not fighting: not due, and no tile.
- In view and fighting anyone: due its respawn ticks from now, and no tile. So one that is last
  seen fighting and then leaves view is due its respawn ticks from when it was last seen.
- Leaving view while near, not fighting: it died; due its respawn ticks from now.
- Leaving view far away, not fighting: its tile is where it was last seen.
- Not in view: a tile that is near is forgotten (it is not there). When it is not due, or more
  than one tick past due, it is due its respawn ticks from now when either the player stands
  within one tile of its spawn and it has no tile, or every corner of its wander box is near:
  someone else killed it out of sight.
- The waiting count goes up by one each update while it is the hunted target, not in view, with
  the player within one tile of its spawn, and starts again from 0 whenever it is in view or
  leaves view while near, fighting or not (an assumed death does not restart it).

Logged in again after a disconnect, it forgets what it saw in view (a target gone from view then
did not die) and moves its tick count on by the time it was logged out, so due ticks stay right.
APOS, which has no login hook, takes a gap of more than 5 seconds between updates for a
reconnect.

The hunted target is the one the last Hunt chose; it stays the hunted target on ticks the camper
does something else, and is forgotten when camping stops. Due within the next 5 ticks means due 0
to 5 ticks from now.

Loot: skipped while the hunted target is due within the next 5 ticks and the player
stands within one tile of its spawn. Otherwise an item that `loot` or `bury` names and the
inventory has room for (a stack already carried has room): one on the player's own tile first,
else the nearest inside the camp within 4 tiles whose tile, or a tile beside it in x or z with no
wall between, the client's walking reaches. It picks it up, walking to the tile beside it when its
own tile cannot be reached, as the stock client does. Walks, pick-ups and attacks are sent every
tick: the server takes the latest.

Hunt: each target not skipped gets a time until it can be fought, in ticks, with tiles as the
walking time:

- In view, not fighting, inside the camp and reached by the client's walking: the tiles to it.
- Due: the larger of the tiles to its spawn and the ticks until it is due (0 once past due), plus 6
  when the walk takes more than 5 ticks longer than the wait.
- Neither: the tiles to its tile, or, with none, to its spawn, plus 6, since it may not be there.

It hunts the lowest, ties going to the target listed first, but keeps the hunted target while
that is less than 2 ticks worse, and keeps it whatever else when it is due within the next 5 ticks
and the player stands within one tile of its spawn. A target in view and not fighting is attacked:
more than 5 tiles away it walks there first. Otherwise it waits on its spawn tile, or on its tile
when it is not due and has one; standing there, it sends no walk. Once the waiting count passes
its respawn ticks, it walks instead to the corner of the target's wander box farthest from the
spawn that the client's walking reaches (the first in min x, min z; max x, min z; min x, max z;
max x, max z order on a tie), or with none to the spawn, and waits there until the count starts
again: the target is alive somewhere in its box, out of view.

While it hunts a target that is not in view and it is not fighting, it sends an attack on the
target's server index once a tick, the bare attack with no walk of its own, so the attack lands the
tick it comes back; not on a tick its walk there attacked a `block` NPC in the way. Any walk that
tick is sent first: a walk cancels an attack sent before it, while an attack sent after a walk
keeps the walk.

Sleep: on a tick it sends an attack on a target, with fatigue at `sleep` percent or more, a
sleeping bag (item 1263) carried and the player not asleep, it uses the bag before the attack. A
walk that tick may go on either side of it: the bag keeps the walk and drops only an attack sent
before it. Experience comes from kills and stops at 100% fatigue, so fatigue matters only before a
fight. Being asleep stops nothing: the camper goes on deciding every tick as above, and the client
answers the sleep word once fatigue is down to 0. The server lets a sleeping player walk and
attack, and a player who attacks stays asleep through the fight; only an NPC attacking the player
wakes it, without the rest. The generator proposes 50. APOS runs no script while the player is
asleep, so its camper then decides on each update when the next one starts (its `onPlayerCoord`
hook), a tick late. Plutonium runs no script while the player is asleep either, and its camper
cannot change that: it decides nothing until it wakes, about 11 ticks from 50%, and in that time
neither eats nor retreats.

A spawn too far away for the client's walking to plan a path to (outside the area of the map the
client has loaded, or for Plutonium outside its local path-finding window) is walked toward: of
the tiles on the straight line from the spawn to the player, the one nearest the spawn that is
inside the camp and that the client's walking reaches. With no such tile, or when the spawn is
near enough but its tile cannot be reached, the spawn is skipped for 50 ticks, logged each time:
a door may have been shut.

A camper that dies stops. Started again where it respawns, it walks back as from anywhere else.

## Stopping

A camper stops only for these, and says why; stopping also turns the client's automatic login
off:

| Message                                                             | Why                                                                        |
| ------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| `line <n>: <what is wrong>`                                         | The recipe breaks a rule above (see Messages).                             |
| `Died: start it again`                                              | It died.                                                                   |
| `Out of <item>: <carried> carried, <banked> in the bank, min <min>` | The bank cannot make up an item's `min`.                                   |
| `No room for <item>`                                                | The inventory has no room for an item's `min`.                             |
| `The bank did not take <deposits or withdrawals>`                   | Deposits or withdrawals still to do 3 ticks after they were sent.          |
| `Route <name> step <n> needs <item>`                                | The current step's item is not carried.                                    |
| `No way to the route`                                               | The long-distance walker reaches none of the route's tiles, or cannot run. |
| `Error: <what>`                                                     | Anything the camper did not expect.                                        |

## Messages

Every camper writes these, word for word. A step number counts from 1, and `<step>` is the step's
line as the recipe has it, without its comment.

| Message                                                            | When                                                                                           |
| ------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------- |
| `Camping <n> spawns around <x>,<z>; banking at <x>,<z>`            | It starts.                                                                                     |
| `Out of food`                                                      | Step 5, once until a tick starts with food.                                                    |
| `Route <name>: from step <n> of <total>`                           | A route is resumed.                                                                            |
| `Route <name>: too far to resume, walking to <x>,<z>`              | The long-distance walker takes over.                                                           |
| `Route <name>: done`                                               | A route reaches its end.                                                                       |
| `Route <name> step <n> (<step>) for <t> ticks`                     | A step is taking long, every 20 ticks.                                                         |
| `No path to #<index>'s spawn at <x>,<z>; skipping it for 50 ticks` | A spawn is skipped.                                                                            |
| `No tick hook: deciding every <tickMs> ms`                         | IdleRSC only, when its hook cannot be set up.                                                  |
| `No saved recipe is called <name>`                                 | The script's parameters name no saved recipe (APOS and IdleRSC).                               |
| `No web walker path search: walking with walkTowards`              | IdleRSC only, when it cannot reach its web walker's path search; the walk then holds up ticks. |
| `No sleep hook: not sleeping`                                      | IdleRSC only, without its tick hook and with a client that does not answer the sleep word.     |

What is wrong with a recipe, after `line <n>: `:

| Text                                                         | When                                                                                                                                                                                                                 |
| ------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `unknown instruction <word>`                                 | A line it does not know, a step or `end` outside a route, or a route other than `bank` or `camp` (`unknown instruction route`).                                                                                      |
| `<word> takes <k> numbers`                                   | Too few or too many, as `walk takes 2 numbers`, `world takes 1 numbers` (after its name), `end takes 0 numbers`, `item takes 3 numbers` (`wear` may follow). A `food`, `loot`, `bury` or `block` line may list none. |
| `<text> is not a number`                                     | Not digits only (no sign) from 0 to 2147483647.                                                                                                                                                                      |
| `<word> is <low> to <high>`                                  | Out of its range: `style is 0 to 3`, `dir is 0 to 3`, `cmd is 1 to 2`, `world is 1 to 2147483647` (its tick), `sleep is 1 to 100`, `itemId is 1 to 2147483647` (a `usewall` or `useobj` item).                       |
| `<word> is already on line <m>`                              | A setting, target index, item id or route given twice; `<word>` is the line's first word (`target`, `item`, `route`).                                                                                                |
| `min is above target`                                        | An `item` whose `min` is above its `target`.                                                                                                                                                                         |
| `the box's min is above its max`                             | A `target` or `camp` box.                                                                                                                                                                                            |
| `no <word> line`                                             | A required line is missing (`no camp line`, `no route bank line`); `<n>` is then the last line that is not blank.                                                                                                    |
| `route <name> has no steps`                                  | A route with none.                                                                                                                                                                                                   |
| `route <name> has no end`                                    | The recipe ends inside a route.                                                                                                                                                                                      |
| `<step word> needs an item line for <id> with min 1 or more` | A step's item, or `sleep needs an item line for 1263 with min 1 or more`.                                                                                                                                            |
| `world <name> is not uranium`                                | Plutonium only.                                                                                                                                                                                                      |

## Example

Cows east of Lumbridge, as the generator writes them:

```
# 3x Cow @ 98, 607
# Banks at Draynor bank. Generated by rsc-map-renderer; the format is in RECIPE.md.
world uranium 640
target 1054 6 98 607 158 93 602 103 612   # cow, level 8
target 1055 6 101 607 158 96 602 106 612   # cow, level 8
target 1058 6 102 614 158 97 609 107 619   # cow, level 8
camp 98 607 93 602 107 619
bank 220 635   # Draynor bank
style 1
eat 7
food 373   # Lobster
item 373 1 10   # Lobster
loot 20   # Bones
bury 20   # Bones
route bank
  walk 98 607
  walk 104 619
  gate 105 619 60
  walk 105 620
  walk 117 625
  walk 128 626
  walk 136 634
  walk 148 634
  walk 160 634
  walk 172 632
  walk 184 631
  walk 196 630
  walk 208 628
  walk 220 634
  walk 220 635
end
route camp
  walk 220 635
  walk 209 629
  walk 197 624
  walk 185 624
  walk 173 628
  walk 161 631
  walk 149 634
  walk 137 634
  walk 128 627
  walk 118 625
  walk 106 620
  walk 105 619
  gate 105 619 60
  walk 104 619
  walk 98 607
end
```

A route through the Edgeville dungeon climbs with `obj`:

```
  walk 216 3299
  obj 215 3300 1 216 467   # CLIMB@215_3300
```
