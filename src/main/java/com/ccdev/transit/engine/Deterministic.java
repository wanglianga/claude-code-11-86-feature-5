package com.ccdev.transit.engine;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 确定性随机原语：所有分配/抽样均来自 SHA-256(seed::key)，
 * 不依赖 Math.random / 系统时间，保证登记后可离线复算。
 */
public final class Deterministic {

    private Deterministic() {
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 返回 [0,1) 的确定性伪随机双精度数（取前 16 个 hex 位，64 bit）。 */
    public static double uniform01(String seed, String key) {
        String hex = sha256Hex(seed + "::" + key).substring(0, 16);
        BigInteger v = new BigInteger(hex, 16);
        return v.doubleValue() / Math.pow(2, 64);
    }
}
