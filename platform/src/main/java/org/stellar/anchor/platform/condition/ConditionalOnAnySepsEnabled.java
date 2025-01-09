package org.stellar.anchor.platform.condition;

import java.lang.annotation.*;
import org.springframework.context.annotation.Conditional;

/** Indicates if any of the SEPs in the "seps" list is enabled in the configuration. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnAnySepEnabledCondition.class)
public @interface ConditionalOnAnySepsEnabled {
  /**
   * The list of seps that are checked if enabled.
   *
   * @return The list of seps
   */
  String[] seps() default {};
}
