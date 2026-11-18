package org.stellar.anchor.config;

import java.lang.annotation.*;

/** Annotate that a method/field is personal identifiable information */
@Documented
@Target({ElementType.FIELD, ElementType.METHOD})
@Inherited
@Retention(RetentionPolicy.RUNTIME)
public @interface PII {}
