package io.github.canjiemo.tools.dict.entity;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Target({})
@Retention(RetentionPolicy.SOURCE)
public @interface Var {
    VarType varType();
    String varName();
    String varValue();
}
