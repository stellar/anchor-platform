package org.stellar.anchor.api.exception.rpc;

import static org.stellar.anchor.api.rpc.RpcErrorCode.INVALID_REQUEST;

import lombok.EqualsAndHashCode;
import org.stellar.anchor.api.exception.RpcException;

/** The JSON sent is not a valid Request object. */
@EqualsAndHashCode(callSuper = false)
public class InvalidRequestException extends RpcException {
  public InvalidRequestException(String message) {
    super(INVALID_REQUEST.getErrorCode(), message);
  }
}
