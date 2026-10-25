package com.pg.entity;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** 统一响应信封；顶层只有 code/msg/data，分页计数属于 data 内部。 */
public class AjaxResult extends HashMap<String, Object> {
    /** 创建空信封，工厂方法负责填齐对外字段。 */
    public AjaxResult() { }

    /**
     * 链式填充响应。
     * @param key 字段名称
     * @param value 字段内容
     * @return 当前响应
     */
    @Override
    public AjaxResult put(String key, Object value) {
        super.put(key, value);
        return this;
    }

    /** @return 无返回数据的成功信封。 */
    public static AjaxResult success() { return success((Object) null); }

    /**
     * 创建自定义成功提示。
     * @param msg 成功提示
     * @return 三字段信封
     */
    public static AjaxResult success(String msg) {
        return success().put("msg", msg);
    }

    /**
     * 创建分页响应；旧调用传单对象时按单对象响应，避免上传地址被误包装成列表。
     * @param total 分页总记录数
     * @param data 列表或旧调用的单对象
     * @return 分页在 data.list/data.total 的成功信封
     */
    public static AjaxResult success(long total, Object data) {
        if (!(data instanceof Collection)) {
            return success(data);
        }
        Map<String, Object> page = new HashMap<>();
        page.put("list", data);
        page.put("total", total);
        return success(page);
    }

    /**
     * 创建单对象响应。
     * @param data 对外结果
     * @return 三字段成功信封
     */
    public static AjaxResult success(Object data) {
        return new AjaxResult().put("code", ErrorCode.OK.getCode()).put("msg", ErrorCode.OK.getMessage()).put("data", data);
    }

    /** @return 不会正常返回；旧错误工厂统一抛异常，让全局处理器设置 HTTP 状态。 */
    public static AjaxResult error() { throw new BizException(ErrorCode.INTERNAL_ERROR, 500); }

    /**
     * 将旧控制器的手工错误迁移到全局出口，不输出可能拼接了敏感输入的提示。
     * @param status 原 HTTP 错误状态
     * @param msg 旧提示；不向客户端输出
     * @return 不会正常返回，抛出安全业务异常
     */
    public static AjaxResult error(int status, String msg) {
        for (ErrorCode code : ErrorCode.values()) {
            if (code != ErrorCode.OK && code.getStatus() == status) {
                throw new BizException(code, status);
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR, 500);
    }

    /**
     * 创建错误信封，供拦截器和全局异常出口写响应。
     * @param code 预定义错误代码
     * @return 三字段错误信封
     */
    public static AjaxResult error(ErrorCode code) {
        return new AjaxResult().put("code", code.getCode()).put("msg", code.getMessage()).put("data", null);
    }
}
