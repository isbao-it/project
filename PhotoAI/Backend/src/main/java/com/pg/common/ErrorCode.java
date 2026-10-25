package com.pg.common;

/** 卡 01 的响应代码及对应 HTTP 状态，供鉴权和异常出口共用。 */
public enum ErrorCode {
    OK(200, "ok", "操作成功"),
    INVALID_REQUEST(400, "invalid_request", "请求参数不合法"),
    UNAUTHORIZED(401, "unauthorized", "请先登录"),
    FORBIDDEN(403, "forbidden", "无权访问该资源"),
    NOT_FOUND(404, "not_found", "资源不存在"),
    CONFLICT(409, "conflict", "数据冲突"),
    PAYLOAD_TOO_LARGE(413, "payload_too_large", "上传内容过大"),
    CONTENT_REJECTED(422, "content_rejected", "内容未通过安全检查"),
    INVALID_IMAGE(422, "invalid_image", "图片格式或内容无效"),
    INVALID_PROFILE(422, "invalid_profile", "昵称超过长度限制"),
    SERVICE_UNAVAILABLE(503, "service_unavailable", "外部服务暂不可用"),
    TOO_MANY_REQUESTS(429, "too_many_requests", "请求过于频繁"),
    INTERNAL_ERROR(500, "internal_error", "服务器内部错误");

    private final int status;
    private final String code;
    private final String message;

    ErrorCode(int status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    /** @return HTTP 状态码。 */
    public int getStatus() {
        return status;
    }

    /** @return 响应体的稳定代码。 */
    public String getCode() {
        return code;
    }

    /** @return 不含请求数据的安全提示。 */
    public String getMessage() {
        return message;
    }
}
