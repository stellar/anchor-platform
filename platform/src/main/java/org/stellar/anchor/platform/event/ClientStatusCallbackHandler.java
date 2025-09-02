package org.stellar.anchor.platform.event;

import static org.stellar.anchor.sep24.Sep24Helper.fromTxn;
import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.errorF;
import static org.stellar.anchor.util.NetUtil.getDomainFromURL;
import static org.stellar.anchor.util.OkHttpUtil.buildJsonRequestBody;
import static org.stellar.anchor.util.StringHelper.json;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.SneakyThrows;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.stellar.anchor.MoreInfoUrlConstructor;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.InternalServerErrorException;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.platform.GetTransactionResponse;
import org.stellar.anchor.api.sep.sep24.Sep24GetTransactionResponse;
import org.stellar.anchor.api.sep.sep6.Sep6GetTransactionResponse;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.client.ClientConfig;
import org.stellar.anchor.config.SecretConfig;
import org.stellar.anchor.platform.data.*;
import org.stellar.anchor.sep24.*;
import org.stellar.anchor.sep24.Sep24Transaction;
import org.stellar.anchor.sep31.RefundPayment;
import org.stellar.anchor.sep31.Sep31Refunds;
import org.stellar.anchor.sep31.Sep31Transaction;
import org.stellar.anchor.sep6.Sep6Transaction;
import org.stellar.anchor.sep6.Sep6TransactionUtils;
import org.stellar.anchor.util.Log;
import org.stellar.sdk.KeyPair;

public class ClientStatusCallbackHandler extends EventHandler {
  private static final OkHttpClient httpClient =
      new OkHttpClient.Builder()
          .connectTimeout(10, TimeUnit.MINUTES)
          .readTimeout(10, TimeUnit.MINUTES)
          .writeTimeout(10, TimeUnit.MINUTES)
          .callTimeout(10, TimeUnit.MINUTES)
          .build();
  private final SecretConfig secretConfig;
  private final ClientConfig clientConfig;
  private final AssetService assetService;
  private final MoreInfoUrlConstructor sep6MoreInfoUrlConstructor;
  private final MoreInfoUrlConstructor sep24MoreInfoUrlConstructor;

  public ClientStatusCallbackHandler(
      SecretConfig secretConfig,
      ClientConfig clientConfig,
      AssetService assetService,
      MoreInfoUrlConstructor sep6MoreInfoUrlConstructor,
      MoreInfoUrlConstructor sep24MoreInfoUrlConstructor) {
    super();
    this.secretConfig = secretConfig;
    this.clientConfig = clientConfig;
    this.assetService = assetService;
    this.sep6MoreInfoUrlConstructor = sep6MoreInfoUrlConstructor;
    this.sep24MoreInfoUrlConstructor = sep24MoreInfoUrlConstructor;
  }

  @Override
  boolean handleEvent(AnchorEvent event) throws IOException {
    if (event.getTransaction() != null || event.getCustomer() != null) {
      KeyPair signer = KeyPair.fromSecretSeed(secretConfig.getSep10SigningSeed());
      Request request = buildHttpRequest(signer, event);

      if (request != null) {
        try (Response response = httpClient.newCall(request).execute()) {
          debugF("Sending event: {} to client status api: {}", json(event), request.url());
          if (response.code() < 200 || response.code() >= 400) {
            errorF("Failed to send event to client status API. Error code: {}", response.code());
            return false;
          }
        }
      }
    }
    return true;
  }

  @SneakyThrows
  Request buildHttpRequest(KeyPair signer, AnchorEvent event) {
    String callbackUrl = getCallbackUrl(event);
    if (callbackUrl == null) {
      Log.debugF(
          "No callback URL found for event: {} for client: {}",
          json(event),
          clientConfig.getName());
      return null;
    }

    String payload = getPayload(event);
    return buildHttpRequest(signer, payload, callbackUrl);
  }

  @Nullable
  String getCallbackUrl(AnchorEvent event) throws InvalidConfigException {
    String callbackUrl = null;
    if (event.getTransaction() != null) {
      switch (event.getTransaction().getSep()) {
        case SEP_6:
          if (!StringUtils.isEmpty(
              Optional.ofNullable(clientConfig.getCallbackUrls())
                  .map(ClientConfig.CallbackUrls::getSep6)
                  .orElse(""))) {
            callbackUrl = clientConfig.getCallbackUrls().getSep6();
          }
          break;
        case SEP_24:
          if (!StringUtils.isEmpty(
              Optional.ofNullable(clientConfig.getCallbackUrls())
                  .map(ClientConfig.CallbackUrls::getSep24)
                  .orElse(""))) {
            callbackUrl = clientConfig.getCallbackUrls().getSep24();
          }
          break;
        case SEP_31:
          if (!StringUtils.isEmpty(
              Optional.ofNullable(clientConfig.getCallbackUrls())
                  .map(ClientConfig.CallbackUrls::getSep31)
                  .orElse(""))) {
            callbackUrl = clientConfig.getCallbackUrls().getSep31();
          }
          break;
        default:
          throw new InvalidConfigException(
              String.format("Unsupported SEP: %s", event.getTransaction().getSep()));
      }
    } else if (event.getCustomer() != null) {
      if (!StringUtils.isEmpty(
          Optional.ofNullable(clientConfig.getCallbackUrls())
              .map(ClientConfig.CallbackUrls::getSep12)
              .orElse(""))) {
        callbackUrl = clientConfig.getCallbackUrls().getSep12();
      }
    }
    return callbackUrl;
  }

  @SneakyThrows
  public static Request buildHttpRequest(KeyPair signer, String payload, String url) {
    // Prepare the payload to sign
    String domain = getDomainFromURL(url);
    String currentTs = String.valueOf(TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()));
    String payloadToSign = currentTs + "." + domain + "." + payload;
    // Sign the payload using the Anchor private key
    // Base64 encode the signature
    String encodedSignature =
        new String(Base64.getEncoder().encode(signer.sign(payloadToSign.getBytes())));

    // Build the X-Stellar-Signature header
    return new Request.Builder()
        .url(url)
        .header("Signature", String.format("t=%s, s=%s", currentTs, encodedSignature))
        .post(buildJsonRequestBody(payload))
        .build();
  }

  private String getPayload(AnchorEvent event) throws AnchorException {
    if (event.getTransaction() != null) {
      switch (event.getTransaction().getSep()) {
        case SEP_6:
          Sep6Transaction sep6Txn = fromSep6Txn(event.getTransaction());
          Sep6GetTransactionResponse sep6TxnRes =
              new Sep6GetTransactionResponse(
                  Sep6TransactionUtils.fromTxn(sep6Txn, sep6MoreInfoUrlConstructor, null));
          return json(sep6TxnRes);
        case SEP_24:
          Sep24Transaction sep24Txn = fromSep24Txn(event.getTransaction());
          Sep24GetTransactionResponse txn24Response =
              Sep24GetTransactionResponse.of(
                  fromTxn(assetService, sep24MoreInfoUrlConstructor, sep24Txn, null));
          return json(txn24Response);
        case SEP_31:
          Sep31Transaction sep31Txn = fromSep31Txn(event.getTransaction());
          return json(sep31Txn.toSep31GetTransactionResponse());
        default:
          throw new SepException(
              String.format("Unsupported SEP: %s", event.getTransaction().getSep()));
      }
    } else if (event.getCustomer() != null) {
      return json(event.getCustomer());
    } else {
      throw new InternalServerErrorException("Event must have either a transaction or a customer");
    }
  }

  static Sep6Transaction fromSep6Txn(GetTransactionResponse txn) {
    JdbcSep6Transaction sep6Txn = new JdbcSep6Transaction();
    sep6Txn.setId(txn.getId());
    sep6Txn.setTransactionId(txn.getId());
    if (txn.getStellarTransactions() != null && !txn.getStellarTransactions().isEmpty()) {
      sep6Txn.setStellarTransactionId(txn.getStellarTransactions().get(0).getId());
    }
    sep6Txn.setExternalTransactionId(txn.getExternalTransactionId());
    sep6Txn.setStatus(txn.getStatus().getStatus());
    sep6Txn.setKind(txn.getKind().kind);
    sep6Txn.setStartedAt(txn.getStartedAt());
    sep6Txn.setCompletedAt(txn.getCompletedAt());
    sep6Txn.setTransferReceivedAt(txn.getTransferReceivedAt());
    sep6Txn.setType(txn.getType());
    if (txn.getAmountIn() != null) {
      sep6Txn.setAmountIn(txn.getAmountIn().getAmount());
      sep6Txn.setAmountInAsset(txn.getAmountIn().getAsset());
    }
    if (txn.getAmountOut() != null) {
      sep6Txn.setAmountOut(txn.getAmountOut().getAmount());
      sep6Txn.setAmountOutAsset(txn.getAmountOut().getAsset());
    }
    if (txn.getFeeDetails() != null) {
      sep6Txn.setFeeDetails(txn.getFeeDetails());
    }
    if (txn.getAmountExpected() != null) {
      sep6Txn.setAmountExpected(txn.getAmountExpected().getAmount());
    }
    sep6Txn.setFromAccount(txn.getSourceAccount());
    sep6Txn.setToAccount(txn.getDestinationAccount());
    sep6Txn.setMemo(txn.getMemo());
    sep6Txn.setMemoType(txn.getMemoType());
    sep6Txn.setClientDomain(txn.getClientDomain());
    sep6Txn.setQuoteId(txn.getQuoteId());
    sep6Txn.setMessage(txn.getMessage());
    sep6Txn.setRefunds(txn.getRefunds());
    sep6Txn.setRefundMemo(txn.getRefundMemo());
    sep6Txn.setRefundMemoType(txn.getRefundMemoType());
    sep6Txn.setRequiredInfoMessage(txn.getRequiredInfoMessage());
    sep6Txn.setRequiredInfoUpdates(txn.getRequiredInfoUpdates());
    sep6Txn.setInstructions(txn.getInstructions());

    return sep6Txn;
  }

  static Sep24Transaction fromSep24Txn(GetTransactionResponse txn) {
    JdbcSep24Transaction sep24Txn = new JdbcSep24Transaction();
    sep24Txn.setId(txn.getId());
    sep24Txn.setTransactionId(txn.getId());
    sep24Txn.setKind(txn.getKind().kind);
    sep24Txn.setStatus(txn.getStatus().getStatus());
    if (txn.getAmountIn() != null) {
      sep24Txn.setAmountIn(txn.getAmountIn().getAmount());
      sep24Txn.setAmountInAsset(txn.getAmountIn().getAsset());
    }
    if (txn.getAmountOut() != null) {
      sep24Txn.setAmountOut(txn.getAmountOut().getAmount());
      sep24Txn.setAmountOutAsset(txn.getAmountOut().getAsset());
    }
    if (txn.getAmountExpected() != null) {
      sep24Txn.setAmountExpected(txn.getAmountExpected().getAmount());
    }
    if (txn.getFeeDetails() != null) {
      sep24Txn.setFeeDetails(txn.getFeeDetails());
    }
    sep24Txn.setStartedAt(txn.getStartedAt());
    sep24Txn.setCompletedAt(txn.getCompletedAt());
    sep24Txn.setExternalTransactionId(txn.getExternalTransactionId());
    sep24Txn.setMessage(txn.getMessage());
    sep24Txn.setFromAccount(txn.getSourceAccount());
    sep24Txn.setToAccount(txn.getDestinationAccount());
    sep24Txn.setMemo(txn.getMemo());
    sep24Txn.setMemoType(txn.getMemoType());
    sep24Txn.setClientDomain(txn.getClientDomain());
    sep24Txn.setQuoteId(txn.getQuoteId());

    if (txn.getRefunds() != null) {
      List<Sep24RefundPayment> paymentList =
          Arrays.stream(txn.getRefunds().getPayments())
              .map(
                  refundPayment -> {
                    Sep24RefundPayment payment = new JdbcSep24RefundPayment();
                    payment.setAmount(refundPayment.getAmount().getAmount());
                    payment.setFee(refundPayment.getFee().getAmount());
                    payment.setId(refundPayment.getId());
                    payment.setIdType(refundPayment.getIdType().toString());
                    return payment;
                  })
              .collect(Collectors.toList());
      Sep24Refunds refunds = new JdbcSep24Refunds();
      refunds.setAmountRefunded(txn.getRefunds().getAmountRefunded().getAmount());
      refunds.setAmountFee(txn.getRefunds().getAmountFee().getAmount());
      refunds.setRefundPayments(paymentList);
      sep24Txn.setRefunds(refunds);
    }

    return sep24Txn;
  }

  static Sep31Transaction fromSep31Txn(GetTransactionResponse txn) {
    JdbcSep31Transaction sep31Txn = new JdbcSep31Transaction();
    sep31Txn.setId(txn.getId());
    sep31Txn.setStatus(txn.getStatus().getStatus());
    if (txn.getAmountIn() != null) {
      sep31Txn.setAmountIn(txn.getAmountIn().getAmount());
      sep31Txn.setAmountInAsset(txn.getAmountIn().getAsset());
    }
    if (txn.getAmountOut() != null) {
      sep31Txn.setAmountOut(txn.getAmountOut().getAmount());
      sep31Txn.setAmountOutAsset(txn.getAmountOut().getAsset());
    }
    if (txn.getAmountExpected() != null) {
      sep31Txn.setAmountExpected(txn.getAmountExpected().getAmount());
    }
    if (txn.getFeeDetails() != null) {
      sep31Txn.setFeeDetails(txn.getFeeDetails());
    }
    sep31Txn.setStartedAt(txn.getStartedAt());
    sep31Txn.setCompletedAt(txn.getCompletedAt());
    sep31Txn.setExternalTransactionId(txn.getExternalTransactionId());
    sep31Txn.setRequiredInfoMessage(txn.getMessage());
    sep31Txn.setStellarMemo(txn.getMemo());
    sep31Txn.setStellarMemoType(txn.getMemoType());
    sep31Txn.setFromAccount(txn.getSourceAccount());
    sep31Txn.setToAccount(txn.getDestinationAccount());
    sep31Txn.setClientDomain(txn.getClientDomain());
    sep31Txn.setQuoteId(txn.getQuoteId());

    if (txn.getRefunds() != null) {
      List<RefundPayment> paymentList =
          Arrays.stream(txn.getRefunds().getPayments())
              .map(
                  refundPayment -> {
                    RefundPayment payment = new JdbcSep31RefundPayment();
                    payment.setAmount(refundPayment.getAmount().getAmount());
                    payment.setFee(refundPayment.getFee().getAmount());
                    payment.setId(refundPayment.getId());

                    return payment;
                  })
              .collect(Collectors.toList());
      Sep31Refunds refunds = new JdbcSep31Refunds();
      refunds.setAmountRefunded(txn.getRefunds().getAmountRefunded().getAmount());
      refunds.setAmountFee(txn.getRefunds().getAmountFee().getAmount());
      refunds.setRefundPayments(paymentList);
      sep31Txn.setRefunds(refunds);
    }

    return sep31Txn;
  }
}
