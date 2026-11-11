package openrsc.sessionproxy;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * The proxy's own RSA key, which clients encrypt their login with when the proxy writes its own
 * ({@link ProxyConfig#writingOwnLogins}). A custom client is handed it in the configs packet; an authentic client has
 * its server's key built in, so it is changed to carry this one's {@link #modulus} and {@link #exponent}. The proxy
 * then reads the password, and an authentic client's ISAAC keys. Kept in a file so that the key a client was changed
 * to stays valid.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/Crypto.java#L94-L119">Crypto.generateRSAKeys</a>
 */
public final class ProxyKey {

    /** The file, in the proxy's data folder, that holds the key. */
    public static final String FILE = "authentic-client-key.pem";

    // The size of key the server makes for itself, which the login block fits under.
    private static final int BITS = 512;
    private static final String PEM_START = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_END = "-----END PRIVATE KEY-----";

    private final RSAPrivateCrtKey key;

    private ProxyKey(RSAPrivateCrtKey key) {
        this.key = key;
    }

    /** The key in {@code dir}, made and written there first when there is none. */
    public static ProxyKey load(Path dir) throws IOException {
        Path file = dir.resolve(FILE);
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(dir);
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(BITS);
                byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();
                String pem = PEM_START + "\n"
                        + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded) + "\n" + PEM_END + "\n";
                Files.writeString(file, pem, StandardCharsets.US_ASCII);
            }
            String pem = Files.readString(file, StandardCharsets.US_ASCII)
                    .replace(PEM_START, "")
                    .replace(PEM_END, "")
                    .replaceAll("\\s", "");
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            return new ProxyKey((RSAPrivateCrtKey) rsa.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem))));
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IOException(file + " does not hold an RSA private key: " + e.getMessage(), e);
        }
    }

    public BigInteger modulus() {
        return key.getModulus();
    }

    public BigInteger exponent() {
        return key.getPublicExponent();
    }

    /**
     * The block a client encrypted with this key, as the server reads it: a signed number, raised to the private
     * exponent, and written back without padding.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/Crypto.java#L88-L92">Crypto.decryptRSA</a>
     */
    public byte[] decrypt(byte[] block) {
        return new BigInteger(block)
                .modPow(key.getPrivateExponent(), key.getModulus())
                .toByteArray();
    }
}
