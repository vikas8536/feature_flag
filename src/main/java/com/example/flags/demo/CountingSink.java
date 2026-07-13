package com.example.flags.demo;

import com.example.flags.log.ErrorKind;
import com.example.flags.log.ErrorSink;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An {@link ErrorSink} that counts logs per {@link ErrorKind} and keeps the first detail seen
 * for each kind.
 *
 * <p>O(1) per log, unlike {@code RecordingSink}, whose {@code CopyOnWriteArrayList} copies its
 * whole backing array on every add. The demo's reader threads spin through error windows fast
 * enough for that to matter.
 */
public final class CountingSink implements ErrorSink {

    private final Map<ErrorKind, AtomicLong> counts = new EnumMap<>(ErrorKind.class);
    private final Map<ErrorKind, String> firstDetail = new ConcurrentHashMap<>();

    public CountingSink() {
        // Fully populated here and never structurally modified again, so concurrent get() is safe.
        for (ErrorKind kind : ErrorKind.values()) counts.put(kind, new AtomicLong());
    }

    @Override
    public void log(ErrorKind kind, String flag, String environment, String tenant, String detail) {
        counts.get(kind).incrementAndGet();
        firstDetail.putIfAbsent(kind, flag + ": " + detail);
    }

    public long count(ErrorKind kind) {
        return counts.get(kind).get();
    }

    /** The first detail logged for this kind, or null if it was never logged. */
    public String firstDetail(ErrorKind kind) {
        return firstDetail.get(kind);
    }
}
