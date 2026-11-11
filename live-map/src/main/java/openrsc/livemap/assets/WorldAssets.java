package openrsc.livemap.assets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import openrsc.bot.api.Nullable;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.landscape.LandscapeSource;
import org.slf4j.Logger;

/**
 * What the world's map assets are baked from, and where to.
 *
 * @param clientCache the OpenRSC client's cache folder, which the models and textures are read from
 * @param bakeDir where the 3D world and the scenery atlas are baked, kept across restarts; one per world
 * @param landscapeFile an {@code .orsc} landscape in place of the world's own; null for its own
 */
public record WorldAssets(
        Path clientCache,
        Path bakeDir,
        ServerConf conf,
        @Nullable Path landscapeFile) {

    // The renderer the bakes run on exits the JVM when the client cache is missing.
    boolean canBake(Logger log, String what) {
        if (Files.isDirectory(clientCache)) {
            return true;
        }
        log.error("no client cache at {}: the {} is not baked, and stays unavailable", clientCache, what);
        return false;
    }

    LandscapeSource openLandscape(WorldProfile world) throws IOException {
        return landscapeFile != null ? LandscapeSource.fromOrsc(landscapeFile) : world.openLandscape(conf);
    }
}
