package openrsc.bot.core.login;

import java.math.BigInteger;

public final class RsaCrypto {

    private RsaCrypto() {}

    public static byte[] encrypt(byte[] plaintext, BigInteger exponent, BigInteger modulus) {
        return new BigInteger(plaintext).modPow(exponent, modulus).toByteArray();
    }
}
