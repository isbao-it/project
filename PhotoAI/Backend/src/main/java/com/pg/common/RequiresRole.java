package com.pg.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 声明处理方法所需角色；共享资料入口可额外允许普通用户进入本人字段白名单分支。 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresRole {
    /** @return 允许进入管理分支的角色。 */
    String value();

    /** @return 是否保留控制器内的普通用户本人更新分支；该分支不能写管理字段。 */
    boolean allowSelfUpdate() default false;
}
