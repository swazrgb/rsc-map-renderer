package openrsc.livemap.live;

import openrsc.livemap.dto.BotLiveDto;

/** What a viewer who has not logged in is shown of the bots: the application decides, the feed applies it. */
public interface AnonymousView {

    /** Whether an anonymous viewer is sent this bot, and what it sees. */
    boolean shows(BotLiveDto bot);

    /** Whether an anonymous viewer may watch the bot of this name for its detail. */
    boolean showsDetail(String username);
}
