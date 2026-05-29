package io.casehub.connectors.webhook;

import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared HMAC utilities for webhook signature verification.
 *
 * <p>All comparison uses {@link MessageDigest#isEqual(byte[], byte[])} (constant-time)
 * to prevent timing attacks.
 */
final class SigHelper {

    private SigHelper() {}

    /**
     * Compute HMAC with the given JCA algorithm name.
     *
     * @param algorithm JCA name, e.g. {@code "HmacSHA256"} or {@code "HmacSHA1"}
     * @param keyBytes  the HMAC key
     * @param dataBytes the data to sign
     * @return the raw HMAC bytes
     */
    static byte[] hmac(final String algorithm, final byte[] keyBytes, final byte[] dataBytes) {
        try {
            final Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(keyBytes, algorithm));
            return mac.doFinal(dataBytes);
        } catch (final Exception e) {
            throw new IllegalStateException("HMAC computation failed: " + algorithm, e);
        }
    }

    /**
     * Constant-time comparison of two byte arrays.
     * Safe against timing attacks.
     */
    static boolean constantTimeEquals(final byte[] a, final byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    /** Hex-encode a byte array. */
    static String toHex(final byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
