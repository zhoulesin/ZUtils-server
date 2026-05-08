package com.zutils.server.util;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * ZUtils 日期时间字符串解析（JVM），与 Android
 * {@code com.zhoulesin.zutils.contract.ZutilsDateTimeParse} 及
 * {@code docs/contracts/zutils-datetime-strings.md} 对齐。
 */
public final class ZutilsDateTimeStrings {

    private ZutilsDateTimeStrings() {}

    /**
     * @param naiveLocalZone 当字符串无 UTC 偏移时，用于解释墙钟时间（服务端无设备上下文时常用 {@link ZoneId#of(String)} 或 UTC）
     */
    public static OffsetDateTime parse(String text, ZoneId naiveLocalZone) {
        String t = text.trim();
        try {
            return OffsetDateTime.parse(t);
        } catch (DateTimeException e) {
            try {
                return LocalDateTime.parse(t).atZone(naiveLocalZone).toOffsetDateTime();
            } catch (DateTimeException e2) {
                e.addSuppressed(e2);
                throw e;
            }
        }
    }
}
