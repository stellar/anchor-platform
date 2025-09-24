package org.stellar.anchor.config;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

public interface Sep6Config {
  boolean isEnabled();

  Features getFeatures();

  DepositInfoGeneratorType getDepositInfoGeneratorType();

  @Nullable
  Long getInitialUserDeadlineSeconds();

  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  class Features {
    @SerializedName("account_creation")
    boolean accountCreation;

    @SerializedName("claimable_balances")
    boolean claimableBalances;
  }

  enum DepositInfoGeneratorType {
    SELF,
    CUSTODY,
    NONE
  }
}
