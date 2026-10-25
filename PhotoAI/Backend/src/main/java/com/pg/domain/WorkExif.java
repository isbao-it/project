package com.pg.domain;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 六项 EXIF 的白名单及数据库范围校验；不保存原始元数据。 */
public final class WorkExif {
    private static final String[] FIELDS = {"focal", "aperture", "shutter", "iso", "lens", "camera"};
    private static final int SMALLINT_MAX = 32767; // 冻结§1.3：有符号 SMALLINT 上限。
    private static final BigDecimal APERTURE_MAX = new BigDecimal("99.9"); // DECIMAL(3,1) 上限。
    private static final int SHUTTER_MAX = 16;
    private static final int EQUIPMENT_MAX = 64;

    private WorkExif() { }

    /**
     * 返回六项空值；
     * @return 始终含六键的可写投影
     */
    public static Map<String, Object> empty() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : FIELDS) {
            result.put(field, null);
        }
        return result;
    }

    /**
     * 投影并验证外部输入，不截断或舍入；
     * @param input JSON 对象
     * @return 六项白名单值
     */
    public static Map<String, Object> validated(Object input) {
        if (!(input instanceof Map)) {
            throw invalid();
        }
        Map<?, ?> source = (Map<?, ?>) input;
        Map<String, Object> result = empty();
        for (String field : FIELDS) {
            Object value = source.get(field);
            if (value == null) {
                continue;
            }
            if ("focal".equals(field) || "iso".equals(field)) {
                if (!(value instanceof Number)) {
                    throw invalid();
                }
                BigDecimal number = new BigDecimal(value.toString());
                if (number.signum() <= 0 || number.compareTo(BigDecimal.valueOf(SMALLINT_MAX)) > 0
                        || number.stripTrailingZeros().scale() > 0) {
                    throw invalid();
                }
                value = number.intValueExact();
            } else if ("aperture".equals(field)) {
                if (!(value instanceof Number)) {
                    throw invalid();
                }
                BigDecimal number = new BigDecimal(value.toString());
                if (number.signum() <= 0 || number.compareTo(APERTURE_MAX) > 0
                        || number.stripTrailingZeros().scale() > 1) {
                    throw invalid();
                }
                value = number;
            } else {
                int max = "shutter".equals(field) ? SHUTTER_MAX : EQUIPMENT_MAX;
                if (!(value instanceof String)
                        || ((String) value).codePointCount(0, ((String) value).length()) > max) {
                    throw invalid();
                }
            }
            result.put(field, value);
        }
        return result;
    }

    private static BizException invalid() {
        return new BizException(ErrorCode.INVALID_REQUEST, "拍摄参数不合法");
    }
}
