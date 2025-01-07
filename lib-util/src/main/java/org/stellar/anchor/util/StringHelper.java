package org.stellar.anchor.util;

import com.google.gson.Gson;
import java.util.Objects;
import org.apache.commons.text.WordUtils;

public class StringHelper {
  public static boolean isEmpty(String value) {
    return Objects.toString(value, "").isEmpty();
  }

  public static boolean isNotEmpty(String value) {
    return !isEmpty(value);
  }

  /**
   * Convert camelCase string to an under-scored snake-case string.
   *
   * @param camel the camel case string.
   * @return under-scored snake-case string
   */
  public static String camelToSnake(String camel) {
    return camel
        .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
        .replaceAll("([a-z])([A-Z])", "$1_$2")
        .replaceAll("-", "_")
        .toLowerCase();
  }

  /**
   * Convert snake_case string to camelCase string.
   *
   * @param snakeWord the camel case string.
   * @return under-scored snake-case string
   */
  public static String snakeToCamelCase(String snakeWord) {
    String[] camelWords = snakeWord.replaceAll("_", " ").split(" ");
    if (camelWords.length == 0) return "";
    StringBuffer sb = new StringBuffer(camelWords[0]);
    for (int i = 1; i < camelWords.length; i++) {
      sb.append(WordUtils.capitalize(camelWords[i]));
    }
    return sb.toString();
  }

  public static String toPosixForm(String camel) {
    return camel
        .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
        .replaceAll("([a-z])([A-Z])", "$1_$2")
        .replaceAll("-", "_")
        .replaceAll("\\.", "_")
        .toUpperCase();
  }

  static Gson gson = GsonUtils.getInstance();

  /**
   * Convert an object to JSON string.
   *
   * @param object the object to convert
   * @return JSON string
   */
  public static String json(Object object) {
    return gson.toJson(object);
  }

  public static String sanitize(String value) {
    return value.replace("\n", "").replace("\r", "");
  }
}
