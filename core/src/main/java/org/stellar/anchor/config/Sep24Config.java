package org.stellar.anchor.config;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import lombok.Setter;

public interface Sep24Config {
  boolean isEnabled();

  Features getFeatures();

  DepositInfoGeneratorType getDepositInfoGeneratorType();

  Long getInitialUserDeadlineSeconds();

  @Getter
  @Setter
  class Features {
    @SerializedName("account_creation")
    Boolean accountCreation;

    @SerializedName("claimable_balances")
    Boolean claimableBalances;
  }

  enum DepositInfoGeneratorType {
    SELF,
    CUSTODY,
    NONE
  }
}
