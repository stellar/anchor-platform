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

  /**
   * Converts a dot-separated string to camelCase. For example: - "kt.reference.server.config"
   * becomes "ktReferenceServerConfig"
   *
   * @param s The dot-separated string to be converted.
   * @return A string in camelCase format.
   * @throws NullPointerException If the input string is null.
   */
  public static String dotToCamelCase(String s) {
    String[] parts = s.split("\\."); // Split by dot
    StringBuilder result = new StringBuilder();

    for (int i = 0; i < parts.length; i++) {
      String part = parts[i];
      if (i == 0) {
        // If it's the first part, leave it as is (in lowercase)
        result.append(part);
      } else {
        // Capitalize the first letter of other parts
        result.append(part.substring(0, 1).toUpperCase()).append(part.substring(1));
      }
    }
    return result.toString();
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
