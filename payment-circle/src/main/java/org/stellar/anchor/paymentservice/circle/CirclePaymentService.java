package org.stellar.anchor.paymentservice.circle;

import static org.stellar.anchor.util.StellarNetworkHelper.toStellarNetwork;

import com.google.gson.JsonObject;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.commons.validator.routines.EmailValidator;
import org.stellar.anchor.exception.HttpException;
import org.stellar.anchor.paymentservice.*;
import org.stellar.anchor.paymentservice.circle.config.CirclePaymentConfig;
import org.stellar.anchor.paymentservice.circle.model.*;
import org.stellar.anchor.paymentservice.circle.model.request.CircleSendTransactionRequest;
import org.stellar.anchor.paymentservice.circle.model.response.*;
import org.stellar.anchor.paymentservice.circle.util.NettyHttpClient;
import org.stellar.sdk.Network;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClient;
import reactor.util.annotation.NonNull;
import reactor.util.annotation.Nullable;
import shadow.com.google.common.reflect.TypeToken;

public class CirclePaymentService
    implements PaymentService, CircleResponseErrorHandler, StellarReconciliation {
  private final CirclePaymentConfig config;

  private final Network stellarNetwork;

  private final PaymentNetwork paymentNetwork = PaymentNetwork.CIRCLE;

  private HttpClient webClient;

  private String mainAccountAddress;

  /**
   * For all service methods to work correctly, make sure your circle account has a valid business
   * wallet and a bank account configured.
   */
  public CirclePaymentService(CirclePaymentConfig config) {
    super();
    this.config = config;
    this.stellarNetwork = toStellarNetwork(config.getStellarNetwork());
  }

  @Override
  public String getHorizonUrl() {
    return config.getHorizonUrl();
  }

  @Override
  public PaymentNetwork getPaymentNetwork() {
    return this.paymentNetwork;
  }

  @Override
  public String getName() {
    return config.getName();
  }

  public HttpClient getWebClient(boolean authenticated) {
    if (webClient == null) {
      this.webClient = NettyHttpClient.withBaseUrl(this.config.getCircleUrl());
    }
    if (!authenticated) {
      return webClient;
    }
    return webClient.headers(
        h -> h.add(HttpHeaderNames.AUTHORIZATION, "Bearer " + this.config.getSecretKey()));
  }

  /**
   * API request that pings the server to make sure it's up and running.
   *
   * @return asynchronous stream with a Void value. If no exception is thrown it means the request
   *     was successful and the remote server is operational.
   * @throws HttpException If the http response status code is 4xx or 5xx.
   */
  public Mono<Void> ping() throws HttpException {
    return getWebClient(false)
        .get()
        .uri("/ping")
        .responseSingle(handleResponseSingle())
        .flatMap(s -> Mono.empty());
  }

  /**
   * API request that returns the id of the distribution account managed by the secret key.
   *
   * @return asynchronous stream with the id of the distribution account managed by the secret key.
   * @throws HttpException If the http response status code is 4xx or 5xx.
   */
  public Mono<String> getDistributionAccountAddress() throws HttpException {
    if (mainAccountAddress != null) {
      return Mono.just(mainAccountAddress);
    }

    return getWebClient(true)
        .get()
        .uri("/v1/configuration")
        .responseSingle(handleResponseSingle())
        .map(
            body -> {
              CircleConfigurationResponse response =
                  gson.fromJson(body, CircleConfigurationResponse.class);
              mainAccountAddress = response.getData().payments.masterWalletId;
              return mainAccountAddress;
            });
  }

  /**
   * Get the merchant account unsettled balances in circle
   *
   * @return asynchronous stream with a list of the unsettled balances.
   */
  @NonNull
  private Mono<List<Balance>> getMerchantAccountUnsettledBalances() {
    return getWebClient(true)
        .get()
        .uri("/v1/businessAccount/balances")
        .responseSingle(handleResponseSingle())
        .map(
            body -> {
              CircleAccountBalancesResponse response =
                  gson.fromJson(body, CircleAccountBalancesResponse.class);

              List<Balance> unsettledBalances = new ArrayList<>();
              for (CircleBalance uBalance : response.getData().unsettled) {
                unsettledBalances.add(uBalance.toBalance(PaymentNetwork.CIRCLE));
              }

              return unsettledBalances;
            });
  }

  /**
   * API request that retrieves the circle wallet with the given id.
   *
   * @param walletId is the existing wallet identifier.
   * @return asynchronous stream with the CircleWallet object.
   * @throws HttpException If the http response status code is 4xx or 5xx.
   */
  @NonNull
  private Mono<CircleWallet> getCircleWallet(String walletId) throws HttpException {
    return getWebClient(true)
        .get()
        .uri("/v1/wallets/" + walletId)
        .responseSingle(handleResponseSingle())
        .map(
            body -> {
              Type type = new TypeToken<CircleDetailResponse<CircleWallet>>() {}.getType();
              CircleDetailResponse<CircleWallet> circleWalletResponse = gson.fromJson(body, type);
              return circleWalletResponse.getData();
            });
  }

  /**
   * API request that retrieves the account with the given id.
   *
   * @param accountId is the existing account identifier of the circle account.
   * @return asynchronous stream with the account object.
   * @throws HttpException If the http response status code is 4xx or 5xx.
   */
  public Mono<Account> getAccount(String accountId) throws HttpException {
    return getDistributionAccountAddress()
        .flatMap(
            distAccountId -> {
              Mono<List<Balance>> unsettledBalancesMono = Mono.just(new ArrayList<>());
              if (distAccountId.equals(accountId)) {
                unsettledBalancesMono = getMerchantAccountUnsettledBalances();
              }
              return Mono.zip(unsettledBalancesMono, getCircleWallet(accountId));
            })
        .map(
            args -> {
              List<Balance> unsettledBalances = args.getT1();
              CircleWallet circleWallet = args.getT2();
              Account account = circleWallet.toAccount();
              account.setUnsettledBalances(unsettledBalances);
              return account;
            });
  }

  /**
   * API request that creates an account with the given id.
   *
   * @param accountId is the identifier of the account to be created. It is used as an optional
   *     description in the Circle implementation.
   * @return asynchronous stream with the account object.
   * @throws HttpException If the http response status code is 4xx or 5xx.
   */
  public Mono<Account> createAccount(@Nullable String accountId) throws HttpException {
    JsonObject postBody = new JsonObject();
    postBody.addProperty("idempotencyKey", UUID.randomUUID().toString());
    if (accountId != null && !accountId.isEmpty()) {
      postBody.addProperty("description", accountId);
    }

    return getWebClient(true)
        .post()
        .uri("/v1/wallets")
        .send(ByteBufMono.fromString(Mono.just(postBody.toString())))
        .responseSingle(handleResponseSingle())
        .map(
            body -> {
              Type type = new TypeToken<CircleDetailResponse<CircleWallet>>() {}.getType();
              CircleDetailResponse<CircleWallet> circleWalletResponse = gson.fromJson(body, type);
              CircleWallet circleWallet = circleWalletResponse.getData();
              return circleWallet.toAccount();
            });
  }

  public Mono<CircleTransferListResponse> getTransfers(
      String accountID, String beforeCursor, String afterCursor, Integer pageSize)
      throws HttpException {
    // build query parameters for GET requests
    int _pageSize = pageSize != null ? pageSize : 50;
    LinkedHashMap<String, String> queryParams = new LinkedHashMap<>();
    queryParams.put("pageSize", Integer.toString(_pageSize));
    queryParams.put("walletId", accountID);

    if (afterCursor != null && !afterCursor.isEmpty()) {
      queryParams.put("pageAfter", afterCursor);
      // we can't use both pageBefore and pageAfter at the same time, that's why I'm using 'else if'
    } else if (beforeCursor != null && !beforeCursor.isEmpty()) {
      queryParams.put("pageBefore", beforeCursor);
    }

    return getWebClient(true)
        .get()
        .uri(NettyHttpClient.buildUri("/v1/transfers", queryParams))
        .responseSingle(handleResponseSingle())
        .flatMap(
            body -> {
              CircleTransferListResponse response =
                  gson.fromJson(body, CircleTransferListResponse.class);
              Mono<CircleTransferListResponse> originalResponseMono = Mono.just(response);

              // Build Mono.zip to retrieve the Stellar info from Stellar->CircleWallet transfers in
              // order to update the sender address that's not disclosed in Circle's API.
              List<Mono<CircleTransfer>> monoList =
                  response.getData().stream()
                      .map(this::updatedStellarSenderAddress)
                      .collect(Collectors.toList());
              Mono<List<CircleTransfer>> updatedTransfersMono = Mono.just(new ArrayList<>());
              if (monoList.size() > 0) {
                updatedTransfersMono =
                    Mono.zip(
                        monoList,
                        objects -> List.of(Arrays.stream(objects).toArray(CircleTransfer[]::new)));
              }

              return Mono.zip(originalResponseMono, updatedTransfersMono);
            })
        .map(
            args -> {
              CircleTransferListResponse response = args.getT1();
              List<CircleTransfer> updatedTransfers = args.getT2();

              response.setData(updatedTransfers);
              return response;
            });
  }

  public Mono<CirclePayoutListResponse> getPayouts(
      String accountID, String beforeCursor, String afterCursor, Integer pageSize)
      throws HttpException {
    // build query parameters for GET requests
    int _pageSize = pageSize != null ? pageSize : 50;
    LinkedHashMap<String, String> queryParams = new LinkedHashMap<>();
    queryParams.put("pageSize", Integer.toString(_pageSize));
    queryParams.put("source", accountID);

    if (afterCursor != null && !afterCursor.isEmpty()) {
      queryParams.put("pageAfter", afterCursor);
      // we can't use both pageBefore and pageAfter at the same time, that's why I'm using 'else if'
    } else if (beforeCursor != null && !beforeCursor.isEmpty()) {
      queryParams.put("pageBefore", beforeCursor);
    }

    return getWebClient(true)
        .get()
        .uri(NettyHttpClient.buildUri("/v1/payouts", queryParams))
        .responseSingle(handleResponseSingle())
        .map(body -> gson.fromJson(body, CirclePayoutListResponse.class));
  }

  /**
   * API request that returns the history of payments involving a given account.
   *
   * @param accountID the id of the account whose payment history we want to fetch.
   * @param beforeCursor the value used to limit payments to only those before it
   * @param afterCursor the value used to limit payments to only those before it
   * @return asynchronous stream with the payment history.
   * @throws HttpException If the http response status code is 4xx or 5xx.
   */
  public Mono<PaymentHistory> getAccountPaymentHistory(
      String accountID, @Nullable String beforeCursor, @Nullable String afterCursor)
      throws HttpException {
    // TODO: implement /v1/payments as well
    // Parse cursor
    String beforeTransfer = null, afterTransfer = null, beforePayout = null, afterPayout = null;
    if (beforeCursor != null) {
      String[] beforeCursors = beforeCursor.split(":");
      if (beforeCursors.length < 2) {
        throw new HttpException(400, "invalid before cursor");
      }
      beforeTransfer = beforeCursors[0];
      beforePayout = beforeCursors[1];
    }
    if (afterCursor != null) {
      String[] afterCursors = afterCursor.split(":");
      if (afterCursors.length < 2) {
        throw new HttpException(400, "invalid after cursor");
      }
      afterTransfer = afterCursors[0];
      afterPayout = afterCursors[1];
    }

    int pageSize = 50;
    return Mono.zip(
            getDistributionAccountAddress(),
            getTransfers(accountID, beforeTransfer, afterTransfer, pageSize),
            getPayouts(accountID, beforePayout, afterPayout, pageSize))
        .map(
            args -> {
              String distributionAccId = args.getT1();
              Account account =
                  new Account(
                      PaymentNetwork.CIRCLE,
                      accountID,
                      new Account.Capabilities(PaymentNetwork.CIRCLE, PaymentNetwork.STELLAR));
              account.capabilities.set(
                  PaymentNetwork.BANK_WIRE, distributionAccId.equals(account.id));

              PaymentHistory transfersHistory =
                  args.getT2().toPaymentHistory(pageSize, account, distributionAccId);
              PaymentHistory payoutsHistory = args.getT3().toPaymentHistory(pageSize, account);
              PaymentHistory result = new PaymentHistory(account);

              String befTransfer = transfersHistory.getBeforeCursor();
              String befPayout = payoutsHistory.getBeforeCursor();
              if (befTransfer != null || befPayout != null) {
                result.setBeforeCursor(befTransfer + ":" + befPayout);
              }

              String aftTransfer = transfersHistory.getAfterCursor();
              String aftPayout = payoutsHistory.getAfterCursor();
              if (aftTransfer != null || aftPayout != null) {
                result.setAfterCursor(aftTransfer + ":" + aftPayout);
              }

              List<Payment> allPayments = new ArrayList<>();
              allPayments.addAll(transfersHistory.getPayments());
              allPayments.addAll(payoutsHistory.getPayments());
              allPayments =
                  allPayments.stream()
                      .sorted((p1, p2) -> p2.getCreatedAt().compareTo(p1.getCreatedAt()))
                      .collect(Collectors.toList());
              for (Payment p : allPayments) {
                updatePaymentWireCapability(p, distributionAccId);
              }
              result.setPayments(allPayments);

              return result;
            });
  }

  /**
   * Validates if the fields needed to send a payment are valid.
   *
   * @param sourceAccount the account where the payment will be sent from.
   * @param destinationAccount the account that will receive the payment.
   * @param currencyName the name of the currency used in the payment. It should obey the
   *     {scheme}:{identifier} format described in <a
   *     href="https://stellar.org/protocol/sep-38#asset-identification-format">SEP-38</a>.
   * @throws HttpException if the source account network is not CIRCLE.
   * @throws HttpException if the destination account network is not supported.
   * @throws HttpException if the destination account is a bank and the idTag is not a valid email.
   * @throws HttpException if the currencyName prefix does not reflect the destination account
   *     network.
   */
  private void validateSendPaymentInput(
      @NonNull Account sourceAccount,
      @NonNull Account destinationAccount,
      @NonNull String currencyName)
      throws HttpException {
    if (sourceAccount.paymentNetwork != PaymentNetwork.CIRCLE) {
      throw new HttpException(400, "the only supported network for the source account is circle");
    }
    if (!List.of(PaymentNetwork.CIRCLE, PaymentNetwork.STELLAR, PaymentNetwork.BANK_WIRE)
        .contains(destinationAccount.paymentNetwork)) {
      throw new HttpException(
          400,
          "the only supported networks for the destination account are circle, stellar and bank_wire");
    }
    if (destinationAccount.paymentNetwork == PaymentNetwork.BANK_WIRE
        && !EmailValidator.getInstance().isValid(destinationAccount.idTag)) {
      throw new HttpException(
          400,
          "for bank transfers, please provide a valid beneficiary email address in the destination idTag");
    }
    if (!currencyName.startsWith(destinationAccount.paymentNetwork.getCurrencyPrefix())) {
      throw new HttpException(
          400, "the currency to be sent must contain the destination network schema");
    }
  }

  /**
   * API request that sends a Circle transfer, i.e. a payment from a Circle account to another
   * Circle account or to a blockchain wallet.
   *
   * @param sourceAccount the account where the payment will be sent from.
   * @param destinationAccount the account that will receive the payment, it can be an internal
   *     Circle account or a Stellar wallet.
   * @param balance the balance to be transferred.
   * @return asynchronous stream with the payment object.
   * @throws HttpException If the Circle http response status code is 4xx or 5xx.
   * @throws HttpException If the destination network is not a Circle account nor a Stellar wallet.
   */
  private Mono<Payment> sendTransfer(
      Account sourceAccount, Account destinationAccount, CircleBalance balance)
      throws HttpException {
    CircleTransactionParty source = CircleTransactionParty.wallet(sourceAccount.getId());
    CircleTransactionParty destination;
    switch (destinationAccount.paymentNetwork) {
      case CIRCLE:
        destination = CircleTransactionParty.wallet(destinationAccount.getId());
        break;
      case STELLAR:
        destination =
            CircleTransactionParty.stellar(destinationAccount.id, destinationAccount.getIdTag());
        break;
      default:
        throw new HttpException(
            400, "the destination network is not supported for Circle transfers");
    }
    CircleSendTransactionRequest req =
        CircleSendTransactionRequest.forTransfer(
            source, destination, balance, UUID.randomUUID().toString());
    String jsonBody = gson.toJson(req);

    return getWebClient(true)
        .post()
        .uri("/v1/transfers")
        .send(ByteBufMono.fromString(Mono.just(jsonBody)))
        .responseSingle(handleResponseSingle())
        .flatMap(body -> Mono.zip(getDistributionAccountAddress(), Mono.just(body)))
        .map(
            args -> {
              String distributionAccountId = args.getT1();
              String body = args.getT2();
              Type type = new TypeToken<CircleDetailResponse<CircleTransfer>>() {}.getType();
              CircleDetailResponse<CircleTransfer> transfer = gson.fromJson(body, type);
              return transfer.getData().toPayment(distributionAccountId);
            });
  }

  /**
   * API request that sends a Circle payout, i.e. a payment from a Circle wallet to a bank account
   * registered in Circle.
   *
   * @param sourceAccount the account where the payment will be sent from.
   * @param destinationAccount the bank wire account that will receive the payment.
   * @param balance the balance to be transferred.
   * @return asynchronous stream with the payment object.
   * @throws HttpException If the Circle http response status code is 4xx or 5xx.
   * @throws HttpException If the destination network is not a bank wire.
   */
  private Mono<Payment> sendPayout(
      Account sourceAccount, Account destinationAccount, CircleBalance balance)
      throws HttpException {
    if (destinationAccount.paymentNetwork != PaymentNetwork.BANK_WIRE) {
      throw new HttpException(
          500, "something went wrong, the destination account network is invalid");
    }

    CircleTransactionParty source = CircleTransactionParty.wallet(sourceAccount.getId());
    CircleTransactionParty destination =
        CircleTransactionParty.wire(destinationAccount.id, destinationAccount.idTag);
    CircleSendTransactionRequest req =
        CircleSendTransactionRequest.forPayout(
            source, destination, balance, UUID.randomUUID().toString());
    String jsonBody = gson.toJson(req);

    return getWebClient(true)
        .post()
        .uri("/v1/payouts")
        .send(ByteBufMono.fromString(Mono.just(jsonBody)))
        .responseSingle(handleResponseSingle())
        .map(
            body -> {
              Type type = new TypeToken<CircleDetailResponse<CirclePayout>>() {}.getType();
              CircleDetailResponse<CirclePayout> payout = gson.fromJson(body, type);
              return payout.getData().toPayment();
            });
  }

  private void updatePaymentWireCapability(Payment payment, String distributionAccountId) {
    if (distributionAccountId == null) {
      return;
    }

    // fill source account level
    Account sourceAcc = payment.getSourceAccount();
    sourceAcc.capabilities.set(
        PaymentNetwork.BANK_WIRE, distributionAccountId.equals(sourceAcc.id));

    // fill destination account level
    Account destinationAcc = payment.getDestinationAccount();
    Boolean isDestinationWireEnabled =
        destinationAcc.paymentNetwork.equals(PaymentNetwork.BANK_WIRE)
            || distributionAccountId.equals(destinationAcc.id);
    destinationAcc.capabilities.set(PaymentNetwork.BANK_WIRE, isDestinationWireEnabled);
  }

  /**
   * API request that executes a payment between accounts. The APIKey needs to have access to the
   * source account for this request to succeed.
   *
   * @param sourceAccount the account making the payment. Only the network and id fields are needed.
   * @param destinationAccount the account receiving the payment. The network field and a subset of
   *     (id, address and addressTag) may be needed.
   * @param currencyName the name of the currency used in the payment. It should obey the
   *     {scheme}:{identifier} format described in <a
   *     href="https://stellar.org/protocol/sep-38#asset-identification-format">SEP-38</a>.
   * @param amount the payment amount.
   * @return asynchronous stream with the payment object.
   * @throws HttpException If the provided input parameters are invalid.
   * @throws HttpException If the http response status code is 4xx or 5xx.
   */
  public Mono<Payment> sendPayment(
      Account sourceAccount, Account destinationAccount, String currencyName, BigDecimal amount)
      throws HttpException {
    // validate input
    validateSendPaymentInput(sourceAccount, destinationAccount, currencyName);

    String rawCurrencyName =
        currencyName.replace(destinationAccount.paymentNetwork.getCurrencyPrefix() + ":", "");
    CircleBalance circleBalance =
        new CircleBalance(rawCurrencyName, amount.toString(), stellarNetwork);

    switch (destinationAccount.paymentNetwork) {
      case CIRCLE:
      case STELLAR:
        return sendTransfer(sourceAccount, destinationAccount, circleBalance);
      case BANK_WIRE:
        return sendPayout(sourceAccount, destinationAccount, circleBalance);
      default:
        throw new RuntimeException(
            "unsupported destination network '" + destinationAccount.paymentNetwork + "'");
    }
  }

  public Mono<CircleListResponse<CircleBlockchainAddress>> getListOfAddresses(
      @NonNull String walletId) {
    return getWebClient(true)
        .get()
        .uri("/v1/wallets/" + walletId + "/addresses")
        .responseSingle(handleResponseSingle())
        .map(
            body -> {
              Type type = new TypeToken<CircleListResponse<CircleBlockchainAddress>>() {}.getType();
              return gson.fromJson(body, type);
            });
  }

  public Mono<CircleDetailResponse<CircleBlockchainAddress>> createNewStellarAddress(
      @NonNull String walletId) {
    JsonObject postBody = new JsonObject();
    postBody.addProperty("idempotencyKey", UUID.randomUUID().toString());
    postBody.addProperty("currency", "USD");
    postBody.addProperty("chain", "XLM");

    return getWebClient(true)
        .post()
        .send(ByteBufMono.fromString(Mono.just(postBody.toString())))
        .uri("/v1/wallets/" + walletId + "/addresses")
        .responseSingle(handleResponseSingle())
        .map(
            body -> {
              Type type =
                  new TypeToken<CircleDetailResponse<CircleBlockchainAddress>>() {}.getType();
              return gson.fromJson(body, type);
            });
  }

  public Mono<CircleBlockchainAddress> getOrCreateStellarAddress(@NonNull String walletId) {
    return getListOfAddresses(walletId)
        .flatMap(
            addressListResponse -> {
              for (CircleBlockchainAddress address : addressListResponse.getData()) {
                if (address.getChain().equals("XLM") && address.getCurrency().equals("USD")) {
                  return Mono.just(address);
                }
              }

              return createNewStellarAddress(walletId)
                  .map(CircleDetailResponse<CircleBlockchainAddress>::getData);
            });
  }

  public Mono<CircleDetailResponse<CircleWireDepositInstructions>> getWireDepositInstructions(
      @NonNull String walletId, @NonNull String bankWireId) {
    return getDistributionAccountAddress()
        .flatMap(
            distributionAccountId -> {
              if (!distributionAccountId.equals(walletId)) {
                return Mono.error(
                    new HttpException(
                        400,
                        "in circle, only the distribution account id can receive wire payments"));
              }

              return getWebClient(true)
                  .get()
                  .uri("/v1/banks/wires/" + bankWireId + "/instructions")
                  .responseSingle(handleResponseSingle())
                  .map(
                      body -> {
                        Type type =
                            new TypeToken<
                                CircleDetailResponse<CircleWireDepositInstructions>>() {}.getType();
                        return gson.fromJson(body, type);
                      });
            });
  }

  private void validateDepositRequirements(@NonNull DepositRequirements config)
      throws HttpException {
    String beneficiaryId = config.getBeneficiaryAccountId();
    if (beneficiaryId == null || beneficiaryId.isEmpty()) {
      throw new HttpException(400, "beneficiary account id cannot be empty");
    }

    if (!"circle:USD".equals(config.getBeneficiaryCurrencyName())) {
      throw new HttpException(
          400, "the only receiving currency in a circle account is \"circle:USD\"");
    }

    PaymentNetwork intermediaryNetwork = config.getIntermediaryPaymentNetwork();
    if (intermediaryNetwork == null
        || !List.of(PaymentNetwork.STELLAR, PaymentNetwork.CIRCLE, PaymentNetwork.BANK_WIRE)
            .contains(intermediaryNetwork)) {
      throw new HttpException(
          400,
          "the only supported intermediary payment networks are \"stellar\", \"circle\" and \"bank_wire\"");
    }

    if (PaymentNetwork.BANK_WIRE.equals(intermediaryNetwork)
        && config.getIntermediaryAccountId() == null) {
      throw new HttpException(
          400,
          "please provide a valid Circle bank id for the intermediaryAccountId field when requesting instructions for bank wire deposits");
    }
  }

  /**
   * API request that returns the info needed to make a deposit into a user account. This method
   * will be needed if the implementation allows users to make deposits using external networks. For
   * instance, when a user wants to make a deposit to their Circle account through a Stellar
   * payment:
   *
   * <pre>{@code
   * // Here we want to check how we can top up a Circle account using USDC funds from the Stellar network.
   * String circleWalletId = "1000066041";
   * Network fromNetwork = Network.STELLAR;
   * String currencyName = "USD";  // or "USDC"
   * DepositConfiguration config = new DepositConfiguration(circleWalletId, fromNetwork, currencyName);
   *
   * // Here are the instructions with the Stellar account that will receive the payment:
   * DepositInfo depositInfo = getInfoForDeposit(config).block();
   * System.out.println("PublicKey: " + depositInfo.accountId);        // "PublicKey: G..."
   * System.out.println("Memo: " + depositInfo.accountIdTag);          // "Memo: 2454278437550473431"
   * System.out.println("Network: " + depositInfo.network);            // "Network: stellar"
   * System.out.println("CurrencyName: " + depositInfo.currencyName);  // "CurrencyName: stellar:USDC:<circle-issuer>"
   * System.out.println("Extra: " + depositInfo.extra);                // "Extra: null"
   * }</pre>
   *
   * @param config an object containing all configuration options needed for an external user to
   *     make a deposit to the desired internal account. Different fields may be mandatory depending
   *     on the interface implementation.
   * @return asynchronous stream with the info needed to make the deposit.
   * @throws HttpException If the http response status code is 4xx or 5xx or if the configuration is
   *     not supported by the network.
   */
  public Mono<DepositInstructions> getDepositInstructions(DepositRequirements config)
      throws HttpException {
    validateDepositRequirements(config);

    String walletId = config.getBeneficiaryAccountId();
    switch (config.getIntermediaryPaymentNetwork()) {
      case STELLAR:
        return getOrCreateStellarAddress(walletId)
            .map(address -> address.toDepositInstructions(walletId, stellarNetwork));

      case CIRCLE:
        return Mono.just(new CircleWallet(walletId).toDepositInstructions());

      case BANK_WIRE:
        return getWireDepositInstructions(walletId, config.getIntermediaryAccountId())
            .map(response -> response.getData().toDepositInstructions(walletId));

      default:
        return null;
    }
  }
}
