package org.stellar.anchor.platform.custody.fireblocks;

import static org.stellar.anchor.util.Log.*;
import static org.stellar.anchor.util.StringHelper.isEmpty;
import static org.stellar.sdk.xdr.OperationType.*;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SignatureException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.stellar.anchor.api.custody.fireblocks.FireblocksEventObject;
import org.stellar.anchor.api.custody.fireblocks.TransactionDetails;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.ledger.LedgerClient;
import org.stellar.anchor.ledger.LedgerTransaction;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation;
import org.stellar.anchor.platform.config.FireblocksConfig;
import org.stellar.anchor.platform.custody.*;
import org.stellar.anchor.platform.data.JdbcCustodyTransactionRepo;
import org.stellar.anchor.util.GsonUtils;
import org.stellar.anchor.util.RSAUtil;
import org.stellar.sdk.xdr.OperationType;

/** Service, that is responsible for handling Fireblocks events */
public class FireblocksEventService extends CustodyEventService {

  public static final String FIREBLOCKS_SIGNATURE_HEADER = "fireblocks-signature";
  private static final Set<OperationType> PAYMENT_TRANSACTION_OPERATION_TYPES =
      Set.of(PAYMENT, PATH_PAYMENT_STRICT_SEND, PATH_PAYMENT_STRICT_RECEIVE);

  private final LedgerClient ledgerClient;
  private final PublicKey publicKey;

  public FireblocksEventService(
      JdbcCustodyTransactionRepo custodyTransactionRepo,
      Sep6CustodyPaymentHandler sep6CustodyPaymentHandler,
      Sep24CustodyPaymentHandler sep24CustodyPaymentHandler,
      Sep31CustodyPaymentHandler sep31CustodyPaymentHandler,
      LedgerClient ledgerClient,
      FireblocksConfig fireblocksConfig)
      throws InvalidConfigException {
    super(
        custodyTransactionRepo,
        sep6CustodyPaymentHandler,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler);
    this.ledgerClient = ledgerClient;
    this.publicKey = fireblocksConfig.getFireblocksPublicKey();
  }

  /**
   * Process request sent by Fireblocks to webhook endpoint
   *
   * @param event Request body
   * @param headers HTTP headers
   * @throws BadRequestException when fireblocks-signature is missing, empty or contains invalid
   *     signature
   */
  @Override
  public void handleEvent(String event, Map<String, String> headers) throws BadRequestException {
    String signature = headers.get(FIREBLOCKS_SIGNATURE_HEADER);
    if (signature == null) {
      throw new BadRequestException("'" + FIREBLOCKS_SIGNATURE_HEADER + "' header missed");
    }

    if (isEmpty(signature)) {
      throw new BadRequestException("'" + FIREBLOCKS_SIGNATURE_HEADER + "' is empty");
    }

    debugF("Fireblocks /webhook endpoint called with signature '{}'", signature);
    debugF("Fireblocks /webhook endpoint called with data '{}'", event);

    try {
      if (RSAUtil.isValidSignature(signature, event, publicKey)) {
        FireblocksEventObject fireblocksEventObject =
            GsonUtils.getInstance().fromJson(event, FireblocksEventObject.class);

        TransactionDetails transactionDetails = fireblocksEventObject.getData();
        setExternalTxId(
            transactionDetails.getDestinationAddress(),
            transactionDetails.getDestinationTag(),
            transactionDetails.getId());

        if (!transactionDetails.getStatus().isObservableByWebhook()) {
          debugF("Skipping Fireblocks webhook event[{}] due to the status", event);
          return;
        }

        try {
          Optional<CustodyPayment> payment = convert(transactionDetails);
          if (payment.isPresent()) {
            handlePayment(payment.get());
          }
        } catch (AnchorException | IOException e) {
          throw new BadRequestException("Unable to handle Fireblocks webhook event", e);
        }
      } else {
        error("Fireblocks webhook event signature is invalid");
      }
    } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
      errorEx("Fireblocks webhook event signature validation failed", e);
    }
  }

  public Optional<CustodyPayment> convert(TransactionDetails td) throws IOException {
    LedgerOperation ledgerOperation = null;
    CustodyPayment.CustodyPaymentStatus status =
        td.getStatus().isCompleted()
            ? CustodyPayment.CustodyPaymentStatus.SUCCESS
            : CustodyPayment.CustodyPaymentStatus.ERROR;
    String message = null;

    if (CustodyPayment.CustodyPaymentStatus.ERROR == status && td.getSubStatus() != null) {
      message = td.getSubStatus().name();
    }

    LedgerTransaction ledgerTxn = null;

    try {
      ledgerTxn = ledgerClient.getTransaction(td.getTxHash());
      Optional<LedgerOperation> op =
          ledgerTxn.getOperations().stream()
              .filter(it -> PAYMENT_TRANSACTION_OPERATION_TYPES.contains(it.getType()))
              .findFirst();
      if (op.isPresent()) {
        ledgerOperation = op.get();
      } else {
        // The type is unknown or there is no operation
        return Optional.empty();
      }
    } catch (Exception e) {
      warnF(
          "Unable to find Stellar transaction for Fireblocks event. Id[{}], error[{}]",
          td.getId(),
          e.getMessage());
    }

    CustodyPayment payment = null;

    try {
      if (ledgerOperation == null) {
        payment =
            CustodyPayment.fromPayment(
                ledgerTxn,
                null,
                td.getId(),
                Instant.ofEpochMilli(td.getLastUpdated()),
                status,
                message,
                td.getTxHash());
      } else if (ledgerOperation.getType() == PAYMENT) {
        payment =
            CustodyPayment.fromPayment(
                ledgerTxn,
                ledgerOperation.getPaymentOperation(),
                td.getId(),
                Instant.ofEpochMilli(td.getLastUpdated()),
                status,
                message,
                td.getTxHash());
      } else if (List.of(PATH_PAYMENT_STRICT_RECEIVE, PATH_PAYMENT_STRICT_SEND)
          .contains(ledgerOperation.getType())) {
        payment =
            CustodyPayment.fromPathPayment(
                ledgerTxn,
                ledgerOperation.getPathPaymentOperation(),
                td.getId(),
                Instant.ofEpochMilli(td.getLastUpdated()),
                status,
                message,
                td.getTxHash());
      } else {
        errorF(
            "Unknown Stellar transaction operation type[{}]. This should never happen.",
            ledgerOperation.getType());
      }
    } catch (SepException ex) {
      warnF("Fireblocks event with id[{}] contains unsupported memo", td.getId());
      warnEx(ex);
    }

    return Optional.ofNullable(payment);
  }
}
