package org.stellar.anchor.sep10;

import static org.stellar.sdk.Sep10Challenge.*;

import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.exception.SepValidationException;
import org.stellar.anchor.api.sep.sep10.ChallengeRequest;
import org.stellar.anchor.api.sep.sep10.ChallengeResponse;
import org.stellar.anchor.api.sep.sep10.ValidationRequest;
import org.stellar.sdk.Memo;

public interface ISep10Service {

  /**
   * Validate the challenge request. The default implementation is NOOP. This is used for the
   * customization of the validation behavior.
   *
   * @param request The challenge request.
   * @throws SepException If the challenge failed to validate.
   */
  void preChallengeRequestValidation(ChallengeRequest request) throws SepException;

  /**
   * Validate the home domain of the challenge request.
   *
   * @param request The challenge request.
   * @throws SepException If the home domain is invalid.
   */
  void validateChallengeRequestFormat(ChallengeRequest request) throws SepException;

  /**
   * Validate the client domain of the challenge request.
   *
   * @param request The challenge request.
   * @throws SepException If the client domain is invalid.
   */
  void validateChallengeRequestClient(ChallengeRequest request) throws SepException;

  /**
   * Validate the memo of the challenge request and return the memo that is validated.
   *
   * @param request The challenge request.
   * @return The validated memo.
   * @throws SepException If the memo is invalid.
   */
  Memo validateChallengeRequestMemo(ChallengeRequest request) throws SepException;

  /**
   * Validate the challenge transaction. The default implementation is NOOP. This is used for the
   * customization of the validation behavior.
   *
   * @param request The challenge request.
   * @throws SepException If the challenge transaction is invalid.
   */
  void postChallengeRequestValidation(ChallengeRequest request) throws SepException;

  /** Increment the metrics counter for the number of challenge requests created. */
  void incrementChallengeRequestCreatedCounter();

  /**
   * Validate the home domain of the challenge transaction. The home_domain of the challenge
   * transaction must match the home_domain of the Sep10Config::getHomeDomain()
   *
   * @param challenge
   * @return The home domain of the challenge transaction.
   * @throws SepValidationException If the home domain of the challenge transaction is invalid.
   */
  String validateChallengeTransactionHomeDomain(ChallengeTransaction challenge)
      throws SepValidationException;

  /**
   * Create a challenge transaction from the request and the memo
   *
   * @param request The challenge request.
   * @param memo The memo.
   * @return The challenge transaction.
   * @throws SepException If the challenge transaction cannot be created.
   */
  ChallengeResponse createChallengeResponse(
      ChallengeRequest request, Memo memo, String clientSigningKey) throws SepException;

  /**
   * Validate the validation request. The default implementation is NOOP. This is used for the
   * customization of the validation behavior.
   *
   * @param request The validation request.
   * @param challenge The parsed challenge transaction.
   * @throws SepException If the validation request is invalid.
   */
  void preValidateRequestValidation(ValidationRequest request, ChallengeTransaction challenge)
      throws SepException;

  /** Increment the metrics counter for the number of validation requests validated. */
  void incrementValidationRequestValidatedCounter();
}
