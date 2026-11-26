package org.stellar.anchor.platform.utils;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.springframework.http.converter.json.GsonHttpMessageConverter;
import org.stellar.anchor.api.platform.PatchTransactionsRequest;
import org.stellar.anchor.api.rpc.RpcRequest;

/**
 * A Gson message converter that reads every request body through a {@link BoundedJsonReader}, so a
 * small body cannot make Gson build a large object graph.
 *
 * <p>The limits depend on the type the body is bound to: a JSON-RPC batch ({@code
 * List<RpcRequest>}) gets its own node limit and a limit on the number of requests, a {@link
 * PatchTransactionsRequest} gets a limit on its {@code records}, and any other type gets the
 * general node and depth limits.
 */
public class BoundedGsonHttpMessageConverter extends GsonHttpMessageConverter {
  @Value
  @Builder
  public static class Limits {
    /** Values allowed in a body bound to any type other than an RPC batch. */
    int maxNodes;

    /** Nesting depth allowed in every body. */
    int maxDepth;

    /** Values allowed in a JSON-RPC batch. */
    int rpcMaxNodes;

    /** Requests allowed in a JSON-RPC batch. */
    int batchSizeLimit;

    /** Records allowed in a {@code PATCH /transactions} body. */
    int maxPatchRecords;
  }

  private static final String RECORDS = "records";

  private final Gson gson;
  private final Limits limits;

  public BoundedGsonHttpMessageConverter(Gson gson, Limits limits) {
    super(gson);
    this.gson = gson;
    this.limits = limits;
  }

  @Override
  protected Object readInternal(Type resolvedType, Reader reader) throws Exception {
    BoundedJsonReader jsonReader = newReader(resolvedType, reader);
    Object result = gson.fromJson(jsonReader, resolvedType);
    assertFullConsumption(result, jsonReader);
    return result;
  }

  private BoundedJsonReader newReader(Type type, Reader reader) {
    if (isRpcBatch(type)) {
      return new BoundedJsonReader(
          reader,
          limits.getRpcMaxNodes(),
          limits.getMaxDepth(),
          BoundedJsonReader.ArrayLimit.root(limits.getBatchSizeLimit()));
    }
    if (PatchTransactionsRequest.class.equals(type)) {
      return new BoundedJsonReader(
          reader,
          limits.getMaxNodes(),
          limits.getMaxDepth(),
          BoundedJsonReader.ArrayLimit.member(RECORDS, limits.getMaxPatchRecords()));
    }
    return new BoundedJsonReader(reader, limits.getMaxNodes(), limits.getMaxDepth(), null);
  }

  private static boolean isRpcBatch(Type type) {
    if (!(type instanceof ParameterizedType)) {
      return false;
    }
    ParameterizedType parameterized = (ParameterizedType) type;
    return List.class.equals(parameterized.getRawType())
        && parameterized.getActualTypeArguments().length == 1
        && RpcRequest.class.equals(parameterized.getActualTypeArguments()[0]);
  }

  /** The same trailing-data check {@code Gson.fromJson(Reader, Type)} applies. */
  private static void assertFullConsumption(Object result, BoundedJsonReader reader) {
    if (result == null) {
      return;
    }
    try {
      if (reader.peek() != JsonToken.END_DOCUMENT) {
        throw new JsonSyntaxException("JSON document was not fully consumed.");
      }
    } catch (MalformedJsonException e) {
      throw new JsonSyntaxException(e);
    } catch (IOException e) {
      throw new JsonIOException(e);
    }
  }
}
