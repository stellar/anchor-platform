package org.stellar.anchor.sep12;

import static org.stellar.anchor.util.Log.infoF;
import static org.stellar.anchor.util.MetricConstants.*;
import static org.stellar.anchor.util.MetricConstants.SEP12_CUSTOMER;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.stellar.anchor.api.callback.*;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.*;
import org.stellar.anchor.api.platform.CustomerUpdatedResponse;
import org.stellar.anchor.api.platform.GetTransactionResponse;
import org.stellar.anchor.api.sep.sep12.*;
import org.stellar.anchor.apiclient.PlatformApiClient;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.auth.Sep10Jwt;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.util.Log;
import org.stellar.anchor.util.MemoHelper;
import org.stellar.sdk.xdr.MemoType;

public class Sep12Service {
  private final CustomerIntegration customerIntegration;
  private final Counter sep12GetCustomerCounter =
      Metrics.counter(SEP12_CUSTOMER, TYPE, TV_SEP12_GET_CUSTOMER);
  private final Counter sep12PutCustomerCounter =
      Metrics.counter(SEP12_CUSTOMER, TYPE, TV_SEP12_PUT_CUSTOMER);
  private final Counter sep12DeleteCustomerCounter =
      Metrics.counter(SEP12_CUSTOMER, TYPE, TV_SEP12_DELETE_CUSTOMER);

  private final Set<String> knownTypes;

  private final PlatformApiClient platformApiClient;
  private final EventService.Session eventSession;

  public Sep12Service(
      CustomerIntegration customerIntegration,
      AssetService assetService,
      PlatformApiClient platformApiClient,
      EventService eventService) {
    this.customerIntegration = customerIntegration;
    Stream<String> receiverTypes =
        assetService.listAllAssets().stream()
            .filter(x -> x.getSep31() != null && x.getSep31().getSep12() != null)
            .flatMap(x -> x.getSep31().getSep12().getReceiver().getTypes().keySet().stream());
    Stream<String> senderTypes =
        assetService.listAllAssets().stream()
            .filter(x -> x.getSep31() != null && x.getSep31().getSep12() != null)
            .flatMap(x -> x.getSep31().getSep12().getSender().getTypes().keySet().stream());
    this.knownTypes = Stream.concat(receiverTypes, senderTypes).collect(Collectors.toSet());
    this.platformApiClient = platformApiClient;
    this.eventSession =
        eventService.createSession(this.getClass().getName(), EventService.EventQueue.TRANSACTION);

    Log.info("Sep12Service initialized.");
  }

  public Sep12GetCustomerResponse getCustomer(Sep10Jwt token, Sep12GetCustomerRequest request)
      throws AnchorException {
    validateGetOrPutRequest(request, token);
    if (request.getId() == null && request.getAccount() == null && token.getAccount() != null) {
      request.setAccount(token.getAccount());
    }

    GetCustomerResponse response =
        customerIntegration.getCustomer(GetCustomerRequest.from(request));
    Sep12GetCustomerResponse res = GetCustomerResponse.to(response);

    // increment counter
    sep12GetCustomerCounter.increment();
    return res;
  }

  public Sep12PutCustomerResponse putCustomer(Sep10Jwt token, Sep12PutCustomerRequest request)
      throws AnchorException {
    validateGetOrPutRequest(request, token);

    if (request.getAccount() == null && token.getAccount() != null) {
      request.setAccount(token.getAccount());
    }

    if (StringUtils.isNotEmpty(request.getBirthDate())) {
      if (!isValidISO8601Date(request.getBirthDate())) {
        throw new SepValidationException("Invalid 'birth_date'");
      }
    }
    if (StringUtils.isNotEmpty(request.getIdIssueDate())) {
      if (!isValidISO8601Date(request.getIdIssueDate())) {
        throw new SepValidationException("Invalid 'id_issue_date'");
      }
    }
    if (StringUtils.isNotEmpty(request.getIdExpirationDate())) {
      if (!isValidISO8601Date(request.getIdExpirationDate())) {
        throw new SepValidationException("Invalid 'id_expiration_date'");
      }
    }

    PutCustomerResponse response =
        customerIntegration.putCustomer(PutCustomerRequest.from(request));

    // Only publish event if the customer was updated.
    eventSession.publish(
        AnchorEvent.builder()
            .id(UUID.randomUUID().toString())
            .sep("12")
            .type(AnchorEvent.Type.CUSTOMER_UPDATED)
            .customer(CustomerUpdatedResponse.builder().id(response.getId()).build())
            .build());

    // increment counter
    sep12PutCustomerCounter.increment();
    return PutCustomerResponse.to(response);
  }

  public void deleteCustomer(Sep10Jwt sep10Jwt, String account, String memo, String memoType)
      throws AnchorException {
    boolean isAccountAuthenticated =
        Stream.of(sep10Jwt.getAccount(), sep10Jwt.getMuxedAccount())
            .filter(Objects::nonNull)
            .anyMatch(tokenAccount -> Objects.equals(tokenAccount, account));

    boolean isMemoMissingAuthentication = false;
    String muxedAccountId = Objects.toString(sep10Jwt.getMuxedAccountId(), null);
    if (muxedAccountId != null) {
      if (!Objects.equals(sep10Jwt.getMuxedAccount(), account)) {
        isMemoMissingAuthentication = !Objects.equals(muxedAccountId, memo);
      }
    } else if (sep10Jwt.getAccountMemo() != null) {
      isMemoMissingAuthentication = !Objects.equals(sep10Jwt.getAccountMemo(), memo);
    }

    if (!isAccountAuthenticated || isMemoMissingAuthentication) {
      infoF("Requester ({}) not authorized to delete account ({})", sep10Jwt.getAccount(), account);
      throw new SepNotAuthorizedException(
          String.format("Not authorized to delete account [%s] with memo [%s]", account, memo));
    }

    boolean existingCustomerMatch = false;
    for (String customerType : knownTypes) {
      GetCustomerResponse existingCustomer =
          customerIntegration.getCustomer(
              GetCustomerRequest.builder()
                  .account(account)
                  .memo(memo)
                  .memoType(memoType)
                  .type(customerType)
                  .build());
      if (existingCustomer.getId() != null) {
        existingCustomerMatch = true;
        customerIntegration.deleteCustomer(existingCustomer.getId());
      }
    }
    if (!existingCustomerMatch) {
      infoF(
          "No existing customer found for account={} memo={} memoType={}", account, memo, memoType);
      throw new SepNotFoundException("User not found.");
    }

    // increment counter
    sep12DeleteCustomerCounter.increment();
  }

  void validateGetOrPutRequest(Sep12CustomerRequestBase requestBase, Sep10Jwt token)
      throws SepException {
    if (requestBase.getTransactionId() != null) {
      try {
        GetTransactionResponse txn =
            platformApiClient.getTransaction(requestBase.getTransactionId());
        requestBase.setAccount(txn.getCustomers().getSender().getAccount());
        requestBase.setMemo(txn.getCustomers().getSender().getMemo());
      } catch (Exception e) {
        throw new SepNotAuthorizedException("The transaction specified does not exist");
      }
    }
    validateRequestAndTokenAccounts(requestBase, token);
    validateRequestAndTokenMemos(requestBase, token);
    updateRequestMemoAndMemoType(requestBase, token);
  }

  void validateRequestAndTokenAccounts(
      @NotNull Sep12CustomerRequestBase requestBase, @NotNull Sep10Jwt token) throws SepException {
    // Validate request.account - SEP-12 says: This field should match the `sub` value of the
    // decoded SEP-10 JWT.
    String tokenAccount = token.getAccount();
    String tokenMuxedAccount = token.getMuxedAccount();
    String customerAccount = requestBase.getAccount();
    if (customerAccount != null
        && Stream.of(tokenAccount, tokenMuxedAccount).noneMatch(customerAccount::equals)) {
      infoF(
          "Neither tokenAccount ({}) nor tokenMuxedAccount ({}) match customerAccount ({})",
          tokenAccount,
          tokenMuxedAccount,
          customerAccount);
      throw new SepNotAuthorizedException(
          "The account specified does not match authorization token");
    }
  }

  void validateRequestAndTokenMemos(Sep12CustomerRequestBase requestBase, @NotNull Sep10Jwt token)
      throws SepException {
    String tokenSubMemo = token.getAccountMemo();
    String tokenMuxedAccountId = Objects.toString(token.getMuxedAccountId(), null);
    String tokenMemo = tokenMuxedAccountId != null ? tokenMuxedAccountId : tokenSubMemo;
    // SEP-12 says: If the JWT's `sub` field does not contain a muxed account or memo then the memo
    // request parameters may contain any value.
    if (tokenMemo == null) {
      return;
    }

    // SEP-12 says: If a memo is present in the decoded SEP-10 JWT's `sub` value, it must match this
    // parameter value. If a muxed account is used as the JWT's `sub` value, memos sent in requests
    // must match the 64-bit integer subaccount ID of the muxed account. See the Shared Account's
    // section for more information.
    String requestMemo = requestBase.getMemo();
    if (Objects.equals(tokenMemo, requestMemo)) {
      return;
    }

    infoF(
        "request memo ({}) does not match token memo ID ({}) authorized via SEP-10",
        requestMemo,
        tokenMemo);
    throw new SepNotAuthorizedException(
        "The memo specified does not match the memo ID authorized via SEP-10");
  }

  void updateRequestMemoAndMemoType(@NotNull Sep12CustomerRequestBase requestBase, Sep10Jwt token)
      throws SepException {
    String memo = requestBase.getMemo();
    if (memo == null) {
      requestBase.setMemoType(null);
      return;
    }
    String memoTypeId = MemoHelper.memoTypeAsString(MemoType.MEMO_ID);
    String memoType = Objects.toString(requestBase.getMemoType(), memoTypeId);
    // SEP-12 says: If a memo is present in the decoded SEP-10 JWT's `sub` value, this parameter
    // (memoType) can be ignored:
    if (token.getAccountMemo() != null || token.getMuxedAccountId() != null) {
      memoType = MemoHelper.memoTypeAsString(MemoType.MEMO_ID);
    }

    try {
      MemoHelper.makeMemo(memo, memoType);
    } catch (Exception e) {
      infoF("Invalid memo ({}) for memo_type ({})", memo, memoType);
      Log.warnEx(e);
      throw new SepValidationException("Invalid 'memo' for 'memo_type'");
    }

    requestBase.setMemo(memo);
    requestBase.setMemoType(memoType);
  }

  private boolean isValidISO8601Date(String dateStr) {
    try {
      LocalDate.parse(dateStr);
      return true;
    } catch (DateTimeParseException e) {
      try {
        ZonedDateTime.parse(dateStr);
        return true;
      } catch (DateTimeParseException e2) {
        return false;
      }
    }
  }
}
