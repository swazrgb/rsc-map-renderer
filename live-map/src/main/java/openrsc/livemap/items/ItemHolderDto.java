package openrsc.livemap.items;

public record ItemHolderDto(String username, int inventory, int bank, boolean equipped, double perHour) {

    public ItemHolderDto {
        perHour = PerHour.shown(perHour);
    }
}
