package openrsc.livemap.panel;

import java.util.List;
import openrsc.livemap.stream.Parts;
import openrsc.livemap.stream.TopicException;
import openrsc.livemap.stream.TopicHub;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The {@code bot} topic ({@code user}): the panel record of the bot the viewer selected, read from the application's
 * {@link BotPanels}, its skills one record each. What the map's stream already sends of the bot is left out. Nothing
 * is registered when the application has no panels.
 */
@Component
public class BotPanelTopic {

    private static final String TOPIC = "bot";

    private final TopicHub topics;

    public BotPanelTopic(ObjectProvider<BotPanels> panels, TopicHub topics) {
        this.topics = topics;
        BotPanels source = panels.getIfAvailable();
        if (source == null) {
            return;
        }
        topics.polled(TOPIC, TopicHub.Access.ANYONE, TopicHub.BACKSTOP_MS, List.of("user"), q -> {
            Record dto = source.panel(q.require("user"), q.authed());
            if (dto == null) {
                throw new TopicException(404, null);
            }
            return Parts.of(dto)
                    .drop(
                            "username",
                            "description",
                            "serverTick",
                            "position",
                            "fatiguePercent",
                            "sleeping",
                            "inCombat",
                            "bankScreenOpen",
                            "shopScreenOpen",
                            "counts",
                            "cache")
                    .map("skills")
                    .toMap();
        });
    }

    /** Reads the bot's panel again for the viewers who have it open; cheap enough for every tick of its session. */
    public void touch(String username) {
        topics.touch(TOPIC, q -> username.equals(q.arg("user")));
    }
}
