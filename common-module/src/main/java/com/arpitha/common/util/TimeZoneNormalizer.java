package com.arpitha.common.util;

import java.util.Map;
import java.util.TimeZone;

/**
 * Replaces legacy JVM time zone IDs with their canonical names.
 * PostgreSQL rejects legacy aliases (e.g. "Asia/Calcutta", which Windows JVMs report for IST)
 * when the JDBC driver sends the session TimeZone, so services fail to connect.
 */
public final class TimeZoneNormalizer {

    private static final Map<String, String> LEGACY_ZONE_IDS = Map.of(
            "Asia/Calcutta", "Asia/Kolkata",
            "Asia/Katmandu", "Asia/Kathmandu",
            "Asia/Saigon", "Asia/Ho_Chi_Minh",
            "Asia/Rangoon", "Asia/Yangon"
    );

    private TimeZoneNormalizer() {
    }

    public static void normalizeDefault() {
        String canonicalId = LEGACY_ZONE_IDS.get(TimeZone.getDefault().getID());
        if (canonicalId != null) {
            TimeZone.setDefault(TimeZone.getTimeZone(canonicalId));
        }
    }
}
