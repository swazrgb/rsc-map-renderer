package openrsc.livemap.panel;

import org.jspecify.annotations.Nullable;

/** Each bot's panel record, as the application builds it, such as a {@link BotPanelDto}. */
public interface BotPanels {

    /** The bot's panel; null when there is no such bot, or a viewer of that kind may not see it. */
    @Nullable
    Record panel(String username, boolean authed);
}
