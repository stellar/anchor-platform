package org.stellar.anchor.sep12;

import static org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_12;
import static org.stellar.anchor.util.Log.infoF;
import static org.stellar.anchor.util.MetricConstants.*;
import static org.stellar.anchor.util.MetricConstants.SEP12_CUSTOMER;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.stellar.anchor.api.callback.*;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.*;
import org.stellar.anchor.api.platform.GetTransactionResponse;
import org.stellar.anchor.api.sep.sep12.*;
import org.stellar.anchor.api.shared.StellarId;
import org.stellar.anchor.apiclient.PlatformApiClient;
import org.stellar.anchor.auth.WebAuthJwt;
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
  private final PlatformApiClient platformApiClient;
  private final EventService.Session eventSession;

  public Sep12Service(
      CustomerIntegration customerIntegration,
      PlatformApiClient platformApiClient,
      EventService eventService) {
    this.customerIntegration = customerIntegration;
    this.platformApiClient = platformApiClient;
    this.eventSession =
        eventService.createSession(this.getClass().getName(), EventService.EventQueue.TRANSACTION);

    Log.info("Sep12Service initialized.");
  }

  public void populateRequestFromTransactionId(Sep12CustomerRequestBase requestBase)
      throws SepNotFoundException {
    if (requestBase.getTransactionId() != null) {
      try {
        GetTransactionResponse txn =
            platformApiClient.getTransaction(requestBase.getTransactionId());
        requestBase.setAccount(txn.getCustomers().getSender().getAccount());
        requestBase.setMemo(txn.getCustomers().getSender().getMemo());
      } catch (Exception e) {
        throw new SepNotFoundException("The transaction specified does not exist");
      }
    }
  }

  public Sep12GetCustomerResponse getCustomer(WebAuthJwt token, Sep12GetCustomerRequest request)
      throws AnchorException {
    populateRequestFromTransactionId(request);

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

  public Sep12PutCustomerResponse putCustomer(WebAuthJwt token, Sep12PutCustomerRequest request)
      throws AnchorException {
    populateRequestFromTransactionId(request);

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

    PutCustomerResponse updatedCustomer =
        customerIntegration.putCustomer(PutCustomerRequest.from(request));

    // Only publish event if the customer was updated.
    eventSession.publish(
        AnchorEvent.builder()
            .id(UUID.randomUUID().toString())
            .sep(SEP_12.getSep().toString())
            .type(AnchorEvent.Type.CUSTOMER_UPDATED)
            .customer(GetCustomerResponse.to(updatedCustomer))
            .build());

    // increment counter
    sep12PutCustomerCounter.increment();
    return Sep12PutCustomerResponse.builder().id(updatedCustomer.getId()).build();
  }

  public void deleteCustomer(WebAuthJwt token, String account, String memo, String memoType)
      throws AnchorException {
    boolean isAccountAuthenticated =
        Stream.of(token.getAccount(), token.getMuxedAccount())
            .filter(Objects::nonNull)
            .anyMatch(tokenAccount -> Objects.equals(tokenAccount, account));

    boolean isMemoMissingAuthentication = false;
    String muxedAccountId = Objects.toString(token.getMuxedAccountId(), null);
    if (muxedAccountId != null) {
      if (!Objects.equals(token.getMuxedAccount(), account)) {
        isMemoMissingAuthentication = !Objects.equals(muxedAccountId, memo);
      }
    } else if (token.getAccountMemo() != null) {
      isMemoMissingAuthentication = !Objects.equals(token.getAccountMemo(), memo);
    }

    if (!isAccountAuthenticated || isMemoMissingAuthentication) {
      infoF("Requester ({}) not authorized to delete account ({})", token.getAccount(), account);
      throw new SepNotAuthorizedException(
          String.format("Not authorized to delete account [%s] with memo [%s]", account, memo));
    }

    boolean existingCustomerMatch = false;
    GetCustomerResponse existingCustomer =
        customerIntegration.getCustomer(
            GetCustomerRequest.builder().account(account).memo(memo).memoType(memoType).build());
    if (existingCustomer.getId() != null) {
      existingCustomerMatch = true;
      customerIntegration.deleteCustomer(existingCustomer.getId());
    }

    if (!existingCustomerMatch) {
      infoF(
          "No existing customer found for account={} memo={} memoType={}", account, memo, memoType);
      throw new SepNotFoundException("User not found.");
    }

    // increment counter
    sep12DeleteCustomerCounter.increment();
  }

  void validateGetOrPutRequest(Sep12CustomerRequestBase requestBase, WebAuthJwt token)
      throws SepException {
    if (requestBase.getTransactionId() != null) {
      try {
        // `transactionId` should be used in conjunction with customer type `type` (sep6,
        // sep31-sender, sep-31-receiver) to get the customer account and memo
        GetTransactionResponse txn =
            platformApiClient.getTransaction(requestBase.getTransactionId());
        StellarId customer =
            "sep31-receiver".equals(requestBase.getType())
                ? txn.getCustomers().getReceiver()
                : txn.getCustomers().getSender();
        requestBase.setId(customer.getId());
        requestBase.setAccount(customer.getAccount());
        requestBase.setMemo(customer.getMemo());
      } catch (Exception e) {
        throw new SepNotAuthorizedException("The transaction specified does not exist");
      }
    }
    validateRequestAndTokenAccounts(requestBase, token);
    validateRequestAndTokenMemos(requestBase, token);
    updateRequestMemoAndMemoType(requestBase, token);
  }

  void validateRequestAndTokenAccounts(
      @NotNull Sep12CustomerRequestBase requestBase, @NotNull WebAuthJwt token)
      throws SepException {
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

  void validateRequestAndTokenMemos(Sep12CustomerRequestBase requestBase, @NotNull WebAuthJwt token)
      throws SepException {
    String tokenSubMemo = token.getAccountMemo();
    String tokenMuxedAccountId = Objects.toString(token.getMuxedAccountId(), null);
    String tokenMemo = tokenMuxedAccountId != null ? tokenMuxedAccountId : tokenSubMemo;
    // SEP-12 says: If the JWT's `sub` field does not contain a muxed account or memo then the memo
    // request parameters may contain any value.
    if (tokenMemo == null) {
      return;
    }

    // SEP-12 says: If a memo is present in the decoded JWT's `sub` value, it must match this
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

  void updateRequestMemoAndMemoType(@NotNull Sep12CustomerRequestBase requestBase, WebAuthJwt token)
      throws SepException {
    String memo = requestBase.getMemo();
    if (memo == null) {
      requestBase.setMemoType(null);
      return;
    }
    String memoTypeId = MemoHelper.memoTypeAsString(MemoType.MEMO_ID);
    String memoType = Objects.toString(requestBase.getMemoType(), memoTypeId);
    // SEP-12 says: If a memo is present in the decoded JWT's `sub` value, this parameter
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
