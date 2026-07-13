package com.example.flags.log;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RecordingSink implements ErrorSink {
    public record Entry(ErrorKind kind, String flag, String environment, String tenant, String detail) {}
    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void log(ErrorKind kind, String flag, String environment, String tenant, String detail) {
        entries.add(new Entry(kind, flag, environment, tenant, detail));
    }

    public List<Entry> entries() { return Collections.unmodifiableList(entries); }
    public long count(ErrorKind kind) { return entries.stream().filter(e -> e.kind() == kind).count(); }
}
