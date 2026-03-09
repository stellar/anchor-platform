package org.stellar.anchor.platform.service;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.errorEx;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.rpc.InternalErrorException;
import org.stellar.anchor.api.exception.rpc.MethodNotFoundException;
import org.stellar.anchor.api.exception.rpc.RpcException;
import org.stellar.anchor.api.rpc.RpcRequest;
import org.stellar.anchor.api.rpc.RpcResponse;
import org.stellar.anchor.api.rpc.method.RpcMethod;
import org.stellar.anchor.platform.config.RpcConfig;
import org.stellar.anchor.platform.rpc.RpcMethodHandler;
import org.stellar.anchor.platform.utils.RpcUtil;
import org.stellar.sdk.exception.NetworkException;

public class RpcService {

  private final Map<RpcMethod, RpcMethodHandler<?>> rpcMethodHandlerMap;
  private final RpcConfig rpcConfig;
  @PersistenceContext private EntityManager entityManager;

  public RpcService(List<RpcMethodHandler<?>> rpcMethodHandlers, RpcConfig rpcConfig) {
    this.rpcMethodHandlerMap =
        rpcMethodHandlers.stream().collect(toMap(RpcMethodHandler::getRpcMethod, identity()));
    this.rpcConfig = rpcConfig;
  }

  public List<RpcResponse> handle(List<RpcRequest> rpcRequests) {
    if (rpcRequests.size() > rpcConfig.getBatchSizeLimit()) {
      return List.of(RpcUtil.getRpcBatchLimitErrorResponse(rpcConfig.getBatchSizeLimit()));
    }

    return rpcRequests.stream()
        .map(
            rc -> {
              final Object rpcId = rc.getId();
              try {
                RpcUtil.validateRpcRequest(rc);
                return RpcUtil.getRpcSuccessResponse(rpcId, processRpcCallWithRetry(rc));
              } catch (RpcException ex) {
                errorEx(
                    String.format(
                        "An RPC error occurred while processing an RPC request with method[%s] and id[%s]",
                        rc.getMethod(), rpcId),
                    ex);
                return RpcUtil.getRpcErrorResponse(rc, ex);
              } catch (BadRequestException ex) {
                return RpcUtil.getRpcErrorResponse(rc, ex);
              } catch (NetworkException ex) {
                var message =
                    ex.getMessage() + " Code: " + ex.getCode() + " , body: " + ex.getBody();
                errorEx(
                    String.format(
                        "Error response received from Horizon while processing an RPC request with method[%s] and id[%s] with message [%s]",
                        rc.getMethod(), rpcId, message),
                    ex);
                return RpcUtil.getRpcErrorResponse(rc, new InternalErrorException(message));
              } catch (Exception ex) {
                errorEx(
                    String.format(
                        "An internal error occurred while processing an RPC request with method[%s] and id[%s]",
                        rc.getMethod(), rpcId),
                    ex);
                return RpcUtil.getRpcErrorResponse(rc, new InternalErrorException(ex.getMessage()));
              }
            })
        .collect(toList());
  }

  private static final int OPTIMISTIC_LOCK_MAX_RETRIES = 5;
  private static final long OPTIMISTIC_LOCK_RETRY_DELAY_MS = 200L;

  // Retries on OptimisticLockingFailureException, which occurs when two concurrent requests
  // modify the same transaction. All other exceptions propagate immediately.
  private Object processRpcCallWithRetry(RpcRequest rpcCall) throws AnchorException {
    for (int attempt = 1; attempt <= OPTIMISTIC_LOCK_MAX_RETRIES; attempt++) {
      try {
        return processRpcCall(rpcCall);
      } catch (OptimisticLockingFailureException ex) {
        if (attempt == OPTIMISTIC_LOCK_MAX_RETRIES) {
          errorEx(
              String.format(
                  "Concurrent modification detected while processing RPC request with method[%s] and id[%s] after %d attempts",
                  rpcCall.getMethod(), rpcCall.getId(), OPTIMISTIC_LOCK_MAX_RETRIES),
              ex);
          throw new InternalErrorException(
              "Transaction was modified by another request. Please retry.");
        }
        // Clear Hibernate L1 cache so the retry loads fresh entity state from the DB
        entityManager.clear();
        try {
          Thread.sleep(OPTIMISTIC_LOCK_RETRY_DELAY_MS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new InternalErrorException("Interrupted while retrying optimistic lock conflict");
        }
      }
    }
    // Unreachable, but required for compilation
    throw new InternalErrorException("Unexpected state in processRpcCallWithRetry");
  }

  private Object processRpcCall(RpcRequest rpcCall) throws AnchorException {
    debugF("Started processing of RPC request with method[{}]", rpcCall.getMethod());
    RpcMethodHandler<?> rpcMethodHandler =
        rpcMethodHandlerMap.get(RpcMethod.from(rpcCall.getMethod()));
    if (rpcMethodHandler == null) {
      throw new MethodNotFoundException(
          String.format("RPC method[%s] handler is not found", rpcCall.getMethod()));
    }
    return rpcMethodHandler.handle(rpcCall.getParams());
  }
}
