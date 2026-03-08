package io.github.canjiemo.tools.dict;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字典注解
 * 用于自动生成字典描述字段
 *
 * @author canjiemo
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.SOURCE)
public @interface MyDict {
    /**
     * 字典名称简写。
     * <p>
     * 仅在单独传递字典名时可简写为 {@code @MyDict("xxx")}。
     */
    String value() default "";

    /**
     * 字典名称。
     * <p>
     * 与 {@link #value()} 二选一即可，保留该属性用于兼容旧写法。
     */
    String name() default "";

    /**
     * 默认描述（当字典查询为空时返回）
     */
    String defaultDesc() default "";

    /**
     * 自定义描述字段注解。
     */
    FieldAnnotation[] descFieldAnnotations() default {};

    /**
     * @deprecated 请改用 {@link #descFieldAnnotations()}，保留该属性用于兼容旧写法。
     */
    @Deprecated(since = "1.0.4-jdk21")
    FieldAnnotation[] fieldAnnotations() default {};

    /**
     * 是否使用驼峰命名
     * 默认：true
     *
     * <p>命名规则（优先级从高到低）：</p>
     * <ul>
     *   <li>1. 如果字段名包含下划线 '_'，忽略此开关，始终生成蛇形命名（如：user_status → user_status_desc）</li>
     *   <li>2. 如果字段名包含大小写混合，忽略此开关，始终生成驼峰命名（如：userName → userNameDesc）</li>
     *   <li>3. 如果字段名全小写无特征（如：type），则根据此开关决定：
     *     <ul>
     *       <li>true：生成驼峰 typeDesc</li>
     *       <li>false：生成蛇形 type_desc</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    boolean camelCase() default true;

}
