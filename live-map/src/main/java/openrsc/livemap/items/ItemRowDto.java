package openrsc.livemap.items;

/** One row of the items list: what it shows of an item, its holders counted rather than listed. */
public record ItemRowDto(int id, String name, long total, long inInventory, long inBank, double perHour, int bots) {

    static ItemRowDto of(ItemAggregateDto a) {
        return new ItemRowDto(
                a.id(),
                a.name(),
                a.total(),
                a.inInventory(),
                a.inBank(),
                a.perHour(),
                a.holders().size());
    }
}
