package org.stellar.anchor.platform.condition;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.context.annotation.ConditionContext;

public class OnAllSepEnabledCondition extends AbstractOnSepsEnabled {
  @Override
  String getAnnotatingClass() {
    return OnAllSepsEnabled.class.getName();
  }

  @Override
  ConditionOutcome getMatchOutcome(List<String> seps, ConditionContext context, String className) {
    for (String sep : seps) {
      boolean enabled =
          "true"
              .equalsIgnoreCase(
                  context
                      .getEnvironment()
                      .getProperty(String.format("%s.enabled", sep.toLowerCase())));
      // One is not enabled.
      if (!enabled) return ConditionOutcome.noMatch(String.format("%s disabled", className));
    }

    // All enabled.
    return ConditionOutcome.match(String.format("%s enabled", className));
  }
}
