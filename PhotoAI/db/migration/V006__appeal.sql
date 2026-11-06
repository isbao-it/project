-- 卡 AD-APPEAL：账号封禁申诉。
-- 幂等：先查 information_schema 再建表；不改动既有表。
-- 执行器：mysql 客户端；也可被 JDBC 直接执行（不含 DELIMITER 段）。
-- 客户端可能以 latin1 连接，中文 DEFAULT 值会报 1067，这里显式声明连接字符集。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS appeal (
    aid      VARCHAR(36)  NOT NULL COMMENT '申诉编号',
    uid      VARCHAR(36)  NOT NULL COMMENT '申诉人',
    content  VARCHAR(200) NOT NULL COMMENT '申诉理由原文（1-200 字）',
    status   VARCHAR(16)  NOT NULL DEFAULT '待处理' COMMENT '待处理/已受理/已驳回',
    ct       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    handler  VARCHAR(36)  NULL COMMENT '处理管理员',
    hct      DATETIME     NULL COMMENT '处理时间',
    reason   VARCHAR(200) NULL COMMENT '驳回理由',
    PRIMARY KEY (aid),
    KEY idx_uid_ct (uid, ct),
    KEY idx_status_ct (status, ct)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '账号封禁申诉';
