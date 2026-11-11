package openrsc.livemap;

import openrsc.gamedata.ServerData;
import openrsc.livemap.assets.MapNav;
import openrsc.livemap.assets.WorldAssets;
import openrsc.livemap.auth.AuthPolicy;
import openrsc.livemap.auth.PasswordAuth;
import openrsc.livemap.live.AnonymousView;
import openrsc.livemap.live.BotLiveEncoder;
import openrsc.livemap.live.BotLiveStore;
import openrsc.livemap.live.LiveOverlay;
import openrsc.livemap.live.LiveRoster;
import openrsc.livemap.live.SeedHistory;
import openrsc.livemap.live.StreamOps;
import openrsc.livemap.planner.PlannerAccounts;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * The live map's web endpoints and stream, for a Spring application to import: the world assets its
 * 2D and 3D maps draw, the live feed of the bots and what they see at {@code /api/ws}, the route
 * planner at {@code /api/pathfinder}, and logging in at {@code /api/auth}. The feed's threads start with
 * the context.
 *
 * <p>The application provides a {@link ServerData}, a {@link MapNav}, a {@link WorldAssets}, a
 * {@link PasswordAuth}, a {@link LiveRoster}, an {@link AnonymousView} and a Jackson 3
 * {@code ObjectMapper} bean (Spring Boot's auto-configuration provides the last), and parks each bot's
 * record in the {@link BotLiveStore} as its session ticks, {@link BotLiveEncoder} encoding it. It may
 * add an {@link AuthPolicy}, {@link LiveOverlay}s, a {@link SeedHistory}, {@link StreamOps} and the
 * {@link PlannerAccounts} the route planner plans as.
 */
@Configuration
@ComponentScan(excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class))
public class LiveMap {}
