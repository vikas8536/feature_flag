package com.example.flags.log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class RateLimitedStderrSink implements ErrorSink {
    private final long windowMillis;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Long> lastLogged = new ConcurrentHashMap<>();

    public RateLimitedStderrSink() { this(60_000, System::currentTimeMillis); }

    public RateLimitedStderrSink(long windowMillis, LongSupplier clock) {
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    // visible for tests
    boolean shouldLog(ErrorKind kind, String flag) {
        String key = kind + ":" + flag;
        long now = clock.getAsLong();
        boolean[] won = new boolean[1];
        lastLogged.compute(key, (k, v) -> {
            if (v != null && now - v < windowMillis) { won[0] = false; return v; }
            won[0] = true; return now;
        });
        return won[0];
    }

    @Override
    public void log(ErrorKind kind, String flag, String environment, String tenant, String detail) {
        if (!shouldLog(kind, flag)) return;
        System.err.println("{\"level\":\"error\",\"component\":\"feature-flags\",\"kind\":\"" + kind
                + "\",\"flag\":\"" + flag + "\",\"env\":\"" + environment
                + "\",\"tenant\":\"" + tenant + "\",\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }
}
