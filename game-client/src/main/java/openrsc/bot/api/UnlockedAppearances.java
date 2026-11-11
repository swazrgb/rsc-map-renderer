package openrsc.bot.api;

public record UnlockedAppearances(
        boolean[] hairStyles,
        boolean[] bodyTypes,
        boolean[] skinColours,
        boolean[] hairColours,
        boolean[] topColours,
        boolean[] bottomColours) {}
