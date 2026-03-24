package org.stellar.anchor.platform.apiclient;

import static org.stellar.anchor.util.OkHttpUtil.TYPE_JSON;

import com.google.gson.Gson;
import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.stellar.anchor.api.custody.CreateCustodyTransactionRequest;
import org.stellar.anchor.api.custody.CreateTransactionPaymentResponse;
import org.stellar.anchor.api.custody.CreateTransactionRefundRequest;
import org.stellar.anchor.api.custody.CustodyExceptionResponse;
import org.stellar.anchor.api.custody.GenerateDepositAddressResponse;
import org.stellar.anchor.api.exception.CustodyException;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.auth.AuthHelper;
import org.stellar.anchor.platform.config.CustodyApiConfig;
import org.stellar.anchor.util.AuthHeader;
import org.stellar.anchor.util.GsonUtils;

/** API client, that is responsible for communication with Custody Server */
public class CustodyApiClient {

  private static final Gson gson = GsonUtils.getInstance();

  private static final String CREATE_TRANSACTION_URL_FORMAT = "/transactions";
  private static final String GENERATE_DEPOSIT_ADDRESS_URL_FORMAT = "/assets/%s/addresses";
  private static final String CREATE_TRANSACTION_PAYMENT_URL_FORMAT = "/transactions/%s/payments";
  private static final String CREATE_TRANSACTION_REFUND_URL_FORMAT = "/transactions/%s/refunds";

  private final OkHttpClient httpClient;
  private final AuthHelper authHelper;
  private final CustodyApiConfig custodyApiConfig;

  public CustodyApiClient(
      OkHttpClient httpClient, AuthHelper authHelper, CustodyApiConfig custodyApiConfig) {
    this.httpClient = httpClient;
    this.authHelper = authHelper;
    this.custodyApiConfig = custodyApiConfig;
  }

  public void createTransaction(CreateCustodyTransactionRequest transactionRequest)
      throws CustodyException, InvalidConfigException {
    Request request =
        getRequestBuilder()
            .url(custodyApiConfig.getBaseUrl() + CREATE_TRANSACTION_URL_FORMAT)
            .post(RequestBody.create(gson.toJson(transactionRequest).getBytes(), TYPE_JSON))
            .build();
    doRequest(request);
  }

  public GenerateDepositAddressResponse generateDepositAddress(String assetId)
      throws CustodyException, InvalidConfigException {
    Request request =
        getRequestBuilder()
            .url(
                custodyApiConfig.getBaseUrl()
                    + String.format(GENERATE_DEPOSIT_ADDRESS_URL_FORMAT, assetId))
            .post(RequestBody.create(StringUtils.EMPTY, null))
            .build();
    String responseBody = doRequest(request);
    return gson.fromJson(responseBody, GenerateDepositAddressResponse.class);
  }

  public CreateTransactionPaymentResponse createTransactionPayment(String txnId, String requestBody)
      throws CustodyException, InvalidConfigException {
    final String url =
        custodyApiConfig.getBaseUrl() + String.format(CREATE_TRANSACTION_PAYMENT_URL_FORMAT, txnId);

    Request request =
        getRequestBuilder()
            .url(url)
            .post(RequestBody.create(gson.toJson(requestBody).getBytes(), TYPE_JSON))
            .build();

    return gson.fromJson(doRequest(request), CreateTransactionPaymentResponse.class);
  }

  public CreateTransactionPaymentResponse createTransactionRefund(
      String txnId, CreateTransactionRefundRequest refundRequest)
      throws CustodyException, InvalidConfigException {
    final String url =
        custodyApiConfig.getBaseUrl() + String.format(CREATE_TRANSACTION_REFUND_URL_FORMAT, txnId);

    Request request =
        getRequestBuilder()
            .url(url)
            .post(RequestBody.create(gson.toJson(refundRequest).getBytes(), TYPE_JSON))
            .build();

    return gson.fromJson(doRequest(request), CreateTransactionPaymentResponse.class);
  }

  private Request.Builder getRequestBuilder() throws InvalidConfigException {
    Request.Builder requestBuilder = new Request.Builder();
    AuthHeader<String, String> authHeader = authHelper.createAuthHeader();
    return authHeader == null
        ? requestBuilder
        : requestBuilder.header(authHeader.getName(), authHeader.getValue());
  }

  private String doRequest(Request request) throws CustodyException {
    try (Response response = httpClient.newCall(request).execute()) {
      ResponseBody responseBody = response.body();
      String responseBodyJson = null;

      if (responseBody != null) {
        responseBodyJson = responseBody.string();
      }

      if (HttpStatus.valueOf(response.code()).is2xxSuccessful()) {
        return responseBodyJson;
      } else {
        String rawMessage =
            gson.fromJson(responseBodyJson, CustodyExceptionResponse.class).getRawErrorMessage();
        if (rawMessage != null) {
          throw new CustodyException(rawMessage, response.code());
        } else {
          throw new CustodyException(responseBodyJson, response.code());
        }
      }
    } catch (IOException e) {
      throw new CustodyException(e);
    }
  }
}
