package orsc.graphics.three;

/**
 * Per-tile collision bitmask (the byte the engine stores per tile). Bits 0-3 are the four cardinal
 * wall edges, 4-6 the server's three full-block sources, 7 the scenery-object footprint.
 */
public class CollisionFlag {

	// Cardinal wall edges — one bit per direction.
	static final int WALL_NORTH = 1 << 0; // 0x01
	static final int WALL_EAST  = 1 << 1; // 0x02
	static final int WALL_SOUTH = 1 << 2; // 0x04
	static final int WALL_WEST  = 1 << 3; // 0x08

	private static final int WALL_NORTH_EAST = WALL_NORTH | WALL_EAST;
	private static final int WALL_NORTH_WEST = WALL_NORTH | WALL_WEST;
	private static final int WALL_SOUTH_EAST = WALL_SOUTH | WALL_EAST;
	private static final int WALL_SOUTH_WEST = WALL_SOUTH | WALL_WEST;

	// Full-tile blocks — the server's three independent block sources.
	static final int FULL_BLOCK_A = 1 << 4; // 0x10
	static final int FULL_BLOCK_B = 1 << 5; // 0x20
	static final int FULL_BLOCK_C = 1 << 6; // 0x40
	private static final int FULL_BLOCK = FULL_BLOCK_A | FULL_BLOCK_B | FULL_BLOCK_C;

	static final int OBJECT = 1 << 7; // 0x80

	static final int NORTH_BLOCKED = FULL_BLOCK | WALL_NORTH;
	static final int EAST_BLOCKED  = FULL_BLOCK | WALL_EAST;
	static final int SOUTH_BLOCKED = FULL_BLOCK | WALL_SOUTH;
	static final int WEST_BLOCKED  = FULL_BLOCK | WALL_WEST;

	static final int NORTH_EAST_BLOCKED = FULL_BLOCK | WALL_NORTH_EAST;
	static final int NORTH_WEST_BLOCKED = FULL_BLOCK | WALL_NORTH_WEST;
	static final int SOUTH_EAST_BLOCKED = FULL_BLOCK | WALL_SOUTH_EAST;
	static final int SOUTH_WEST_BLOCKED = FULL_BLOCK | WALL_SOUTH_WEST;

	// "Source" side of a block — the wall bits rotated one position (the server
	// encodes which side an entity approaches a blocked edge from).
	static final int SOURCE_SOUTH = 1 << 0; // 0x01
	static final int SOURCE_WEST  = 1 << 1; // 0x02
	static final int SOURCE_NORTH = 1 << 2; // 0x04
	static final int SOURCE_EAST  = 1 << 3; // 0x08

	static final int SOURCE_NORTH_EAST = SOURCE_NORTH | SOURCE_EAST;
	static final int SOURCE_NORTH_WEST = SOURCE_NORTH | SOURCE_WEST;
	static final int SOURCE_SOUTH_EAST = SOURCE_SOUTH | SOURCE_EAST;
	static final int SOURCE_SOUTH_WEST = SOURCE_SOUTH | SOURCE_WEST;
}
