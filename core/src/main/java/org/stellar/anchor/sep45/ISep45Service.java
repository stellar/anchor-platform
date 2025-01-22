package org.stellar.anchor.sep45;

import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.sep.sep45.ChallengeRequest;
import org.stellar.anchor.api.sep.sep45.ChallengeResponse;
import org.stellar.anchor.api.sep.sep45.ValidationRequest;
import org.stellar.anchor.api.sep.sep45.ValidationResponse;

public interface ISep45Service {

  ChallengeResponse getChallenge(ChallengeRequest request) throws AnchorException;

  ValidationResponse validate(ValidationRequest request) throws AnchorException;
}
