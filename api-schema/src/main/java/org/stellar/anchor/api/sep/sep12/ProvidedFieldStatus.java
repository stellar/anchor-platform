package org.stellar.anchor.api.sep.sep12;

import com.google.gson.annotations.SerializedName;

public enum ProvidedFieldStatus {
  @SerializedName("ACCEPTED")
  ACCEPTED("ACCEPTED"),

  @SerializedName("PROCESSING")
  PROCESSING("PROCESSING"),

  @SerializedName("REJECTED")
  REJECTED("REJECTED"),

  @SerializedName("VERIFICATION_REQUIRED")
  VERIFICATION_REQUIRED("VERIFICATION_REQUIRED");

  private final String name;

  ProvidedFieldStatus(String name) {
    this.name = name;
  }

  public String getName() {
    return this.name;
  }
}
