package org.stellar.anchor.platform.custody.fireblocks;

import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.error;
import static org.stellar.anchor.util.Log.errorEx;
import static org.stellar.anchor.util.Log.warnEx;
import static org.stellar.anchor.util.Log.warnF;
import static org.stellar.anchor.util.StringHelper.isEmpty;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SignatureException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.stellar.anchor.api.custody.fireblocks.FireblocksEventObject;
import org.stellar.anchor.api.custody.fireblocks.TransactionDetails;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.network.Horizon;
import org.stellar.anchor.platform.config.FireblocksConfig;
import org.stellar.anchor.platform.custody.*;
import org.stellar.anchor.platform.data.JdbcCustodyTransactionRepo;
import org.stellar.anchor.util.GsonUtils;
import org.stellar.anchor.util.RSAUtil;
import org.stellar.sdk.responses.operations.OperationResponse;
import org.stellar.sdk.responses.operations.PathPaymentBaseOperationResponse;
import org.stellar.sdk.responses.operations.PaymentOperationResponse;

/** Service, that is responsible for handling Fireblocks events */
public class FireblocksEventService extends CustodyEventService {

  public static final String FIREBLOCKS_SIGNATURE_HEADER = "fireblocks-signature";
  private static final Set<String> PAYMENT_TRANSACTION_OPERATION_TYPES =
      Set.of("payment", "path_payment");

  private final Horizon horizon;
  private final PublicKey publicKey;

  public FireblocksEventService(
      JdbcCustodyTransactionRepo custodyTransactionRepo,
      Sep6CustodyPaymentHandler sep6CustodyPaymentHandler,
      Sep24CustodyPaymentHandler sep24CustodyPaymentHandler,
      Sep31CustodyPaymentHandler sep31CustodyPaymentHandler,
      Horizon horizon,
      FireblocksConfig fireblocksConfig)
      throws InvalidConfigException {
    super(
        custodyTransactionRepo,
        sep6CustodyPaymentHandler,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler);
    this.horizon = horizon;
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
    Optional<OperationResponse> operation = Optional.empty();
    CustodyPayment.CustodyPaymentStatus status =
        td.getStatus().isCompleted()
            ? CustodyPayment.CustodyPaymentStatus.SUCCESS
            : CustodyPayment.CustodyPaymentStatus.ERROR;
    String message = null;

    if (CustodyPayment.CustodyPaymentStatus.ERROR == status && td.getSubStatus() != null) {
      message = td.getSubStatus().name();
    }

    try {
      operation =
          horizon
              .getServer()
              .payments()
              .includeTransactions(true)
              .forTransaction(td.getTxHash())
              .execute()
              .getRecords()
              .stream()
              .filter(o -> PAYMENT_TRANSACTION_OPERATION_TYPES.contains(o.getType()))
              .findFirst();
    } catch (Exception e) {
      warnF(
          "Unable to find Stellar transaction for Fireblocks event. Id[{}], error[{}]",
          td.getId(),
          e.getMessage());
    }

    CustodyPayment payment = null;

    try {
      if (operation.isEmpty()) {
        payment =
            CustodyPayment.fromPayment(
                Optional.empty(),
                td.getId(),
                Instant.ofEpochMilli(td.getLastUpdated()),
                status,
                message,
                td.getTxHash());
      } else if (operation.get() instanceof PaymentOperationResponse) {
        PaymentOperationResponse paymentOperation = (PaymentOperationResponse) operation.get();
        payment =
            CustodyPayment.fromPayment(
                Optional.of(paymentOperation),
                td.getId(),
                Instant.ofEpochMilli(td.getLastUpdated()),
                status,
                message,
                td.getTxHash());
      } else if (operation.get() instanceof PathPaymentBaseOperationResponse) {
        PathPaymentBaseOperationResponse pathPaymentOperation =
            (PathPaymentBaseOperationResponse) operation.get();
        payment =
            CustodyPayment.fromPathPayment(
                Optional.of(pathPaymentOperation),
                td.getId(),
                Instant.ofEpochMilli(td.getLastUpdated()),
                status,
                message,
                td.getTxHash());
      } else {
        warnF("Unknown Stellar transaction operation type[{}]", operation.get().getType());
      }
    } catch (SepException ex) {
      warnF("Fireblocks event with id[{}] contains unsupported memo", td.getId());
      warnEx(ex);
    }

    return Optional.ofNullable(payment);
  }
}
