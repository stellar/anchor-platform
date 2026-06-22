package org.stellar.anchor.platform.controller.sep;

import static org.stellar.anchor.platform.controller.sep.SepRequestHelper.getClientIpAddress;
import static org.stellar.anchor.util.Log.debugF;

import jakarta.servlet.http.HttpServletRequest;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.sep.sep6.*;
import org.stellar.anchor.auth.WebAuthJwt;
import org.stellar.anchor.platform.condition.OnAllSepsEnabled;
import org.stellar.anchor.sep6.Sep6Service;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("sep6")
@OnAllSepsEnabled(seps = {"sep6"})
public class Sep6Controller {
  private final Sep6Service sep6Service;

  public Sep6Controller(Sep6Service sep6Service) {
    this.sep6Service = sep6Service;
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/info",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public InfoResponse getInfo() {
    debugF("GET /info");
    return sep6Service.getInfo();
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/deposit",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public StartDepositResponse deposit(
      HttpServletRequest request,
      @RequestParam(value = "asset_code") String assetCode,
      @RequestParam(value = "account") String account,
      @RequestParam(value = "memo_type", required = false) String memoType,
      @RequestParam(value = "memo", required = false) String memo,
      @RequestParam(value = "email_address", required = false) String emailAddress,
      @RequestParam(value = "funding_method", required = false) String fundingMethod,
      @RequestParam(value = "type", required = false) String type,
      @RequestParam(value = "wallet_name", required = false) String walletName,
      @RequestParam(value = "wallet_url", required = false) String walletUrl,
      @RequestParam(value = "lang", required = false) String lang,
      @RequestParam(value = "amount", required = false) String amount,
      @RequestParam(value = "country_code", required = false) String countryCode,
      @RequestParam(value = "claimable_balances_supported", required = false)
          Boolean claimableBalancesSupported)
      throws AnchorException {
    debugF("GET /deposit");
    WebAuthJwt token = SepRequestHelper.getToken(request);
    StartDepositRequest startDepositRequest =
        StartDepositRequest.builder()
            .assetCode(assetCode)
            .account(account)
            .memoType(memoType)
            .memo(memo)
            .emailAddress(emailAddress)
            .fundingMethod(fundingMethod)
            .type(type)
            .walletName(walletName)
            .walletUrl(walletUrl)
            .lang(lang)
            .amount(amount)
            .countryCode(countryCode)
            .claimableBalancesSupported(claimableBalancesSupported)
            .requestClientIpAddress(getClientIpAddress(request))
            .build();
    return sep6Service.deposit(token, startDepositRequest);
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/deposit-exchange",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public StartDepositResponse depositExchange(
      HttpServletRequest request,
      @RequestParam(value = "destination_asset") String destinationAsset,
      @RequestParam(value = "source_asset") String sourceAsset,
      @RequestParam(value = "quote_id", required = false) String quoteId,
      @RequestParam(value = "amount") String amount,
      @RequestParam(value = "account") String account,
      @RequestParam(value = "memo_type", required = false) String memoType,
      @RequestParam(value = "memo", required = false) String memo,
      @RequestParam(value = "funding_method", required = false) String fundingMethod,
      @RequestParam(value = "type", required = false) String type,
      @RequestParam(value = "lang", required = false) String lang,
      @RequestParam(value = "country_code", required = false) String countryCode,
      @RequestParam(value = "claimable_balances_supported", required = false)
          Boolean claimableBalancesSupported)
      throws AnchorException {
    debugF("GET /deposit-exchange");
    WebAuthJwt token = SepRequestHelper.getToken(request);
    StartDepositExchangeRequest startDepositExchangeRequest =
        StartDepositExchangeRequest.builder()
            .destinationAsset(destinationAsset)
            .sourceAsset(sourceAsset)
            .quoteId(quoteId)
            .amount(amount)
            .account(account)
            .memoType(memoType)
            .memo(memo)
            .fundingMethod(fundingMethod)
            .type(type)
            .lang(lang)
            .countryCode(countryCode)
            .claimableBalancesSupported(claimableBalancesSupported)
            .requestClientIpAddress(getClientIpAddress(request))
            .build();
    return sep6Service.depositExchange(token, startDepositExchangeRequest);
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/withdraw",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public StartWithdrawResponse withdraw(
      HttpServletRequest request,
      @RequestParam(value = "asset_code") String assetCode,
      @RequestParam(value = "funding_method", required = false) String fundingMethod,
      @RequestParam(value = "type", required = false) String type,
      @RequestParam(value = "amount", required = false) String amount,
      @RequestParam(value = "country_code", required = false) String countryCode,
      @RequestParam(value = "refundMemo", required = false) String refundMemo,
      @RequestParam(value = "refundMemoType", required = false) String refundMemoType)
      throws AnchorException {
    debugF("GET /withdraw");
    WebAuthJwt token = SepRequestHelper.getToken(request);
    StartWithdrawRequest startWithdrawRequest =
        StartWithdrawRequest.builder()
            .assetCode(assetCode)
            .fundingMethod(fundingMethod)
            .type(type)
            .amount(amount)
            .countryCode(countryCode)
            .refundMemo(refundMemo)
            .refundMemoType(refundMemoType)
            .requestClientIpAddress(getClientIpAddress(request))
            .build();
    return sep6Service.withdraw(token, startWithdrawRequest);
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/withdraw-exchange",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public StartWithdrawResponse withdraw(
      HttpServletRequest request,
      @RequestParam(value = "source_asset") String sourceAsset,
      @RequestParam(value = "destination_asset") String destinationAsset,
      @RequestParam(value = "quote_id", required = false) String quoteId,
      @RequestParam(value = "amount") String amount,
      @RequestParam(value = "funding_method", required = false) String fundingMethod,
      @RequestParam(value = "type", required = false) String type,
      @RequestParam(value = "country_code", required = false) String countryCode,
      @RequestParam(value = "refund_memo", required = false) String refundMemo,
      @RequestParam(value = "refund_memo_type", required = false) String refundMemoType)
      throws AnchorException {
    debugF("GET /withdraw-exchange");
    WebAuthJwt token = SepRequestHelper.getToken(request);
    StartWithdrawExchangeRequest startWithdrawExchangeRequest =
        StartWithdrawExchangeRequest.builder()
            .sourceAsset(sourceAsset)
            .destinationAsset(destinationAsset)
            .quoteId(quoteId)
            .amount(amount)
            .fundingMethod(fundingMethod)
            .type(type)
            .countryCode(countryCode)
            .refundMemo(refundMemo)
            .refundMemoType(refundMemoType)
            .requestClientIpAddress(getClientIpAddress(request))
            .build();
    return sep6Service.withdrawExchange(token, startWithdrawExchangeRequest);
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/transactions",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public GetTransactionsResponse getTransactions(
      HttpServletRequest request,
      @RequestParam(value = "asset_code") String assetCode,
      @RequestParam(value = "account") String account,
      @RequestParam(required = false, value = "kind") String kind,
      @RequestParam(required = false, value = "limit") Integer limit,
      @RequestParam(required = false, value = "paging_id") String pagingId,
      @RequestParam(required = false, value = "no_older_than") String noOlderThan,
      @RequestParam(required = false, value = "lang") String lang)
      throws SepException {
    debugF(
        "/transactions asset_code={} kind={} limit={} paging_id={} no_older_than={} lang={}",
        assetCode,
        kind,
        limit,
        pagingId,
        noOlderThan,
        lang);
    WebAuthJwt token = SepRequestHelper.getToken(request);
    GetTransactionsRequest getTransactionsRequest =
        GetTransactionsRequest.builder()
            .assetCode(assetCode)
            .account(account)
            .kind(kind)
            .limit(limit)
            .pagingId(pagingId)
            .noOlderThan(noOlderThan)
            .lang(lang)
            .build();
    return sep6Service.findTransactions(token, getTransactionsRequest);
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/transaction",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public Sep6GetTransactionResponse getTransaction(
      HttpServletRequest request,
      @RequestParam(required = false, value = "id") String id,
      @RequestParam(required = false, value = "stellar_transaction_id") String stellarTransactionId,
      @RequestParam(required = false, value = "external_transaction_id")
          String externalTransactionId,
      @RequestParam(required = false, value = "lang") String lang)
      throws AnchorException, MalformedURLException, URISyntaxException {
    debugF(
        "/transaction id={} stellar_transaction_id={} external_transaction_id={} lang={}",
        id,
        stellarTransactionId,
        externalTransactionId,
        lang);
    WebAuthJwt token = SepRequestHelper.getToken(request);
    GetTransactionRequest getTransactionRequest =
        GetTransactionRequest.builder()
            .id(id)
            .stellarTransactionId(stellarTransactionId)
            .externalTransactionId(externalTransactionId)
            .build();
    return sep6Service.findTransaction(token, getTransactionRequest);
  }
}
