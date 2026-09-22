package com.github.lisama569.chainindexerjava.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 内容摘要工具：用确定性 SHA-256 十六进制串模拟链上哈希。 */
public final class HashUtils {

    public static final String ZERO_HASH = "0".repeat(64);

    private HashUtils() {
    }

    /** 对字符串内容计算 SHA-256，输出 64 位小写十六进制。 */
    public static String sha256Hex(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
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

    /** 生成短摘要前缀，便于日志中人工辨认。 */
    public static String shortHash(String hash) {
        if (hash == null) {
            return "null";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12) + "..";
    }
}
