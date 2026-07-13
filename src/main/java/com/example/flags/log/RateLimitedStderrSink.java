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
        Long prev = lastLogged.get(key);
        if (prev != null && now - prev < windowMillis) return false;
        return lastLogged.compute(key, (k, v) -> (v != null && now - v < windowMillis) ? v : now) == now;
    }

    @Override
    public void log(ErrorKind kind, String flag, String environment, String tenant, String detail) {
        if (!shouldLog(kind, flag)) return;
        System.err.println("{\"level\":\"error\",\"component\":\"feature-flags\",\"kind\":\"" + kind
                + "\",\"flag\":\"" + flag + "\",\"env\":\"" + environment
                + "\",\"tenant\":\"" + tenant + "\",\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }
}
