package com.pg.common;

/** 表示已知的业务拒绝；只携带安全代码和 HTTP 状态，不携带用户输入或底层异常。 */
public class BizException extends RuntimeException {
    private final ErrorCode code;
    private final int httpStatus;

    /**
     * 创建业务拒绝，不把底层原因写入响应。
     * @param code 预定义业务代码
     * @param httpStatus 对应的 HTTP 状态
     */
    public BizException(ErrorCode code, int httpStatus) {
        super(code.getMessage());
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /**
     * 创建带固定中文说明的业务拒绝；响应仍只使用预定义错误码的安全提示。
     * @param code 预定义业务代码，决定 HTTP 状态和响应信封
     * @param message 固定中文说明，不得拼接用户输入或底层异常
     */
    public BizException(ErrorCode code, String message) {
        super(message);
        this.code = code;
        this.httpStatus = code.getStatus();
    }

    /** @return 安全的业务代码。 */
    public ErrorCode getCode() { return code; }

    /** @return 响应 HTTP 状态。 */
    public int getHttpStatus() { return httpStatus; }
}
