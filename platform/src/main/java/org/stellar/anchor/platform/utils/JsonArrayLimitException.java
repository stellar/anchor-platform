package org.stellar.anchor.platform.utils;

import com.google.gson.JsonParseException;
import lombok.Getter;

/** Thrown while a JSON body is read when the limited array holds more elements than allowed. */
@Getter
public class JsonArrayLimitException extends JsonParseException {
  /** The root-object member that holds the array, or null when the array is the root. */
  private final String member;

  private final int limit;

  public JsonArrayLimitException(String member, int limit) {
    super(
        String.format(
            "The JSON array %s exceeds the limit of %d elements",
            member == null ? "at the root" : "\"" + member + "\"", limit));
    this.member = member;
    this.limit = limit;
  }
}
