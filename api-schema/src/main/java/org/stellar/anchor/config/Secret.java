package org.stellar.anchor.config;

import java.lang.annotation.*;

/** To annotate if a method returns secret information. */
@Documented
@Target({ElementType.METHOD})
@Inherited
@Retention(RetentionPolicy.RUNTIME)
public @interface Secret {}
