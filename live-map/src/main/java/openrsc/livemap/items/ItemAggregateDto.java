package openrsc.livemap.items;

import java.util.List;

public record ItemAggregateDto(
        int id, String name, long total, long inInventory, long inBank, double perHour, List<ItemHolderDto> holders) {

    public ItemAggregateDto {
        perHour = PerHour.shown(perHour);
    }
}
