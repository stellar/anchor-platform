package org.stellar.anchor.platform.service;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.errorEx;
import static org.stellar.anchor.util.Log.infoF;

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
import org.stellar.anchor.util.GsonUtils;
import org.stellar.sdk.exception.NetworkException;

public class RpcService {

  private final Map<RpcMethod, RpcMethodHandler<?>> rpcMethodHandlerMap;
  private final RpcConfig rpcConfig;

  public RpcService(List<RpcMethodHandler<?>> rpcMethodHandlers, RpcConfig rpcConfig) {
    this.rpcMethodHandlerMap =
        rpcMethodHandlers.stream().collect(toMap(RpcMethodHandler::getRpcMethod, identity()));
    this.rpcConfig = rpcConfig;
  }

  public List<RpcResponse> handle(List<RpcRequest> rpcRequests) {
    if (rpcRequests.size() > rpcConfig.getBatchSizeLimit()) {
      return List.of(RpcUtil.getRpcBatchLimitErrorResponse(rpcConfig.getBatchSizeLimit()));
    }

    List<RpcResponse> responses =
        rpcRequests.stream()
            .map(
                rc -> {
                  final Object rpcId = rc.getId();
                  RpcResponse response;
                  try {
                    RpcUtil.validateRpcRequest(rc);
                    response = RpcUtil.getRpcSuccessResponse(rpcId, processRpcCall(rc));
                  } catch (RpcException ex) {
                    errorEx(
                        String.format(
                            "An RPC error occurred while processing an RPC request with method[%s] and id[%s]",
                            rc.getMethod(), rpcId),
                        ex);
                    response = RpcUtil.getRpcErrorResponse(rc, ex);
                  } catch (BadRequestException ex) {
                    // ANCHOR-1279 DIAG: this branch swallows the exception with no log line --
                    // logging it here to see whether it's firing for the ids that come up short.
                    infoF(
                        "RPC_DIAG BadRequestException for method[{}] id[{}]: {}",
                        rc.getMethod(),
                        rpcId,
                        ex.getMessage());
                    response = RpcUtil.getRpcErrorResponse(rc, ex);
                  } catch (OptimisticLockingFailureException ex) {
                    errorEx(
                        String.format(
                            "Concurrent modification detected while processing RPC request with method[%s] and id[%s]",
                            rc.getMethod(), rpcId),
                        ex);
                    response =
                        RpcUtil.getRpcErrorResponse(
                            rc,
                            new InternalErrorException(
                                "Transaction was modified by another request. Please re-read the transaction state and retry if appropriate."));
                  } catch (NetworkException ex) {
                    var message =
                        ex.getMessage() + " Code: " + ex.getCode() + " , body: " + ex.getBody();
                    errorEx(
                        String.format(
                            "Error response received from Horizon while processing an RPC request with method[%s] and id[%s] with message [%s]",
                            rc.getMethod(), rpcId, message),
                        ex);
                    response = RpcUtil.getRpcErrorResponse(rc, new InternalErrorException(message));
                  } catch (Exception ex) {
                    errorEx(
                        String.format(
                            "An internal error occurred while processing an RPC request with method[%s] and id[%s]",
                            rc.getMethod(), rpcId),
                        ex);
                    response =
                        RpcUtil.getRpcErrorResponse(
                            rc, new InternalErrorException(ex.getMessage()));
                  }
                  // ANCHOR-1279 DIAG: dump exactly what's being returned per request, since the
                  // test client never logs the raw batch response body.
                  infoF(
                      "RPC_DIAG response for method[{}] id[{}]: {}",
                      rc.getMethod(),
                      rpcId,
                      GsonUtils.getInstance().toJson(response));
                  return response;
                })
            .collect(toList());
    // ANCHOR-1279 DIAG: confirm the returned list has one entry per input request.
    infoF(
        "RPC_DIAG batch: {} request(s) in, {} response(s) out",
        rpcRequests.size(),
        responses.size());
    return responses;
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
