package com.example.flags.eval;

final class ValueCompare {
    private ValueCompare() {}

    private static boolean isIntegral(Object o) {
        return o instanceof Byte || o instanceof Short || o instanceof Integer || o instanceof Long;
    }

    static boolean numericEquals(Object a, Object b) {
        Integer c = numericCompare(a, b);
        return c != null && c == 0;
    }

    static Integer numericCompare(Object a, Object b) {
        if (!(a instanceof Number na) || !(b instanceof Number nb)) return null;
        if (isIntegral(a) && isIntegral(b)) return Long.compare(na.longValue(), nb.longValue());
        return Double.compare(na.doubleValue(), nb.doubleValue());
    }
}
