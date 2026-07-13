package com.example.flags.log;

/** Contract: implementations MUST be thread-safe and non-blocking. */
public interface ErrorSink {
    void log(ErrorKind kind, String flag, String environment, String tenant, String detail);
}
