package org.stellar.anchor.config;

import java.util.List;

public interface Sep10Config {

  /**
   * Whether to enable SEP-10 for this instance of AP server. If true, @required_secrets:
   * SEP10_SIGNING_SEED
   *
   * @return true if SEP-10 is enabled.
   */
  Boolean getEnabled();

  /**
   * The `web_auth_domain` property of <a
   * href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0010.md#response">SEP-10</a>.
   * If the `web_auth_domain` is not specified, the `web_auth_domain` will be set to the first value
   * of `home_domains`. The `web_auth_domain` value must equal to the host of the SEP server.
   *
   * @return the web auth domain.
   */
  String getWebAuthDomain();

  /**
   * The `home_domains` property of <a
   * href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0010.md#request">SEP-10</a>.
   *
   * @return the list of home domains.
   */
  List<String> getHomeDomains();

  /**
   * Set the authentication challenge transaction timeout in seconds. An expired signed transaction
   * will be rejected. This is the timeout period the client must finish the authentication process.
   * (ie: sign and respond the challenge transaction). #
   *
   * @return the auth timeout.
   */
  Integer getAuthTimeout();

  /**
   * Set the timeout in seconds of the authenticated JSON Web Token. An expired JWT will be
   * rejected. This is the timeout period after the client has authenticated. #
   *
   * @return the JWT timeout.
   */
  Integer getJwtTimeout();

  /**
   * Set if the client attribution is required. Client Attribution requires clients to verify their
   * identity by passing a domain in the challenge transaction request and signing the challenge
   * with the ``SIGNING_KEY`` on that domain's SEP-1 stellar.toml. See the SEP-10 section `Verifying
   * Client Application Identity` for more information (<a
   * href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0010.md#verifying-client-application-identity">SEP-10</a>).
   * # # If the client_attribution_required is set to true, the list of allowed clients must be
   * configured in the `clients` # section of this configuration file. The `domain` field of the
   * client must be provided.
   *
   * <p>The flag is only relevant for noncustodial wallets.
   *
   * @return true if client attribution is required.
   */
  boolean isClientAttributionRequired();

  /**
   * Get the list of allowed client domains.
   *
   * @return the list of allowed client domains.
   */
  List<String> getAllowedClientDomains();

  /**
   * Get the list of allowed client names.
   *
   * @return the list of allowed client names.
   */
  List<String> getAllowedClientNames();

  /**
   * Set the list of known custodial accounts.
   *
   * @return the list of known custodial accounts.
   */
  List<String> getKnownCustodialAccountList();

  /**
   * Set if the client must provide a valid Auth header in get challenge request (GET /Auth)
   *
   * @return true if functionality is enabled.
   */
  boolean isRequireAuthHeader();
}
