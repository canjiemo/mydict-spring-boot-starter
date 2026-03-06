package io.github.canjiemo.tools.dict;

import io.github.canjiemo.tools.dict.entity.Var;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Target({})
@Retention(RetentionPolicy.SOURCE)
public @interface FieldAnnotation {
    String fullAnnotationName();
    Var[] vars();
}
