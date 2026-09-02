package com.deepx.apicenter.mapping;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 类型注册表（M0-02 §4.2 转换矩阵，定稿 D6）：typeCast 的边界行为定死。
 * 转换失败抛 TypeCastException，由 MappingEngine 按规则 null_strategy 兜底（定稿 D7）。
 * DATE 输出约定：内部 LocalDateTime，落 UnifiedModel 为 ISO 字符串
 * （yyyy-MM-dd'T'HH:mm:ss，契约 D6 输出格式定死）。
 */
public final class TypeRegistry {

    /** 目标类型 */
    public static final String STRING = "STRING";
    public static final String INT = "INT";
    public static final String DECIMAL = "DECIMAL";
    public static final String BOOL = "BOOL";
    public static final String DATE = "DATE";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter ISO_WITH_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final DateTimeFormatter[] DATE_PARSERS = {
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            ISO_WITH_MILLIS,
            ISO,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    };

    private TypeRegistry() {
    }

    /** 目标类型值域校验 */
    public static boolean isKnown(String targetType) {
        return STRING.equals(targetType) || INT.equals(targetType) || DECIMAL.equals(targetType)
                || BOOL.equals(targetType) || DATE.equals(targetType);
    }

    /** 统一入口：按目标类型转换，失败抛 TypeCastException */
    public static Object cast(Object value, String targetType) {
        return switch (targetType) {
            case STRING -> toStringValue(value);
            case INT -> toInt(value);
            case DECIMAL -> toDecimal(value);
            case BOOL -> toBool(value);
            case DATE -> toDate(value);
            default -> throw new TypeCastException("未知目标类型：" + targetType);
        };
    }

    // ---------- 各目标类型 ----------

    /** → STRING：标量 toString；OBJECT/ARRAY 不支持（转换失败） */
    private static String toStringValue(Object v) {
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Long || v instanceof Integer) {
            return String.valueOf(v);
        }
        if (v instanceof BigDecimal d) {
            return d.toPlainString();
        }
        if (v instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (v instanceof LocalDateTime dt) {
            return dt.format(ISO);
        }
        throw new TypeCastException("不支持转为 STRING：" + v.getClass().getSimpleName());
    }

    /** → INT：数字串解析（trim）；DECIMAL 截断；BOOLEAN 1/0；DATE → epoch 秒 */
    private static long toInt(Object v) {
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof Integer i) {
            return i.longValue();
        }
        if (v instanceof BigDecimal d) {
            return d.longValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        if (v instanceof LocalDateTime dt) {
            return dt.toEpochSecond(ZoneOffset.UTC);
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new TypeCastException("无法转为 INT：" + s);
            }
        }
        throw new TypeCastException("不支持转为 INT：" + v.getClass().getSimpleName());
    }

    /** → DECIMAL：BigDecimal；BOOLEAN 1/0；DATE → epoch 秒（与 INT 同，定稿 D6） */
    private static BigDecimal toDecimal(Object v) {
        if (v instanceof BigDecimal d) {
            return d;
        }
        if (v instanceof Long l) {
            return BigDecimal.valueOf(l);
        }
        if (v instanceof Integer i) {
            return BigDecimal.valueOf(i.longValue());
        }
        if (v instanceof Boolean b) {
            return b ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (v instanceof LocalDateTime dt) {
            return BigDecimal.valueOf(dt.toEpochSecond(ZoneOffset.UTC));
        }
        if (v instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                throw new TypeCastException("无法转为 DECIMAL：" + s);
            }
        }
        throw new TypeCastException("不支持转为 DECIMAL：" + v.getClass().getSimpleName());
    }

    /** → BOOL：白名单字符串（true/1/false/0）；数值 0→false 非 0→true */
    private static boolean toBool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return switch (s.trim().toLowerCase()) {
                case "true", "1" -> true;
                case "false", "0" -> false;
                default -> throw new TypeCastException("无法转为 BOOL：" + s);
            };
        }
        if (v instanceof Long l) {
            return l != 0;
        }
        if (v instanceof Integer i) {
            return i != 0;
        }
        if (v instanceof BigDecimal d) {
            return d.compareTo(BigDecimal.ZERO) != 0;
        }
        throw new TypeCastException("不支持转为 BOOL：" + v.getClass().getSimpleName());
    }

    /**
     * → DATE（定稿 D6 边界定死）：
     * 数值 = epoch（值 > 1e11 视为毫秒，否则秒）；字符串 = ISO-8601 或 yyyy-MM-dd HH:mm:ss。
     */
    private static LocalDateTime toDate(Object v) {
        if (v instanceof Long l) {
            return epoch(l);
        }
        if (v instanceof Integer i) {
            return epoch(i.longValue());
        }
        if (v instanceof BigDecimal d) {
            return epoch(d.longValue());
        }
        if (v instanceof String s) {
            String t = s.trim();
            for (DateTimeFormatter f : DATE_PARSERS) {
                try {
                    return LocalDateTime.parse(t, f);
                } catch (Exception ignored) {
                    // 尝试下一个格式
                }
            }
            throw new TypeCastException("无法转为 DATE（支持 ISO-8601 / yyyy-MM-dd HH:mm:ss）：" + s);
        }
        throw new TypeCastException("不支持转为 DATE：" + v.getClass().getSimpleName());
    }

    private static LocalDateTime epoch(long v) {
        if (v > 100_000_000_000L) { // 1e11 毫秒
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(v), ZoneOffset.UTC);
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(v), ZoneOffset.UTC);
    }

    /** 类型转换失败信号（MappingEngine 按 null_strategy 兜底） */
    public static class TypeCastException extends RuntimeException {
        public TypeCastException(String message) {
            super(message);
        }
    }
}
