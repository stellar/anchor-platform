package org.stellar.anchor.config;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Set;
import lombok.*;
import org.stellar.anchor.client.ClientConfig;
import org.stellar.anchor.client.CustodialClient;
import org.stellar.anchor.client.NonCustodialClient;

public interface ClientsConfig {

  ClientsConfigType getType();

  String getValue();

  List<RawClient> getItems();

  boolean isMigrateFromFileOnStartup();

  enum ClientsConfigType {
    @SerializedName("file")
    FILE,
    @SerializedName("inline")
    INLINE,
    @SerializedName("json")
    JSON,
    @SerializedName("yaml")
    YAML,
    @SerializedName("db")
    DB,
  }

  @Data
  @Builder
  @AllArgsConstructor
  @NoArgsConstructor
  class RawClient {

    /**
     * Represents a temporary client object used exclusively for loading client details from inline
     * YAML configuration. This class is intended as a temporary workaround to facilitate the
     * deserialization of client configurations. Note: If you add any new fields to the
     * CustodialClient or NonCustodialClient classes, ensure that you also add those fields here to
     * maintain consistency.
     */
    @NonNull String name;

    ClientConfig.ClientType type;

    Set<String> domains;

    @SerializedName("signing_keys")
    Set<String> signingKeys;

    @SerializedName("callback_urls")
    ClientConfig.CallbackUrls callbackUrls;

    @SerializedName("allow_any_destination")
    boolean allowAnyDestination = false;

    @SerializedName("destination_accounts")
    Set<String> destinationAccounts;

    public CustodialClient toCustodialClient() {
      return CustodialClient.builder()
          .name(name)
          .signingKeys(signingKeys)
          .callbackUrls(callbackUrls)
          .allowAnyDestination(allowAnyDestination)
          .destinationAccounts(destinationAccounts)
          .build();
    }

    public NonCustodialClient toNonCustodialClient() {
      return NonCustodialClient.builder()
          .name(name)
          .domains(domains)
          .callbackUrls(callbackUrls)
          .build();
    }
  }
}
