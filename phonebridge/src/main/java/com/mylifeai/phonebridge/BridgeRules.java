package com.mylifeai.phonebridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure rules shared by import, notifications and exports. No Android dependency. */
public final class BridgeRules {
    public static final long WINDOW_MS = 7L * 24 * 60 * 60 * 1000;
    public static final String FILE_NAME = "업무브리핑_휴대폰_최신.json";
    private static final Pattern OTP = Pattern.compile(
        "(?is)(인증\\s*번호|일회용\\s*(번호|비밀번호)|verification\\s*code|one[- ]time|\\botp\\b).{0,60}\\d{4,8}|\\d{4,8}.{0,40}(인증\\s*번호|verification\\s*code)");
    private BridgeRules() {}
    public static boolean isCredential(String value) { return value != null && OTP.matcher(value).find(); }
    public static String sourceForPackage(String name) {
        if ("com.kakao.talk".equals(name)) return "kakao_notification";
        if ("com.samsung.android.messaging".equals(name) || "com.google.android.apps.messaging".equals(name)) return "sms_notification";
        return null;
    }
    public static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String decodeText(byte[] bytes) {
        if (bytes.length >= 2 && bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe)
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        if (bytes.length >= 2 && bytes[0] == (byte)0xfe && bytes[1] == (byte)0xff)
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        return new String(bytes, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "");
    }
    public static String callType(int type) {
        switch (type) {
            case 1: return "incoming";
            case 2: return "outgoing";
            case 3: return "missed";
            case 5: return "rejected";
            case 6: return "blocked";
            default: return "other";
        }
    }
}
