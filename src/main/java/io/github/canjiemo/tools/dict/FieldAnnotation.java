package io.github.canjiemo.tools.dict;

import io.github.canjiemo.tools.dict.entity.Var;

public @interface FieldAnnotation {
    String fullAnnotationName();
    Var[] vars();
}
