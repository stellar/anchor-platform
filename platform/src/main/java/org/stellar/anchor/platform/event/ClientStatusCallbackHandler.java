package org.stellar.anchor.platform.event;

import static org.stellar.anchor.sep24.Sep24Helper.fromTxn;
import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.errorF;
import static org.stellar.anchor.util.NetUtil.getDomainFromURL;
import static org.stellar.anchor.util.OkHttpUtil.buildJsonRequestBody;
import static org.stellar.anchor.util.StringHelper.json;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.platform.GetTransactionResponse;
import org.stellar.anchor.api.sep.sep24.Sep24GetTransactionResponse;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.config.ClientsConfig.ClientConfig;
import org.stellar.anchor.config.SecretConfig;
import org.stellar.anchor.platform.data.*;
import org.stellar.anchor.sep24.*;
import org.stellar.anchor.sep24.MoreInfoUrlConstructor;
import org.stellar.anchor.sep24.Sep24Transaction;
import org.stellar.anchor.sep31.RefundPayment;
import org.stellar.anchor.sep31.Sep31Refunds;
import org.stellar.anchor.sep31.Sep31Transaction;
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
  private final MoreInfoUrlConstructor moreInfoUrlConstructor;

  public ClientStatusCallbackHandler(
      SecretConfig secretConfig,
      ClientConfig clientConfig,
      AssetService assetService,
      MoreInfoUrlConstructor moreInfoUrlConstructor) {
    super();
    this.secretConfig = secretConfig;
    this.clientConfig = clientConfig;
    this.assetService = assetService;
    this.moreInfoUrlConstructor = moreInfoUrlConstructor;
  }

  @Override
  boolean handleEvent(AnchorEvent event) throws IOException {
    if (event.getTransaction() != null) {
      KeyPair signer = KeyPair.fromSecretSeed(secretConfig.getSep10SigningSeed());
      Request request = buildHttpRequest(signer, event);
      Response response = httpClient.newCall(request).execute();
      debugF(
          "Sending event: {} to client status api: {}", json(event), clientConfig.getCallbackUrl());
      if (response.code() < 200 || response.code() >= 400) {
        errorF("Failed to send event to client status API. Error code: {}", response.code());
        return false;
      }
    }
    return true;
  }

  @SneakyThrows
  Request buildHttpRequest(KeyPair signer, AnchorEvent event) {
    String payload = getPayload(event);
    return buildHttpRequest(signer, payload, clientConfig.getCallbackUrl());
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

  private String getPayload(AnchorEvent event)
      throws AnchorException, MalformedURLException, URISyntaxException {
    switch (event.getTransaction().getSep()) {
      case SEP_24:
        Sep24Transaction sep24Txn = fromSep24Txn(event.getTransaction());
        Sep24GetTransactionResponse txn24Response =
            Sep24GetTransactionResponse.of(fromTxn(assetService, moreInfoUrlConstructor, sep24Txn));
        return json(txn24Response);
      case SEP_31:
        Sep31Transaction sep31Txn = fromSep31Txn(event.getTransaction());
        return json(sep31Txn.toSep31GetTransactionResponse());
      default:
        throw new SepException("Only SEP-24 and SEP-31 are supported");
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
