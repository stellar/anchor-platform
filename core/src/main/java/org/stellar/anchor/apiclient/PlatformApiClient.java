package org.stellar.anchor.apiclient;

import static org.stellar.anchor.api.rpc.method.RpcMethod.*;

import com.google.gson.JsonParseException;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.data.domain.Sort;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.platform.*;
import org.stellar.anchor.api.rpc.RpcErrorCode;
import org.stellar.anchor.api.rpc.RpcRequest;
import org.stellar.anchor.api.rpc.RpcResponse;
import org.stellar.anchor.api.rpc.method.*;
import org.stellar.anchor.api.rpc.method.NotifyRefundSentRequest.Refund;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.auth.AuthHelper;
import org.stellar.anchor.util.OkHttpUtil;
import org.stellar.anchor.util.RpcHelper;

/** The client for the PlatformAPI endpoints. */
public class PlatformApiClient extends BaseApiClient {
  public static final String JSON_RPC_VERSION = "2.0";

  public PlatformApiClient(AuthHelper authHelper, String endpoint) {
    super(authHelper, endpoint);
  }

  /**
   * Get the transaction with the given id by calling the /transactions/{id} endpoint.
   *
   * @param id the id of the transaction to get.
   * @return the GetTransactionResponse.
   * @throws IOException if the request fails due to IO errors.
   * @throws AnchorException if the response is not successful.
   */
  @Deprecated // ANCHOR-641 Use getTransactionByRpc instead
  public GetTransactionResponse getTransaction(String id) throws IOException, AnchorException {
    Request request = getRequestBuilder().url(endpoint + "/transactions/" + id).get().build();
    String responseBody = handleResponse(client.newCall(request).execute());
    return gson.fromJson(responseBody, GetTransactionResponse.class);
  }

  public GetTransactionResponse getTransactionByRpc(String id) throws IOException, AnchorException {
    GetTransactionRpcRequest requestParams =
        GetTransactionRpcRequest.builder().transactionId(id).build();
    RpcRequest rpcRequest =
        RpcRequest.builder()
            .id(UUID.randomUUID().toString())
            .method(GET_TRANSACTION.toString())
            .jsonrpc(JSON_RPC_VERSION)
            .params(requestParams)
            .build();
    Response rpcResponse = sendRpcRequest(List.of(rpcRequest));
    Object txnObj = RpcHelper.getResultFromRpcResponse(rpcResponse);
    return gson.fromJson(gson.toJson(txnObj), GetTransactionResponse.class);
  }

  public GetQuoteResponse getQuote(String quoteId) throws IOException, AnchorException {
    GetQuoteRpcRequest requestParams = GetQuoteRpcRequest.builder().quoteId(quoteId).build();
    RpcRequest rpcRequest =
        RpcRequest.builder()
            .id(UUID.randomUUID().toString())
            .method(GET_QUOTE.toString())
            .jsonrpc(JSON_RPC_VERSION)
            .params(requestParams)
            .build();
    Response rpcResponse = sendRpcRequest(List.of(rpcRequest));
    Object quoteObj = RpcHelper.getResultFromRpcResponse(rpcResponse);
    return gson.fromJson(gson.toJson(quoteObj), GetQuoteResponse.class);
  }

  /**
   * Search the transactions with the given filters by calling the /transactions endpoint.
   *
   * @param sep The SEP number (eg: 6, 24, 31) to filter by.
   * @param orderBy The field to order by.
   * @param order The direction to order by.
   * @param statuses The statuses to filter by.
   * @param pageSize The number of transactions to return per page.
   * @param pageNumber The page number of the search.
   * @return The GetTransactionsResponse.
   * @throws IOException if the request fails due to IO errors.
   * @throws AnchorException if the response is not successful.
   */
  @Deprecated // ANCHOR-641 Use getTransactionsByRpc instead
  public GetTransactionsResponse getTransactions(
      TransactionsSeps sep,
      @Nullable TransactionsOrderBy orderBy,
      @Nullable Sort.Direction order,
      @Nullable List<SepTransactionStatus> statuses,
      @Nullable Integer pageSize,
      @Nullable Integer pageNumber)
      throws IOException, AnchorException {
    HttpUrl.Builder builder =
        Objects.requireNonNull(HttpUrl.parse(endpoint + "/transactions")).newBuilder();

    builder.addQueryParameter("sep", sep.name().toLowerCase().replaceAll("sep_", ""));

    addToBuilder(builder, orderBy, "order_by", x -> x.name().toLowerCase());
    addToBuilder(builder, order, "order", x -> x.name().toLowerCase());
    addToBuilder(builder, statuses, "statuses", SepTransactionStatus::mergeStatusesList);
    addToBuilder(builder, pageSize, "page_size", Object::toString);
    addToBuilder(builder, pageNumber, "page_number", Object::toString);

    Request request = getRequestBuilder().url(builder.build()).get().build();
    String responseBody = handleResponse(client.newCall(request).execute());
    return gson.fromJson(responseBody, GetTransactionsResponse.class);
  }

  public GetTransactionsResponse getTransactionsByRpc(
      TransactionsSeps sep,
      @Nullable TransactionsOrderBy orderBy,
      @Nullable Sort.Direction order,
      @Nullable List<SepTransactionStatus> statuses,
      @Nullable Integer pageSize,
      @Nullable Integer pageNumber)
      throws IOException, AnchorException {
    GetTransactionsRpcRequest requestParams =
        GetTransactionsRpcRequest.builder()
            .sep(sep)
            .orderBy(orderBy)
            .order(order)
            .statuses(statuses)
            .pageSize(pageSize)
            .pageNumber(pageNumber)
            .build();
    RpcRequest rpcRequest =
        RpcRequest.builder()
            .id(UUID.randomUUID().toString())
            .method(GET_TRANSACTIONS.toString())
            .jsonrpc(JSON_RPC_VERSION)
            .params(requestParams)
            .build();
    Response rpcResponse = sendRpcRequest(List.of(rpcRequest));
    Object txnObj = RpcHelper.getResultFromRpcResponse(rpcResponse);
    return gson.fromJson(gson.toJson(txnObj), GetTransactionsResponse.class);
  }

  /**
   * Patch the transaction.
   *
   * @param txnRequest The request to patch the transaction.
   * @return The response of the patch request.
   * @throws IOException if the request fails due to IO errors.
   * @throws AnchorException if the response is not successful.
   */
  @Deprecated // ANCHOR-641
  public PatchTransactionsResponse patchTransaction(PatchTransactionsRequest txnRequest)
      throws IOException, AnchorException {
    HttpUrl url = HttpUrl.parse(endpoint);
    if (url == null)
      throw new InvalidConfigException(
          String.format("Invalid endpoint: %s of the client.", endpoint));
    url =
        new HttpUrl.Builder()
            .scheme(url.scheme())
            .host(url.host())
            .port(url.port())
            .addPathSegment("transactions")
            .build();

    RequestBody requestBody = OkHttpUtil.buildJsonRequestBody(gson.toJson(txnRequest));
    Request request = getRequestBuilder().url(url).patch(requestBody).build();
    Response response = client.newCall(request).execute();
    return gson.fromJson(handleResponse(response), PatchTransactionsResponse.class);
  }

  public void notifyOnchainFundsSent(String txnId, String stellarTxnId, String message)
      throws AnchorException, IOException {
    NotifyOnchainFundsSentRequest request =
        NotifyOnchainFundsSentRequest.builder()
            .transactionId(txnId)
            .stellarTransactionId(stellarTxnId)
            .message(message)
            .build();
    sendRpcNotification(NOTIFY_ONCHAIN_FUNDS_SENT, request);
  }

  public void notifyOnchainFundsReceived(
      String txnId, String stellarTxnId, String amountIn, String message)
      throws AnchorException, IOException {
    NotifyOnchainFundsReceivedRequest request =
        NotifyOnchainFundsReceivedRequest.builder()
            .transactionId(txnId)
            .stellarTransactionId(stellarTxnId)
            .message(message)
            .amountIn(new AmountRequest(amountIn))
            .build();
    sendRpcNotification(NOTIFY_ONCHAIN_FUNDS_RECEIVED, request);
  }

  public void notifyRefundSent(
      String txnId, String stellarTxnId, String amount, String amountFee, String asset)
      throws AnchorException, IOException {
    NotifyRefundSentRequest request =
        NotifyRefundSentRequest.builder()
            .transactionId(txnId)
            .refund(
                Refund.builder()
                    .id(stellarTxnId)
                    .amount(AmountAssetRequest.builder().amount(amount).asset(asset).build())
                    .amountFee(AmountAssetRequest.builder().amount(amountFee).asset(asset).build())
                    .build())
            .build();
    sendRpcNotification(NOTIFY_REFUND_SENT, request);
  }

  public void notifyTransactionError(String txnId, String message)
      throws AnchorException, IOException {
    NotifyTransactionErrorRequest request =
        NotifyTransactionErrorRequest.builder().transactionId(txnId).message(message).build();
    sendRpcNotification(NOTIFY_TRANSACTION_ERROR, request);
  }

  public void sendRpcNotification(RpcMethod method, Object requestParams)
      throws IOException, AnchorException {
    RpcRequest rpcRequest =
        RpcRequest.builder()
            .id(UUID.randomUUID().toString())
            .method(method.toString())
            .jsonrpc(JSON_RPC_VERSION)
            .params(requestParams)
            .build();

    try (Response response = sendRpcRequest(List.of(rpcRequest))) {
      if (!response.isSuccessful() || response.body() == null) {
        throw new IOException(
            String.format(
                "RPC notification %s failed with HTTP status %d", method, response.code()));
      }
      RpcResponse[] rpcResponses;
      try {
        rpcResponses = gson.fromJson(response.body().string(), RpcResponse[].class);
      } catch (JsonParseException jpex) {
        throw new IOException(
            String.format("RPC notification %s returned an unreadable response", method), jpex);
      }
      if (rpcResponses == null) {
        throw new IOException(String.format("RPC notification %s returned no response", method));
      }
      for (RpcResponse rpcResponse : rpcResponses) {
        RpcResponse.RpcError error = rpcResponse.getError();
        if (error == null) {
          continue;
        }
        String message =
            String.format(
                "RPC notification %s failed: code=%d, message=%s",
                method, error.getCode(), error.getMessage());
        if (error.getCode() == RpcErrorCode.INTERNAL_ERROR.getErrorCode()) {
          throw new IOException(message);
        }
        throw new SepException(message);
      }
    }
  }

  public Response sendRpcRequest(List<RpcRequest> rpcRequests) throws IOException, AnchorException {
    HttpUrl url = HttpUrl.parse(endpoint);
    if (url == null)
      throw new InvalidConfigException(
          String.format("Invalid endpoint: %s of the client.", endpoint));
    url = new HttpUrl.Builder().scheme(url.scheme()).host(url.host()).port(url.port()).build();

    RequestBody requestBody = OkHttpUtil.buildJsonRequestBody(gson.toJson(rpcRequests));
    Request request = getRequestBuilder().url(url).post(requestBody).build();
    return client.newCall(request).execute();
  }

  public HashMap<?, ?> health(List<String> checks) throws IOException, AnchorException {
    HttpUrl url = HttpUrl.parse(endpoint);
    if (url == null)
      throw new InvalidConfigException(
          String.format("Invalid endpoint: %s of the client.", endpoint));
    HttpUrl.Builder builder =
        new HttpUrl.Builder()
            .scheme(url.scheme())
            .host(url.host())
            .port(url.port())
            .addPathSegment("health");
    if (checks.size() != 0) {
      String strChecks = String.join(",", checks);
      builder.addQueryParameter("checks", strChecks);
    }

    Request request =
        new Request.Builder()
            .url(endpoint + "/health")
            .header("Content-Type", "application/json")
            .get()
            .build();

    String responseBody = handleResponse(client.newCall(request).execute());
    return gson.fromJson(responseBody, HashMap.class);
  }

  private <T> void addToBuilder(
      HttpUrl.Builder builder, T val, String name, Function<T, String> f) {
    if (val != null) {
      builder.addQueryParameter(name, f.apply(val));
    }
  }
}
