package kr.pmsx.common;

import java.security.SecureRandom;

/** ULID(26자, Crockford Base32) 생성기. 시간순 정렬 가능한 대리 ID. */
public final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {
    }

    public static String next() {
        long time = System.currentTimeMillis();
        byte[] rnd = new byte[10];
        RANDOM.nextBytes(rnd);
        char[] out = new char[26];
        // 48비트 시간 → 10문자
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        // 80비트 난수 → 16문자 (40비트씩 두 덩어리)
        long hi = 0;
        for (int i = 0; i < 5; i++) {
            hi = (hi << 8) | (rnd[i] & 0xFF);
        }
        long lo = 0;
        for (int i = 5; i < 10; i++) {
            lo = (lo << 8) | (rnd[i] & 0xFF);
        }
        for (int i = 17; i >= 10; i--) {
            out[i] = ALPHABET[(int) (hi & 31)];
            hi >>>= 5;
        }
        for (int i = 25; i >= 18; i--) {
            out[i] = ALPHABET[(int) (lo & 31)];
            lo >>>= 5;
        }
        return new String(out);
    }
}
