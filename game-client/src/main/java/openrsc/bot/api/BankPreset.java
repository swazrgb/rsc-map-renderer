package openrsc.bot.api;

import java.util.List;
import openrsc.bot.api.container.InventorySlot;

public record BankPreset(int slot, List<InventorySlot> inventory, List<InventorySlot> equipment) {}
