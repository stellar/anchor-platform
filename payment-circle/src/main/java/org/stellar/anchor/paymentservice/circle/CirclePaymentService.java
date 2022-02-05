package org.stellar.anchor.paymentservice.circle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.commons.validator.routines.EmailValidator;
import org.stellar.anchor.exception.HttpException;
import org.stellar.anchor.paymentservice.*;
import org.stellar.anchor.paymentservice.circle.model.CircleBalance;
import org.stellar.anchor.paymentservice.circle.model.CircleTransactionParty;
import org.stellar.anchor.paymentservice.circle.model.CircleWallet;
import org.stellar.anchor.paymentservice.circle.model.request.CircleSendTransactionRequest;
import org.stellar.anchor.paymentservice.circle.model.response.*;
import org.stellar.anchor.paymentservice.circle.util.NettyHttpClient;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.util.annotation.NonNull;
import reactor.util.annotation.Nullable;

public class CirclePaymentService implements PaymentService {
  private static final Gson gson = new Gson();

  private final Network network = Network.CIRCLE;

  private String url;

  private String secretKey;

  private HttpClient webClient;

  private String mainAccountAddress;

  /**
   * For all service methods to work correctly, make sure your circle account has a valid business
   * wallet and a bank account configured.
   */
  public CirclePaymentService(String url, String secretKey) {
    super();
    this.url = url;
    this.secretKey = secretKey;
  }

  public Network getNetwork() {
    return this.network;
  }

  public String getUrl() {
    return this.url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getSecretKey() {
    return this.secretKey;
  }

  public void setSecretKey(String secretKey) {
    this.secretKey = secretKey;
    this.mainAccountAddress = null;
  }

  private HttpClient getWebClient(boolean authenticated) {
    if (webClient == null) {
      this.webClient = NettyHttpClient.withBaseUrl(getUrl());
    }
    if (!authenticated) {
      return webClient;
    }
    return webClient.headers(h -> h.add(HttpHeaderNames.AUTHORIZATION, "Bearer " + getSecretKey()));
  }

  @NonNull
  private <T> Mono<T> handleCircleError(HttpClientResponse response, ByteBufMono bodyBytesMono) {
    return bodyBytesMono
        .asString()
        .map(
            body -> {
              CircleError circleError = gson.fromJson(body, CircleError.class);
              throw new HttpException(
                  response.status().code(),
                  circleError.getMessage(),
                  circleError.getCode().toString());
            });
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
        .responseSingle(
            (response, bodyBytesMono) -> {
              if (response.status().code() >= 400) {
                return handleCircleError(response, bodyBytesMono);
              }

              return Mono.empty();
            });
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
        .responseSingle(
            (response, bodyBytesMono) -> {
              if (response.status().code() >= 400) {
                return handleCircleError(response, bodyBytesMono);
              }

              return bodyBytesMono.asString();
            })
        .map(
            body -> {
              CircleConfigurationResponse response =
                  gson.fromJson(body, CircleConfigurationResponse.class);
              mainAccountAddress = response.data.payments.masterWalletId;
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
        .responseSingle(
            (response, bodyBytesMono) -> {
              if (response.status().code() >= 400) {
                return handleCircleError(response, bodyBytesMono);
              }

              return bodyBytesMono.asString();
            })
        .map(
            body -> {
              CircleAccountBalancesResponse response =
                  gson.fromJson(body, CircleAccountBalancesResponse.class);

              List<Balance> unsettledBalances = new ArrayList<>();
              for (CircleBalance uBalance : response.getData().unsettled) {
                unsettledBalances.add(uBalance.toBalance());
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
        .responseSingle(
            (response, bodyBytesMono) -> {
              if (response.status().code() >= 400) {
                return handleCircleError(response, bodyBytesMono);
              }

              return bodyBytesMono.asString();
            })
        .map(
            body -> {
              CircleWalletResponse circleWalletResponse =
                  gson.fromJson(body, CircleWalletResponse.class);
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
        .responseSingle(
            (postResponse, bodyBytesMono) -> {
              if (postResponse.status().code() >= 400) {
                return handleCircleError(postResponse, bodyBytesMono);
              }

              return bodyBytesMono.asString();
            })
        .map(
            body -> {
              CircleWalletResponse circleWalletResponse =
                  gson.fromJson(body, CircleWalletResponse.class);
              CircleWallet circleWallet = circleWalletResponse.getData();
              return circleWallet.toAccount();
            });
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
    // TODO: implement
    return null;
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
    if (sourceAccount.network != Network.CIRCLE) {
      throw new HttpException(400, "the only supported network for the source account is circle");
    }
    if (!List.of(Network.CIRCLE, Network.STELLAR, Network.BANK_WIRE)
        .contains(destinationAccount.network)) {
      throw new HttpException(
          400,
          "the only supported networks for the destination account are circle, stellar and bank_wire");
    }
    if (destinationAccount.network == Network.BANK_WIRE
        && !EmailValidator.getInstance().isValid(destinationAccount.idTag)) {
      throw new HttpException(
          400,
          "for bank transfers, please provide a valid beneficiary email address in the destination idTag");
    }
    if (!currencyName.startsWith(destinationAccount.network.getCurrencyPrefix())) {
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
    switch (destinationAccount.network) {
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

    Mono<Payment> sendTransferMono =
        getWebClient(true)
            .post()
            .uri("/v1/transfers")
            .send(ByteBufMono.fromString(Mono.just(jsonBody)))
            .responseSingle(
                (postResponse, bodyBytesMono) -> {
                  if (postResponse.status().code() >= 400) {
                    return handleCircleError(postResponse, bodyBytesMono);
                  }

                  return bodyBytesMono.asString();
                })
            .map(
                body -> {
                  CircleTransferResponse transfer =
                      gson.fromJson(body, CircleTransferResponse.class);
                  return transfer.getData().toPayment();
                });

    return updatePaymentWireCapability(sendTransferMono);
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
    if (destinationAccount.network != Network.BANK_WIRE) {
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

    Mono<Payment> sendPayoutMono =
        getWebClient(true)
            .post()
            .uri("/v1/payouts")
            .send(ByteBufMono.fromString(Mono.just(jsonBody)))
            .responseSingle(
                (postResponse, bodyBytesMono) -> {
                  if (postResponse.status().code() >= 400) {
                    return handleCircleError(postResponse, bodyBytesMono);
                  }

                  return bodyBytesMono.asString();
                })
            .map(
                body -> {
                  CirclePayoutResponse payout = gson.fromJson(body, CirclePayoutResponse.class);
                  return payout.getData().toPayment();
                });

    return updatePaymentWireCapability(sendPayoutMono);
  }

  /**
   * Executes a Mono&lt;Payment&gt; reactive stream and updates the resulting payment source &
   * destination accounts BANK_WIRE capability if any of them is the distribution account.
   *
   * @param sendPaymentMono Is a reactive stream that returns a Payment object.
   * @return a Mono&lt;Payment&gt; the same result from the input parameter with updated accounts
   *     BANK_WIRE capabilities.
   */
  private Mono<Payment> updatePaymentWireCapability(Mono<Payment> sendPaymentMono) {
    return Mono.zip(getDistributionAccountAddress(), sendPaymentMono)
        .map(
            args -> {
              String distributionAccountId = args.getT1();
              Payment payment = args.getT2();

              // fill source account level
              Account sourceAcc = payment.getSourceAccount();
              sourceAcc.capabilities.set(
                  Network.BANK_WIRE, sourceAcc.id.equals(distributionAccountId));

              // fill destination account level
              Account destinationAcc = payment.getDestinationAccount();
              Boolean isDestinationWireEnabled =
                  destinationAcc.network.equals(Network.BANK_WIRE)
                      || destinationAcc.id.equals(distributionAccountId);
              destinationAcc.capabilities.set(Network.BANK_WIRE, isDestinationWireEnabled);

              return payment;
            });
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
        currencyName.replace(destinationAccount.network.getCurrencyPrefix() + ":", "");
    CircleBalance circleBalance = new CircleBalance(rawCurrencyName, amount.toString());

    switch (destinationAccount.network) {
      case CIRCLE:
      case STELLAR:
        return sendTransfer(sourceAccount, destinationAccount, circleBalance);
      case BANK_WIRE:
        return sendPayout(sourceAccount, destinationAccount, circleBalance);
      default:
        throw new RuntimeException(
            "unsupported destination network '" + destinationAccount.network + "'");
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
    // TODO: implement
    return null;
  }
}
