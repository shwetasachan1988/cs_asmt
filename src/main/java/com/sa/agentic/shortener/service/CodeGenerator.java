package com.sa.agentic.shortener.service;

import com.sa.agentic.shortener.ShortenerProperties;
import java.math.BigInteger;
import org.springframework.stereotype.Component;

/**
 * Collision-free short codes: a DB sequence value is multiplied by a constant coprime to 62^length
 * (a bijection on [0, 62^length)) and Base62 encoded, so codes are unique by construction yet
 * not sequentially guessable. No retry-on-collision loop is needed.
 */
@Component
public class CodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    /** Odd and not divisible by 31 => coprime to 62^n. */
    private static final BigInteger MULTIPLIER = BigInteger.valueOf(2_654_435_761L);

    private final int length;
    private final BigInteger space;

    public CodeGenerator(ShortenerProperties props) {
        this.length = props.codeLength();
        this.space = BigInteger.valueOf(62).pow(length);
    }

    public String fromId(long id) {
        if (id < 0 || BigInteger.valueOf(id).compareTo(space) >= 0) {
            throw new IllegalArgumentException("id outside code space: " + id);
        }
        BigInteger scrambled = BigInteger.valueOf(id).multiply(MULTIPLIER).mod(space);
        char[] out = new char[length];
        for (int i = length - 1; i >= 0; i--) {
            BigInteger[] qr = scrambled.divideAndRemainder(BigInteger.valueOf(62));
            out[i] = ALPHABET.charAt(qr[1].intValue());
            scrambled = qr[0];
        }
        return new String(out);
    }
}
