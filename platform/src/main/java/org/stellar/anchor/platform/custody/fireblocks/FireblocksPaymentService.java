package org.stellar.anchor.platform.custody.fireblocks;

import static org.stellar.anchor.api.custody.fireblocks.CreateTransactionRequest.DestinationTransferPeerPathType.ONE_TIME_ADDRESS;
import static org.stellar.anchor.api.custody.fireblocks.CreateTransactionRequest.TransferPeerPathType.VAULT_ACCOUNT;
import static org.stellar.anchor.util.MemoHelper.memoTypeAsString;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.stellar.anchor.api.custody.CreateTransactionPaymentResponse;
import org.stellar.anchor.api.custody.GenerateDepositAddressResponse;
import org.stellar.anchor.api.custody.fireblocks.CreateAddressRequest;
import org.stellar.anchor.api.custody.fireblocks.CreateAddressResponse;
import org.stellar.anchor.api.custody.fireblocks.CreateTransactionRequest;
import org.stellar.anchor.api.custody.fireblocks.CreateTransactionResponse;
import org.stellar.anchor.api.custody.fireblocks.TransactionDetails;
import org.stellar.anchor.api.exception.FireblocksException;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.platform.config.FireblocksConfig;
import org.stellar.anchor.platform.custody.CustodyPaymentService;
import org.stellar.anchor.platform.data.JdbcCustodyTransaction;
import org.stellar.anchor.util.GsonUtils;
import org.stellar.sdk.xdr.MemoType;

/** Fireblocks implementation of payment service */
@Getter
public class FireblocksPaymentService implements CustodyPaymentService<TransactionDetails> {

  private static final Gson gson = GsonUtils.getInstance();

  private static final String CREATE_NEW_DEPOSIT_ADDRESS_URL_FORMAT =
      "/v1/vault/accounts/%s/%s/addresses";
  private static final String TRANSACTIONS_URL = "/v1/transactions";
  private static final String GET_TRANSACTION_BY_ID_URL_FORMAT = "/v1/transactions/%s";

  private static final String QUERY_PARAM_AFTER = "after";
  private static final String QUERY_PARAM_BEFORE = "before";
  private static final String QUERY_PARAM_LIMIT = "limit";
  private static final String QUERY_PARAM_ORDER_BY = "orderBy";
  private static final String QUERY_PARAM_SORT = "sort";
  private static final String TRANSACTIONS_ORDER_BY = "createdAt";
  private static final String TRANSACTIONS_SORT = "ASC";
  private static final int DEFAULT_TRANSACTION_LIMIT = 500;

  private final FireblocksApiClient fireblocksApiClient;
  private final FireblocksConfig fireblocksConfig;
  private final Type transactionDetailsListType;
  private int transactionLimit = DEFAULT_TRANSACTION_LIMIT;

  public FireblocksPaymentService(
      FireblocksApiClient fireblocksApiClient, FireblocksConfig fireblocksConfig) {
    this.fireblocksApiClient = fireblocksApiClient;
    this.fireblocksConfig = fireblocksConfig;
    this.transactionDetailsListType = new TypeToken<ArrayList<TransactionDetails>>() {}.getType();
  }

  public FireblocksPaymentService(
      FireblocksApiClient fireblocksApiClient,
      FireblocksConfig fireblocksConfig,
      int transactionLimit) {
    this.fireblocksApiClient = fireblocksApiClient;
    this.fireblocksConfig = fireblocksConfig;
    this.transactionDetailsListType = new TypeToken<ArrayList<TransactionDetails>>() {}.getType();
    this.transactionLimit = transactionLimit;
  }

  /**
   * @see CustodyPaymentService#generateDepositAddress(String)
   */
  @Override
  public GenerateDepositAddressResponse generateDepositAddress(String assetId)
      throws FireblocksException, InvalidConfigException {
    CreateAddressRequest request = new CreateAddressRequest();
    CreateAddressResponse depositAddress =
        gson.fromJson(
            fireblocksApiClient.post(
                String.format(
                    CREATE_NEW_DEPOSIT_ADDRESS_URL_FORMAT,
                    fireblocksConfig.getVaultAccountId(),
                    fireblocksConfig.getFireblocksAssetCode(assetId)),
                gson.toJson(request)),
            CreateAddressResponse.class);
    return new GenerateDepositAddressResponse(
        depositAddress.getAddress(), depositAddress.getTag(), memoTypeAsString(MemoType.MEMO_ID));
  }

  /**
   * @see CustodyPaymentService#createTransactionPayment(JdbcCustodyTransaction, String)
   */
  @Retryable(
      value = FireblocksException.class,
      maxAttemptsExpression = "${custody.fireblocks.retry_config.max_attempts}",
      backoff = @Backoff(delayExpression = "${custody.fireblocks.retry_config.delay}"),
      exceptionExpression =
          "#root instanceof T(org.stellar.anchor.api.exception.FireblocksException) AND"
              + "(#root.statusCode == 429 OR #root.statusCode == 503)")
  public CreateTransactionPaymentResponse createTransactionPayment(
      JdbcCustodyTransaction txn, String requestBody)
      throws FireblocksException, InvalidConfigException {
    CreateTransactionRequest request = getCreateTransactionRequest(txn);

    CreateTransactionResponse response =
        gson.fromJson(
            fireblocksApiClient.post(TRANSACTIONS_URL, gson.toJson(request)),
            CreateTransactionResponse.class);

    return new CreateTransactionPaymentResponse(response.getId());
  }

  private CreateTransactionRequest getCreateTransactionRequest(JdbcCustodyTransaction txn)
      throws InvalidConfigException {
    return CreateTransactionRequest.builder()
        .assetId(fireblocksConfig.getFireblocksAssetCode(txn.getAsset()))
        .amount(txn.getAmount())
        .source(
            new CreateTransactionRequest.TransferPeerPath(
                VAULT_ACCOUNT, fireblocksConfig.getVaultAccountId()))
        .destination(
            new CreateTransactionRequest.DestinationTransferPeerPath(
                ONE_TIME_ADDRESS,
                new CreateTransactionRequest.OneTimeAddress(txn.getToAccount(), txn.getMemo())))
        .build();
  }

  @Override
  public TransactionDetails getTransactionById(String txnId) throws FireblocksException {
    return gson.fromJson(
        fireblocksApiClient.get(String.format(GET_TRANSACTION_BY_ID_URL_FORMAT, txnId)),
        TransactionDetails.class);
  }

  @Override
  public List<TransactionDetails> getTransactionsByTimeRange(Instant startTime, Instant endTime)
      throws FireblocksException {
    if (startTime.isAfter(endTime)) {
      throw new IllegalArgumentException("End time can't be before start time");
    }

    Map<String, String> queryParams =
        new HashMap<>(
            Map.of(
                QUERY_PARAM_AFTER, String.valueOf(startTime.toEpochMilli()),
                QUERY_PARAM_BEFORE, String.valueOf(endTime.toEpochMilli()),
                QUERY_PARAM_LIMIT, String.valueOf(transactionLimit),
                QUERY_PARAM_ORDER_BY, TRANSACTIONS_ORDER_BY,
                QUERY_PARAM_SORT, TRANSACTIONS_SORT));

    List<TransactionDetails> transactions = new ArrayList<>(getTransactions(queryParams));

    while (transactions.size() % transactionLimit == 0) {
      Long maxCreatedAt =
          transactions.stream()
              .map(TransactionDetails::getCreatedAt)
              .reduce(Long.MIN_VALUE, Long::max);

      queryParams.put(QUERY_PARAM_AFTER, String.valueOf(maxCreatedAt));

      List<TransactionDetails> retrievedTransactions = getTransactions(queryParams);
      if (retrievedTransactions == null) {
        return transactions;
      } else {
        transactions.addAll(getTransactions(queryParams));
      }
    }

    return transactions;
  }

  private List<TransactionDetails> getTransactions(Map<String, String> queryParams)
      throws FireblocksException {
    return gson.fromJson(
        fireblocksApiClient.get(TRANSACTIONS_URL, queryParams), transactionDetailsListType);
  }
}
