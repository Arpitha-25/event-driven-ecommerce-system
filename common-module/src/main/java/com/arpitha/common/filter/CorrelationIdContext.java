package com.arpitha.common.filter;

public class CorrelationIdContext {

    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    public static String getCorrelationId() {
        return CORRELATION_ID.get();
    }

    public static void setCorrelationId(String correlationId) {
        CORRELATION_ID.set(correlationId);
    }

    public static void clear() {
        CORRELATION_ID.remove();
    }
}
