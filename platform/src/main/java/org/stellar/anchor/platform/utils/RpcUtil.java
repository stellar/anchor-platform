package org.stellar.anchor.platform.utils;

import static org.stellar.anchor.api.rpc.RpcErrorCode.INVALID_PARAMS;
import static org.stellar.anchor.api.rpc.RpcErrorCode.INVALID_REQUEST;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.rpc.InvalidRequestException;
import org.stellar.anchor.api.exception.rpc.RpcException;
import org.stellar.anchor.api.rpc.RpcRequest;
import org.stellar.anchor.api.rpc.RpcResponse;
import org.stellar.anchor.api.rpc.method.RpcMethodParamsRequest;
import org.stellar.anchor.util.GsonUtils;
import org.stellar.anchor.util.Log;
import org.stellar.anchor.util.StringHelper;

public class RpcUtil {

  public static final String JSON_RPC_VERSION = "2.0";
  private static final Gson gson = GsonUtils.getInstance();

  public static RpcResponse getRpcSuccessResponse(Object id, Object result) {
    return RpcResponse.builder().jsonrpc(JSON_RPC_VERSION).id(id).result(result).build();
  }

  public static RpcResponse getRpcErrorResponse(RpcRequest rpcRequest, BadRequestException ex) {
    return RpcResponse.builder()
        .jsonrpc(JSON_RPC_VERSION)
        .id(rpcRequest.getId())
        .error(
            RpcResponse.RpcError.builder()
                .id(getTransactionId(rpcRequest.getParams()))
                .code(INVALID_PARAMS.getErrorCode())
                .message(ex.getMessage())
                .build())
        .build();
  }

  public static RpcResponse getRpcErrorResponse(RpcRequest rpcRequest, RpcException ex) {
    return RpcResponse.builder()
        .jsonrpc(JSON_RPC_VERSION)
        .id(rpcRequest.getId())
        .error(
            RpcResponse.RpcError.builder()
                .id(getTransactionId(rpcRequest.getParams()))
                .code(ex.getErrorCode().getErrorCode())
                .message(ex.getMessage())
                .build())
        .build();
  }

  public static RpcResponse getRpcBatchLimitErrorResponse(int limit) {
    return RpcResponse.builder()
        .jsonrpc(JSON_RPC_VERSION)
        .error(
            RpcResponse.RpcError.builder()
                .code(INVALID_REQUEST.getErrorCode())
                .message(String.format("RPC batch size limit[%d] exceeded", limit))
                .build())
        .build();
  }

  public static void validateRpcRequest(RpcRequest rpcRequest) throws InvalidRequestException {
    List<String> messages = new ArrayList<>();
    if (!JSON_RPC_VERSION.equals(rpcRequest.getJsonrpc())) {
      messages.add(
          String.format("Unsupported JSON-RPC protocol version[%s]", rpcRequest.getJsonrpc()));
    }

    String method = rpcRequest.getMethod();
    if (StringHelper.isEmpty(method)) {
      messages.add("Method name can't be NULL or empty");
    }

    Object id = rpcRequest.getId();
    if (id == null) {
      messages.add("Id can't be NULL");
    } else {
      if (!(id instanceof Number) && !(id instanceof String)) {
        messages.add("An identifier MUST contain a String or a Number");
      }
    }

    if (!messages.isEmpty()) {
      throw new InvalidRequestException(String.join(";", messages));
    }
  }

  private static String getTransactionId(Object params) {
    try {
      RpcMethodParamsRequest request =
          gson.fromJson(gson.toJson(params), RpcMethodParamsRequest.class);
      return request.getTransactionId();
    } catch (Exception e) {
      Log.errorEx("Failed to retrieve transaction id from request", e);
    }

    return null;
  }
}
