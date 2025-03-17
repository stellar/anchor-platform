package org.stellar.anchor.sep10;

import static java.lang.String.format;
import static org.stellar.anchor.util.Log.*;
import static org.stellar.anchor.util.MetricConstants.SEP10_CHALLENGE_CREATED;
import static org.stellar.anchor.util.MetricConstants.SEP10_CHALLENGE_VALIDATED;
import static org.stellar.anchor.util.StringHelper.isEmpty;
import static org.stellar.sdk.Network.TESTNET;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.stellar.anchor.api.exception.*;
import org.stellar.anchor.api.sep.sep10.ChallengeRequest;
import org.stellar.anchor.api.sep.sep10.ChallengeResponse;
import org.stellar.anchor.api.sep.sep10.ValidationRequest;
import org.stellar.anchor.api.sep.sep10.ValidationResponse;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.auth.Sep10Jwt;
import org.stellar.anchor.client.ClientFinder;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.config.SecretConfig;
import org.stellar.anchor.config.Sep10Config;
import org.stellar.anchor.ledger.LedgerClient;
import org.stellar.anchor.util.Log;
import org.stellar.sdk.*;
import org.stellar.sdk.Sep10Challenge.ChallengeTransaction;
import org.stellar.sdk.exception.InvalidSep10ChallengeException;
import org.stellar.sdk.exception.NetworkException;
import org.stellar.sdk.operations.ManageDataOperation;
import org.stellar.sdk.operations.Operation;

/** The Sep-10 protocol service. */
public class Sep10Service implements ISep10Service {
  final AppConfig appConfig;
  final SecretConfig secretConfig;
  final Sep10Config sep10Config;
  final LedgerClient ledgerClient;
  final JwtService jwtService;
  final ClientFinder clientFinder;
  final String serverAccountId;
  final Counter sep10ChallengeCreatedCounter = Metrics.counter(SEP10_CHALLENGE_CREATED);
  final Counter sep10ChallengeValidatedCounter = Metrics.counter(SEP10_CHALLENGE_VALIDATED);

  public Sep10Service(
      AppConfig appConfig,
      SecretConfig secretConfig,
      Sep10Config sep10Config,
      LedgerClient ledgerClient,
      JwtService jwtService,
      ClientFinder clientFinder) {
    debug("appConfig:", appConfig);
    debug("sep10Config:", sep10Config);
    this.appConfig = appConfig;
    this.secretConfig = secretConfig;
    this.sep10Config = sep10Config;
    this.ledgerClient = ledgerClient;
    this.jwtService = jwtService;
    this.clientFinder = clientFinder;
    this.serverAccountId =
        KeyPair.fromSecretSeed(secretConfig.getSep10SigningSeed()).getAccountId();
    Log.info("Sep10Service initialized.");
  }

  public ChallengeResponse createChallenge(
      @NotNull ChallengeRequest request, @Nullable String authorization)
      throws SepException, BadRequestException {
    info("Creating SEP-10 challenge.");

    // pre validation to be defined by the anchor
    preChallengeRequestValidation(request);
    // Basic request validation, including null, account format, etc
    validateChallengeRequestFormat(request);
    // validate client_attribution, custodial/non-custodial wallet, etc
    validateChallengeRequestClient(request);
    // Validate the validity of the memo
    Memo memo = validateChallengeRequestMemo(request);

    // Non-custodial case
    if (!isEmpty(request.getClientDomain())) {
      debugF("Fetching SIGNING_KEY from client_domain: {}", request.getClientDomain());
      String clientDomainSigningKey = fetchSigningKeyFromClientDomain(request.getClientDomain());
      debugF("SIGNING_KEY from client_domain fetched: {}", clientDomainSigningKey);

      // Check authorization
      validateAuthorization(request, authorization, clientDomainSigningKey);
      // post validations to be defined by the anchor
      postChallengeRequestValidation(request);
      // increment counter
      incrementChallengeRequestCreatedCounter();
      // Create the challenge
      return createChallengeResponse(request, memo, clientDomainSigningKey);
    }
    // Custodial case
    else {
      // Check authorization
      validateAuthorization(request, authorization, null);
      // post validations to be defined by the anchor
      postChallengeRequestValidation(request);
      // increment counter
      incrementChallengeRequestCreatedCounter();
      // Create the challenge
      return createChallengeResponse(request, memo, null);
    }
  }

  public ValidationResponse validateChallenge(ValidationRequest request)
      throws SepValidationException, IOException {
    info("Validating SEP-10 challenge.");

    ChallengeTransaction challenge = parseChallenge(request);
    // pre validation to be defined by the anchor
    preValidateRequestValidation(request, challenge);

    String homeDomain = validateChallengeTransactionHomeDomain(challenge);

    // fetch the client domain from the transaction
    String clientDomain = fetchClientDomain(challenge);
    // fetch the account response from the ledgerApi
    LedgerClient.Account account = fetchAccount(request, challenge, clientDomain);

    if (account == null) {
      // The account does not exist from LedgerApi, using the client's master key to verify.
      return ValidationResponse.of(generateSep10Jwt(challenge, clientDomain, homeDomain));
    }
    // Since the account exists, we should check the signers and the client domain
    validateChallengeRequest(request, account, clientDomain);
    // increment counter
    incrementValidationRequestValidatedCounter();
    // Generate the JWT token
    return ValidationResponse.of(generateSep10Jwt(challenge, clientDomain, homeDomain));
  }

  @Override
  public String validateChallengeTransactionHomeDomain(ChallengeTransaction challenge)
      throws SepValidationException {
    String homeDomain;
    try {
      homeDomain = challenge.getMatchedHomeDomain();
    } catch (Throwable e) {
      throw new SepValidationException("Invalid challenge transaction.");
    }

    if (!Sep10Helper.isDomainNameMatch(sep10Config.getHomeDomains(), homeDomain)) {
      throw new SepValidationException(format("Invalid home_domain. %s", homeDomain));
    }

    return homeDomain;
  }

  @Override
  public ChallengeResponse createChallengeResponse(
      ChallengeRequest request, Memo memo, String clientSigningKey) throws SepException {
    try {
      // create challenge transaction
      Transaction txn = newChallenge(request, clientSigningKey, memo);

      // Convert the challenge to response
      trace("SEP-10 challenge txn:", txn);
      ChallengeResponse challengeResponse =
          ChallengeResponse.of(txn.toEnvelopeXdrBase64(), appConfig.getStellarNetworkPassphrase());
      trace("challengeResponse:", challengeResponse);
      return challengeResponse;
    } catch (InvalidSep10ChallengeException ex) {
      warnEx(ex);
      throw new SepException("Failed to create the sep-10 challenge.", ex);
    }
  }

  Transaction newChallenge(ChallengeRequest request, String clientSigningKey, Memo memo) {

    KeyPair signer = KeyPair.fromSecretSeed(secretConfig.getSep10SigningSeed());
    long now = Instant.now().getEpochSecond();

    return Sep10ChallengeWrapper.instance()
        .newChallenge(
            signer,
            new Network(appConfig.getStellarNetworkPassphrase()),
            request.getAccount(),
            request.getHomeDomain(),
            sep10Config.getWebAuthDomain(),
            new TimeBounds(now, now + sep10Config.getAuthTimeout()),
            (request.getClientDomain() == null) ? "" : request.getClientDomain(),
            (clientSigningKey == null) ? "" : clientSigningKey,
            memo);
  }

  @Override
  public Memo validateChallengeRequestMemo(ChallengeRequest request) throws SepException {
    // Validate memo. It should be 64-bit positive integer if not null.
    try {
      if (request.getMemo() != null) {
        long memoLong = Long.parseUnsignedLong(request.getMemo());
        if (memoLong <= 0) {
          infoF("Invalid memo value: {}", request.getMemo());
          throw new SepValidationException(format("Invalid memo value: %s", request.getMemo()));
        }
        return new MemoId(memoLong);
      } else {
        return null;
      }
    } catch (NumberFormatException e) {
      infoF("invalid memo format: {}. Only MEMO_ID is supported", request.getMemo());
      throw new SepValidationException(format("Invalid memo format: %s", request.getMemo()));
    }
  }

  @Override
  public void validateChallengeRequestClient(ChallengeRequest request) throws SepException {
    boolean custodialWallet = false;
    if (sep10Config.getKnownCustodialAccountList() != null) {
      custodialWallet =
          sep10Config.getKnownCustodialAccountList().contains(request.getAccount().trim());
    }

    if (custodialWallet && request.getClientDomain() != null) {
      throw new SepValidationException(
          "client_domain must not be specified if the account is an custodial-wallet account");
    }

    if (!custodialWallet && sep10Config.isClientAttributionRequired()) {
      if (request.getClientDomain() == null) {
        info("client_domain is required but not provided");
        throw new SepValidationException("client_domain is required");
      }

      List<String> allowList = sep10Config.getAllowedClientDomains();
      if (!allowList.contains(request.getClientDomain())) {
        infoF(
            "client_domain provided ({}) is not in the configured allow list",
            request.getClientDomain());
        throw new SepNotAuthorizedException("unable to process");
      }
    }
  }

  @Override
  public void validateChallengeRequestFormat(ChallengeRequest request) throws SepException {
    // Make sure the request is requested for the correct home domain
    validateHomeDomain(request);
    // Validate request account format
    validateAccountFormat(request);
  }

  @Override
  public void preChallengeRequestValidation(ChallengeRequest request) {
    // NOOP. To be overridden.
  }

  @Override
  public void postChallengeRequestValidation(ChallengeRequest request) {
    // NOOP. To be overridden.
  }

  @Override
  public void incrementChallengeRequestCreatedCounter() {
    sep10ChallengeCreatedCounter.increment();
  }

  @Override
  public void preValidateRequestValidation(
      ValidationRequest request, ChallengeTransaction challenge) {
    // NOOP. To be overridden.
  }

  @Override
  public void incrementValidationRequestValidatedCounter() {
    sep10ChallengeValidatedCounter.increment();
  }

  String fetchSigningKeyFromClientDomain(String clientDomain) throws SepException {
    return Sep10Helper.fetchSigningKeyFromClientDomain(
        clientDomain,
        appConfig.getStellarNetworkPassphrase().equals(TESTNET.getNetworkPassphrase()));
  }

  void validateAuthorization(
      @NotNull ChallengeRequest request,
      @Nullable String authorization,
      @Nullable String clientSigningKey)
      throws SepException {
    if (authorization == null || authorization.isEmpty()) {
      if (sep10Config.isRequireAuthHeader()) {
        throw new SepMissingAuthHeaderException("Authorization header is required");
      }

      return;
    }

    if (!authorization.startsWith("Bearer")) {
      throw new SepValidationException("Invalid JWT token");
    }

    String token = authorization.replaceAll("^Bearer", "").stripLeading();

    Jws<Claims> jwt;

    // Non-custodial
    if (request.getClientDomain() != null) {
      jwt = jwtService.getHeaderJwt(clientSigningKey, token);
    } else {
      // Custodial
      jwt = jwtService.getHeaderJwt(request.getAccount(), token);
    }

    Claims payload = jwt.getPayload();

    try {
      if (payload.get("exp") == null) {
        throw new SepValidationException("Missing expiration time (exp) in JWT");
      }

      if (Instant.ofEpochSecond(Long.parseLong(payload.get("exp").toString()))
          .isBefore(Instant.now())) {
        throw new SepValidationException("JWT token has expired");
      }
    } catch (NumberFormatException e) {
      throw new SepValidationException("Invalid expiration format: must be UNIX epoch second");
    }

    if (claimNotEqual(payload.get("web_auth_endpoint"), authUrl())) {
      if (appConfig.getStellarNetworkPassphrase().equals(TESTNET.getNetworkPassphrase())) {
        // Allow http for testnet
        if (claimNotEqual(payload.get("web_auth_endpoint"), authUrl().replace("https", "http"))) {
          throw new SepValidationException("Invalid web_auth_endpoint in the signed header");
        }
      } else {
        throw new SepValidationException("Invalid web_auth_endpoint in the signed header");
      }
    }

    validateQueryParam(payload, "account", request.getAccount());
    validateQueryParam(payload, "memo", request.getMemo());
    validateQueryParam(payload, "home_domain", request.getHomeDomain());
    validateQueryParam(payload, "client_domain", request.getClientDomain());

    // Validate that client is allowed
    clientFinder.getClientName(request.getClientDomain(), request.getAccount());
  }

  private boolean claimNotEqual(Object claim, String string) {
    return !StringUtils.equals(claim == null ? null : claim.toString(), string);
  }

  void validateQueryParam(Claims payload, String name, String requestParam)
      throws SepValidationException {
    if (claimNotEqual(payload.get(name), requestParam)) {
      throw new SepValidationException(
          "Request query parameter " + name + " doesn't match signed URL query parameter");
    }
  }

  void validateHomeDomain(ChallengeRequest request) throws SepValidationException {
    String homeDomain = request.getHomeDomain();
    String defaultHomeDomain = Sep10Helper.getDefaultDomainName(sep10Config.getHomeDomains());

    if (homeDomain == null && defaultHomeDomain == null) {
      info("home_domain is required but not provided");
      throw new SepValidationException("home_domain is required");
    } else if (homeDomain == null && !isEmpty(defaultHomeDomain)) {
      debugF("home_domain is not specified. Will use the default: {}", defaultHomeDomain);
      request.setHomeDomain(defaultHomeDomain);
    } else if (!Sep10Helper.isDomainNameMatch(sep10Config.getHomeDomains(), homeDomain)) {
      infoF("Bad home_domain: {}", homeDomain);
      throw new SepValidationException(format("home_domain [%s] is not supported.", homeDomain));
    }
  }

  void validateAccountFormat(ChallengeRequest request) throws SepException {
    // Validate account
    try {
      KeyPair.fromAccountId(request.getAccount());
    } catch (IllegalArgumentException ex) {
      infoF("client wallet account ({}) is invalid", request.getAccount());
      throw new SepValidationException("Invalid account.");
    }
  }

  void validateChallengeRequest(
      ValidationRequest request, LedgerClient.Account account, String clientDomain)
      throws SepValidationException {
    // fetch the signers from the transaction
    Set<Sep10Challenge.Signer> signers = fetchSigners(account);
    // the signatures must be greater than the medium threshold of the account.
    int threshold = account.getThresholds().getMedium();
    Network network = new Network(appConfig.getStellarNetworkPassphrase());
    String homeDomain = extractHomeDomainFromChallengeXdr(request.getTransaction(), network);

    infoF(
        "Verifying challenge threshold. server_account={}, client_domain={}, threshold={}, signers={}",
        shorter(serverAccountId),
        clientDomain,
        threshold,
        signers.size());
    Sep10ChallengeWrapper.instance()
        .verifyChallengeTransactionThreshold(
            request.getTransaction(),
            serverAccountId,
            network,
            homeDomain,
            sep10Config.getWebAuthDomain(),
            threshold,
            signers);
  }

  Set<Sep10Challenge.Signer> fetchSigners(LedgerClient.Account account) {
    // Find the signers of the client account.
    return account.getSigners().stream()
        .filter(as -> as.getType().equals("ed25519_public_key"))
        .map(as -> new Sep10Challenge.Signer(as.getKey(), as.getWeight()))
        .collect(Collectors.toSet());
  }

  LedgerClient.Account fetchAccount(
      ValidationRequest request, ChallengeTransaction challenge, String clientDomain)
      throws SepValidationException, IOException {
    // Check the client's account
    LedgerClient.Account account;
    try {
      infoF("Checking if {} exists in the Stellar network", challenge.getClientAccountId());
      account = ledgerClient.getAccount(challenge.getClientAccountId());
      traceF("challenge account: {}", account);
      sep10ChallengeValidatedCounter.increment();
      return account;
    } catch (NetworkException ex) {
      infoF("Account {} does not exist in the Stellar Network");
      // account not found
      // The client account does not exist, using the client's master key to verify.
      Set<String> signers = new HashSet<>();
      signers.add(challenge.getClientAccountId());

      infoF(
          "Verifying challenge threshold. server_account={}, client_domain={}, signers={}",
          shorter(serverAccountId),
          clientDomain,
          signers.size());

      if ((clientDomain != null && challenge.getTransaction().getSignatures().size() != 3)
          || (clientDomain == null && challenge.getTransaction().getSignatures().size() != 2)) {
        infoF(
            "Invalid SEP 10 challenge exception, there is more than one client signer on challenge transaction for an account that doesn't exist. client_domain={}, account_id={}",
            clientDomain,
            challenge.getClientAccountId());
        throw new InvalidSep10ChallengeException(
            "There is more than one client signer on challenge transaction for an account that doesn't exist");
      }

      Network network = new Network(appConfig.getStellarNetworkPassphrase());
      String homeDomain = extractHomeDomainFromChallengeXdr(request.getTransaction(), network);

      debug("Calling Sep10Challenge.verifyChallengeTransactionSigners");
      Sep10ChallengeWrapper.instance()
          .verifyChallengeTransactionSigners(
              request.getTransaction(),
              serverAccountId,
              network,
              homeDomain,
              sep10Config.getWebAuthDomain(),
              signers);

      // increment counter
    }
    sep10ChallengeValidatedCounter.increment();
    return null;
  }

  String fetchClientDomain(ChallengeTransaction challenge) {
    String clientDomain = null;
    Operation operation =
        Arrays.stream(challenge.getTransaction().getOperations())
            .filter(
                op ->
                    (op instanceof ManageDataOperation
                        && ((ManageDataOperation) op).getName().equals("client_domain")))
            .findFirst()
            .orElse(null);

    trace("Challenge operation:", operation);
    if (operation != null) {
      clientDomain = new String(((ManageDataOperation) operation).getValue());
    }
    debugF("client_domain: {}", clientDomain);

    return clientDomain;
  }

  ChallengeTransaction parseChallenge(ValidationRequest request) throws SepValidationException {

    if (request == null || request.getTransaction() == null) {
      throw new SepValidationException("{transaction} is required.");
    }

    String transaction = request.getTransaction();
    Network network = new Network(appConfig.getStellarNetworkPassphrase());
    String homeDomain = extractHomeDomainFromChallengeXdr(transaction, network);

    debug("Parse challenge string.");
    ChallengeTransaction challenge =
        Sep10ChallengeWrapper.instance()
            .readChallengeTransaction(
                transaction,
                serverAccountId,
                new Network(appConfig.getStellarNetworkPassphrase()),
                homeDomain,
                sep10Config.getWebAuthDomain());

    debugF(
        "Challenge parsed. account={}, home_domain={}",
        shorter(challenge.getClientAccountId()),
        challenge.getMatchedHomeDomain());

    trace("challenge:", challenge);

    return challenge;
  }

  String generateSep10Jwt(ChallengeTransaction challenge, String clientDomain, String homeDomain) {
    long issuedAt = challenge.getTransaction().getTimeBounds().getMinTime().longValue();
    Memo memo = challenge.getTransaction().getMemo();
    Sep10Jwt sep10Jwt =
        Sep10Jwt.of(
            authUrl(),
            (memo == null || memo instanceof MemoNone)
                ? challenge.getClientAccountId()
                : challenge.getClientAccountId() + ":" + memo,
            issuedAt,
            issuedAt + sep10Config.getJwtTimeout(),
            challenge.getTransaction().hashHex(),
            clientDomain,
            homeDomain);
    debug("jwtToken:", sep10Jwt);
    return jwtService.encode(sep10Jwt);
  }

  private String authUrl() {
    return "https://" + sep10Config.getWebAuthDomain() + "/auth";
  }

  /**
   * Extracts the home domain from a Stellar SEP-10 challenge transaction represented in XDR format.
   *
   * @param challengeXdr SEP-10 transaction challenge transaction in base64.
   * @param network The network to connect to for verifying and retrieving.
   * @return The extracted home domain from the Manage Data operation within the SEP-10 challenge
   *     transaction.
   * @throws SepValidationException If the transaction is not a valid SEP-10 challenge transaction.
   */
  String extractHomeDomainFromChallengeXdr(String challengeXdr, Network network)
      throws SepValidationException {
    AbstractTransaction parsed;
    try {
      parsed = Transaction.fromEnvelopeXdr(challengeXdr, network);
    } catch (IllegalArgumentException e) {
      throw new SepValidationException("Invalid challenge transaction.");
    }

    if (!(parsed instanceof Transaction)) {
      throw new SepValidationException("Transaction cannot be a fee bump transaction");
    }

    Transaction transaction = (Transaction) parsed;
    if (transaction.getOperations().length < 1) {
      throw new SepValidationException("Transaction requires at least one ManageData operation.");
    }

    // verify that the first operation in the transaction is a Manage Data operation
    Operation operation = transaction.getOperations()[0];
    if (!(operation instanceof ManageDataOperation)) {
      throw new SepValidationException("Operation type should be ManageData.");
    }

    ManageDataOperation manageDataOperation = (ManageDataOperation) operation;
    String homeDomain = manageDataOperation.getName().split(" ")[0];
    if (!Sep10Helper.isDomainNameMatch(sep10Config.getHomeDomains(), homeDomain)) {
      throw new SepValidationException(
          "The transaction's operation key name does not include one of the expected home domains.");
    }

    return homeDomain;
  }
}

class Sep10ChallengeWrapper {
  static Sep10ChallengeWrapper instance = new Sep10ChallengeWrapper();

  public static Sep10ChallengeWrapper instance() {
    return instance;
  }

  public synchronized Transaction newChallenge(
      KeyPair signer,
      Network network,
      String clientAccountId,
      String domainName,
      String webAuthDomain,
      TimeBounds timebounds,
      String clientDomain,
      String clientSigningKey,
      Memo memo) {
    return Sep10Challenge.newChallenge(
        signer,
        network,
        clientAccountId,
        domainName,
        webAuthDomain,
        timebounds,
        clientDomain,
        clientSigningKey,
        memo);
  }

  public synchronized ChallengeTransaction readChallengeTransaction(
      String challengeXdr,
      String serverAccountId,
      Network network,
      String domainName,
      String webAuthDomain) {
    return Sep10Challenge.readChallengeTransaction(
        challengeXdr, serverAccountId, network, domainName, webAuthDomain);
  }

  public synchronized void verifyChallengeTransactionSigners(
      String challengeXdr,
      String serverAccountId,
      Network network,
      String domainName,
      String webAuthDomain,
      Set<String> signers) {
    Sep10Challenge.verifyChallengeTransactionSigners(
        challengeXdr, serverAccountId, network, domainName, webAuthDomain, signers);
  }

  public synchronized void verifyChallengeTransactionThreshold(
      String challengeXdr,
      String serverAccountId,
      Network network,
      String domainName,
      String webAuthDomain,
      int threshold,
      Set<Sep10Challenge.Signer> signers) {
    Sep10Challenge.verifyChallengeTransactionThreshold(
        challengeXdr, serverAccountId, network, domainName, webAuthDomain, threshold, signers);
  }
}
