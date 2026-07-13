package com.example.flags.eval;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Bucketer {
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    });

    private Bucketer() {}

    // ponytail: SHA-256 ~500ns/eval; swap for inlined murmur3_32 if hashing shows in a profile.
    public static int bucket(String salt, String tenant, String subject) {
        MessageDigest md = SHA256.get();
        md.reset();
        byte[] digest = md.digest((salt + ":" + tenant + ":" + subject).getBytes(StandardCharsets.UTF_8));
        long h = ByteBuffer.wrap(digest, 0, 8).getLong();   // first 8 bytes, big-endian
        return (int) Long.remainderUnsigned(h, 100);
    }

    public static boolean inRollout(String salt, String tenant, String subject, int percentage) {
        return bucket(salt, tenant, subject) < percentage;
    }
}
