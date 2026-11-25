package com.shiptrack.api.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Feishu custom-bot signature, exactly as §7.5 fixes it:
 *
 * <pre>
 * string_to_sign = timestamp + "\n" + FEISHU_WEBHOOK_SECRET
 * sign = Base64(HMAC-SHA256(key = string_to_sign, message = ""))
 * </pre>
 */
public final class FeishuSigner {

    private FeishuSigner() {
    }

    public static String sign(String timestamp, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    (timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", error);
        }
    }
}
