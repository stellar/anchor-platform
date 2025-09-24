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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.config.ClientsConfig.ClientConfig;
import org.stellar.anchor.config.SecretConfig;
import org.stellar.anchor.platform.data.*;
import org.stellar.anchor.sep24.*;
import org.stellar.anchor.sep24.Sep24Transaction;
import org.stellar.anchor.sep31.RefundPayment;
import org.stellar.anchor.sep31.Sep31Refunds;
import org.stellar.anchor.sep31.Sep31Transaction;
import org.stellar.anchor.sep6.Sep6Transaction;
import org.stellar.anchor.sep6.Sep6TransactionStore;
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
  private final Sep6TransactionStore sep6TransactionStore;
  private final AssetService assetService;
  private final MoreInfoUrlConstructor sep6MoreInfoUrlConstructor;
  private final MoreInfoUrlConstructor sep24MoreInfoUrlConstructor;

  public ClientStatusCallbackHandler(
      SecretConfig secretConfig,
      ClientConfig clientConfig,
      Sep6TransactionStore sep6TransactionStore,
      AssetService assetService,
      MoreInfoUrlConstructor sep6MoreInfoUrlConstructor,
      MoreInfoUrlConstructor sep24MoreInfoUrlConstructor) {
    super();
    this.secretConfig = secretConfig;
    this.clientConfig = clientConfig;
    this.assetService = assetService;
    this.sep6TransactionStore = sep6TransactionStore;
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
      Log.debugF("No callback URL found for event: {}", json(event));
      return null;
    }

    String payload = getPayload(event);
    return buildHttpRequest(signer, payload, callbackUrl);
  }

  String getCallbackUrl(AnchorEvent event) throws InvalidConfigException {
    String callbackUrl = clientConfig.getCallbackUrl();
    if (event.getTransaction() != null) {
      switch (event.getTransaction().getSep()) {
        case SEP_6:
          if (!StringUtils.isEmpty(clientConfig.getCallbackUrlSep6())) {
            callbackUrl = clientConfig.getCallbackUrlSep6();
          }
          break;
        case SEP_24:
          if (!StringUtils.isEmpty(clientConfig.getCallbackUrlSep24())) {
            callbackUrl = clientConfig.getCallbackUrlSep24();
          }
          break;
        case SEP_31:
          if (!StringUtils.isEmpty(clientConfig.getCallbackUrlSep31())) {
            callbackUrl = clientConfig.getCallbackUrlSep31();
          }
          break;
        default:
          throw new InvalidConfigException(
              String.format("Unsupported SEP: %s", event.getTransaction().getSep()));
      }
    } else if (event.getCustomer() != null) {
      if (!StringUtils.isEmpty(clientConfig.getCallbackUrlSep12())) {
        callbackUrl = clientConfig.getCallbackUrlSep12();
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
          // TODO: remove dependence on the transaction store
          Sep6Transaction sep6Txn =
              sep6TransactionStore.findByTransactionId(event.getTransaction().getId());
          org.stellar.anchor.api.sep.sep6.GetTransactionResponse sep6TxnRes =
              new org.stellar.anchor.api.sep.sep6.GetTransactionResponse(
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

  private Sep24Transaction fromSep24Txn(GetTransactionResponse txn) {
    JdbcSep24Transaction sep24Txn = new JdbcSep24Transaction();
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
    if (txn.getAmountFee() != null) {
      sep24Txn.setAmountFee(txn.getAmountFee().getAmount());
      sep24Txn.setAmountFeeAsset(txn.getAmountFee().getAsset());
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

  private Sep31Transaction fromSep31Txn(GetTransactionResponse txn) {
    JdbcSep31Transaction sep24Txn = new JdbcSep31Transaction();
    sep24Txn.setId(txn.getId());
    sep24Txn.setStatus(txn.getStatus().getStatus());
    if (txn.getAmountIn() != null) {
      sep24Txn.setAmountIn(txn.getAmountIn().getAmount());
      sep24Txn.setAmountInAsset(txn.getAmountIn().getAsset());
    }
    if (txn.getAmountOut() != null) {
      sep24Txn.setAmountOut(txn.getAmountOut().getAmount());
      sep24Txn.setAmountOutAsset(txn.getAmountOut().getAsset());
    }
    if (txn.getAmountFee() != null) {
      sep24Txn.setAmountFee(txn.getAmountFee().getAmount());
      sep24Txn.setAmountFeeAsset(txn.getAmountFee().getAsset());
    }
    if (txn.getFeeDetails() != null) {
      sep24Txn.setFeeDetails(txn.getFeeDetails());
    }
    sep24Txn.setStartedAt(txn.getStartedAt());
    sep24Txn.setCompletedAt(txn.getCompletedAt());
    sep24Txn.setExternalTransactionId(txn.getExternalTransactionId());
    sep24Txn.setRequiredInfoMessage(txn.getMessage());
    sep24Txn.setStellarMemo(txn.getMemo());
    sep24Txn.setStellarMemoType(txn.getMemoType());

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
      sep24Txn.setRefunds(refunds);
    }

    return sep24Txn;
  }
}
