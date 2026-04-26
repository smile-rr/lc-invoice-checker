package com.lc.checker.infra.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Lowercase-hex SHA-256 helper. SHA-256 is required on every JRE, so this never throws. */
public final class Sha256 {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Sha256() {}

    public static String hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] out = new char[digest.length * 2];
            for (int i = 0, j = 0; i < digest.length; i++) {
                int v = digest[i] & 0xFF;
                out[j++] = HEX[v >>> 4];
                out[j++] = HEX[v & 0x0F];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JRE", e);
        }
    }

    public static String hex(String s) {
        return hex(s.getBytes(StandardCharsets.UTF_8));
    }
}
