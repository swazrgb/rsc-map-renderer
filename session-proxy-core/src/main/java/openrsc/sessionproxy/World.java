package openrsc.sessionproxy;

import java.math.BigInteger;
import java.util.Locale;

/**
 * The two OpenRSC worlds that allow bots, and the only ones the proxy holds sessions on: each one's address, the name
 * its configs packet gives, and the RSA key it encrypts logins with, which every OpenRSC world shares.
 */
public enum World {
    URANIUM("uranium", "uranium", "RSC Uranium", 43235),
    COLESLAW("coleslaw", "rsccoleslaw", "RSC Coleslaw", 43599);

    private static final String HOST = "game.openrsc.com";
    private static final BigInteger MODULUS = new BigInteger(
            "7112866275597968156550007489163685737528267584779959617759901583041864787078477876689003422509099353805015"
                    + "177703670715380710894892460637136582066351659813");
    private static final BigInteger EXPONENT = BigInteger.valueOf(65537);

    private final String setting;
    private final String conf;
    private final String serverName;
    private final int port;

    World(String setting, String conf, String serverName, int port) {
        this.setting = setting;
        this.conf = conf;
        this.serverName = serverName;
        this.port = port;
    }

    /** The name the settings file gives the world: {@code uranium} or {@code coleslaw}. */
    public String setting() {
        return setting;
    }

    /** The server conf the world runs, without {@code .conf}: its game data's. */
    public String conf() {
        return conf;
    }

    /** The world's server, as {@code host:port}. */
    public String address() {
        return HOST + ":" + port;
    }

    ServerIdentity identity() {
        return new ServerIdentity(serverName, HOST, port, MODULUS, EXPONENT);
    }

    /**
     * The world the settings file names.
     *
     * @throws IllegalArgumentException for any other name
     */
    public static World named(String setting) {
        String wanted = setting.strip().toLowerCase(Locale.ROOT);
        for (World world : values()) {
            if (world.setting.equals(wanted)) {
                return world;
            }
        }
        throw new IllegalArgumentException("world must be uranium or coleslaw, got " + setting);
    }

    /**
     * The world whose server conf is {@code conf}.
     *
     * @throws IllegalArgumentException for any other conf
     */
    public static World ofConf(String conf) {
        for (World world : values()) {
            if (world.conf.equals(conf)) {
                return world;
            }
        }
        throw new IllegalArgumentException("the session proxy runs only on Uranium and Coleslaw, not " + conf);
    }
}
