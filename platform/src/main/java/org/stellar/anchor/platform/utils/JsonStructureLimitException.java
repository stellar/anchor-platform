package org.stellar.anchor.platform.utils;

import com.google.gson.JsonParseException;
import lombok.Getter;

/** Thrown while a JSON body is read when it holds more values, or nests deeper, than allowed. */
@Getter
public class JsonStructureLimitException extends JsonParseException {
  public static final String NODES = "max_json_nodes";
  public static final String DEPTH = "max_json_depth";

  private final String limitName;
  private final int limit;

  public JsonStructureLimitException(String limitName, int limit) {
    super(String.format("The JSON body exceeds the limit %s of %d", limitName, limit));
    this.limitName = limitName;
    this.limit = limit;
  }
}
