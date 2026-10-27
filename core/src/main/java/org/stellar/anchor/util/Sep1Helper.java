package org.stellar.anchor.util;

import static org.stellar.anchor.util.Log.*;

import com.moandjiezana.toml.Toml;
import java.io.IOException;
import java.util.List;
import okhttp3.OkHttpClient;
import org.stellar.anchor.api.exception.InvalidConfigException;

public class Sep1Helper {
  private static final long DEFAULT_MAX_RESPONSE_SIZE = 100 * 1024;

  // toml4j's Keys$Key builds each parsed key's path by concatenating it with its parent's path, so
  // parsing a single key or table header with n dotted segments costs O(n^2). Reject pathologically
  // deep segment chains before the string ever reaches toml4j. No stellar.toml table header needs
  // more than one segment (CURRENCIES, DOCUMENTATION, PRINCIPALS).
  private static final int MAX_KEY_SEGMENTS = 20;

  public static TomlContent readToml(String url) throws IOException, InvalidConfigException {
    try {
      String tomlValue = NetUtil.fetch(url);
      return new TomlContent(tomlValue);
    } catch (IOException e) {
      String obfuscatedMessage =
          String.format("An error occurred while fetching the TOML from %s", url);
      Log.errorEx(obfuscatedMessage, e);
      throw new IOException(obfuscatedMessage, e);
    } catch (InvalidConfigException e) {
      String obfuscatedMessage =
          String.format("An error occurred while parsing the TOML from %s", url);
      Log.errorEx(obfuscatedMessage, e);
      throw new InvalidConfigException(List.of(obfuscatedMessage), e);
    }
  }

  public static TomlContent readToml(String url, OkHttpClient client)
      throws IOException, InvalidConfigException {
    try {
      String tomlValue = NetUtil.fetch(url, DEFAULT_MAX_RESPONSE_SIZE, client);
      return new TomlContent(tomlValue);
    } catch (IOException e) {
      String obfuscatedMessage =
          String.format("An error occurred while fetching the TOML from %s", url);
      Log.errorEx(obfuscatedMessage, e);
      throw new IOException(obfuscatedMessage, e);
    } catch (InvalidConfigException e) {
      String obfuscatedMessage =
          String.format("An error occurred while parsing the TOML from %s", url);
      Log.errorEx(obfuscatedMessage, e);
      throw new InvalidConfigException(List.of(obfuscatedMessage), e);
    }
  }

  public static TomlContent parse(String tomlString) throws InvalidConfigException {
    try {
      return new TomlContent(tomlString);
    } catch (Throwable e) {
      // Obfuscate the message and rethrow. Caught as Throwable because toml4j can raise
      // StackOverflowError on pathologically nested inline tables/arrays, not just Exception.
      String obfuscatedMessage = "Failed to parse TOML content. Invalid Config.";
      Log.error(e.toString()); // Log the parsing exception
      throw new InvalidConfigException(
          obfuscatedMessage); // Preserve the original exception as the cause
    }
  }

  public static class TomlContent {
    private final Toml toml;

    TomlContent(String tomlString) throws InvalidConfigException {
      checkKeySegmentDepth(tomlString);
      try {
        toml = new Toml().read(tomlString);
      } catch (Throwable e) {
        // Obfuscate the message and rethrow. Caught as Throwable because toml4j can raise
        // StackOverflowError on pathologically nested inline tables/arrays, not just Exception.
        String obfuscatedMessage = "Failed to parse TOML content. Invalid Config.";
        Log.error(e.toString()); // Log the parsing exception
        throw new InvalidConfigException(
            obfuscatedMessage); // Preserve the original exception as the cause
      }
    }

    public String getString(String key) {
      return toml.getString(key);
    }

    public String getString(String key, String defaultValue) {
      return toml.getString(key, defaultValue);
    }
  }

  /**
   * Scans a TOML document line by line and rejects any table header (`[a.b.c]` or `[[a.b.c]]`)
   * whose dotted segment count exceeds {@link #MAX_KEY_SEGMENTS}, without ever handing the string
   * to toml4j. A bare dotted key-value line (`a.b.c = ...`, no header) is not scanned: toml4j 0.7.2
   * does not support that syntax at all and already rejects it immediately with its own
   * `IllegalStateException`, confirmed empirically against the bundled jar, so it never reaches the
   * quadratic key-path construction this guard protects against.
   */
  private static void checkKeySegmentDepth(String tomlString) throws InvalidConfigException {
    for (String rawLine : tomlString.split("\n", -1)) {
      String line = rawLine.strip();
      if (!line.startsWith("[")) {
        continue;
      }

      String inner = line.startsWith("[[") ? line.substring(2) : line.substring(1);
      int closeIndex = firstUnquotedIndexOf(inner, ']');
      String token = closeIndex >= 0 ? inner.substring(0, closeIndex) : inner;

      if (countSegments(token) > MAX_KEY_SEGMENTS) {
        infoF(
            "Rejected TOML document: a table header exceeds the maximum of {} dotted segments.",
            MAX_KEY_SEGMENTS);
        throw new InvalidConfigException("Failed to parse TOML content. Invalid Config.");
      }
    }
  }

  /** Index of the first unquoted occurrence of {@code target} in {@code s}, or -1. */
  private static int firstUnquotedIndexOf(String s, char target) {
    boolean inQuotes = false;
    char quoteChar = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inQuotes) {
        if (c == quoteChar) {
          inQuotes = false;
        }
      } else if (c == '"' || c == '\'') {
        inQuotes = true;
        quoteChar = c;
      } else if (c == target) {
        return i;
      }
    }
    return -1;
  }

  /** Number of dot-separated segments in {@code token}, treating a quoted segment as one. */
  private static int countSegments(String token) {
    String trimmed = token.strip();
    if (trimmed.isEmpty()) {
      return 0;
    }
    int dotCount = 0;
    boolean inQuotes = false;
    char quoteChar = 0;
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (inQuotes) {
        if (c == quoteChar) {
          inQuotes = false;
        }
      } else if (c == '"' || c == '\'') {
        inQuotes = true;
        quoteChar = c;
      } else if (c == '.') {
        dotCount++;
      }
    }
    return dotCount + 1;
  }
}
