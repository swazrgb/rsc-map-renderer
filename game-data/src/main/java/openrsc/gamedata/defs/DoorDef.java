package openrsc.gamedata.defs;

/**
 * A boundary: a wall, fence, door or window on a tile's edge. {@code nonBlocking} is the server's
 * {@code unknown} field; see {@link DoorDefs#collides}.
 */
public record DoorDef(String name, int doorType, int nonBlocking, String command1, String command2) {}
