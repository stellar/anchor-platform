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
  //
  // This check re-implements part of toml4j's lexer and is NOT a boundary for untrusted input: it
  // can be bypassed with quoting shapes it reads differently from toml4j. It only covers
  // operator-controlled content (the anchor's own stellar.toml). A document fetched from a
  // client_domain must go through readSigningKey / extractSigningKey and never reach toml4j.
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

  /**
   * Fetches a stellar.toml served by an untrusted {@code client_domain} and returns its top-level
   * {@code SIGNING_KEY}. The document is never given to a TOML parser, see {@link
   * #extractSigningKey}.
   *
   * @return the signing key text, or {@code null} if the document has none.
   */
  public static String readSigningKey(String url, OkHttpClient client)
      throws IOException, InvalidConfigException {
    try {
      String tomlValue = NetUtil.fetch(url, DEFAULT_MAX_RESPONSE_SIZE, client);
      return extractSigningKey(tomlValue);
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

  private static final String SIGNING_KEY_NAME = "SIGNING_KEY";
  private static final String INVALID_TOML_MESSAGE =
      "Failed to parse TOML content. Invalid Config.";

  /**
   * Reads the top-level {@code SIGNING_KEY} from a stellar.toml without building a TOML parser.
   *
   * <p>This is the only reader for documents served by a {@code client_domain}, which is attacker
   * controlled: the cost is one pass over the document, whatever the document contains. It scans
   * line by line, stops at the first line that starts with {@code [} (everything after a table
   * header is not top-level), and interprets only the {@code SIGNING_KEY} line: a single-line
   * {@code "..."} string without a backslash, or a single-line {@code '...'} string, optionally
   * followed by a {@code #} comment. It is not a TOML validator.
   *
   * @return the key's text, or {@code null} when no top-level {@code SIGNING_KEY} line exists.
   * @throws InvalidConfigException if the key appears twice or its value is not such a string.
   */
  public static String extractSigningKey(String toml) throws InvalidConfigException {
    String found = null;
    int length = toml.length();
    int lineStart = 0;
    while (lineStart <= length) {
      int lineEnd = toml.indexOf('\n', lineStart);
      if (lineEnd < 0) {
        lineEnd = length;
      }
      int i = skipBlanks(toml, lineStart, lineEnd);
      if (i < lineEnd && toml.charAt(i) == '[') {
        return found;
      }
      if (i < lineEnd
          && toml.charAt(i) != '#'
          && toml.startsWith(SIGNING_KEY_NAME, i)
          && !isBareKeyChar(toml, i + SIGNING_KEY_NAME.length(), lineEnd)) {
        if (found != null) {
          throw invalidToml("duplicate SIGNING_KEY");
        }
        found = readSigningKeyValue(toml, i + SIGNING_KEY_NAME.length(), lineEnd);
      }
      lineStart = lineEnd + 1;
    }
    return found;
  }

  private static String readSigningKeyValue(String toml, int from, int lineEnd)
      throws InvalidConfigException {
    int i = skipBlanks(toml, from, lineEnd);
    if (i >= lineEnd || toml.charAt(i) != '=') {
      throw invalidToml("SIGNING_KEY is not followed by '='");
    }
    i = skipBlanks(toml, i + 1, lineEnd);
    if (i >= lineEnd) {
      throw invalidToml("SIGNING_KEY has no value");
    }
    char quote = toml.charAt(i);
    if (quote != '"' && quote != '\'') {
      throw invalidToml("SIGNING_KEY value is not a quoted string");
    }
    if (toml.startsWith("" + quote + quote + quote, i)) {
      throw invalidToml("SIGNING_KEY value is a multi-line string");
    }
    int valueStart = i + 1;
    int valueEnd = valueStart;
    while (valueEnd < lineEnd && toml.charAt(valueEnd) != quote) {
      if (quote == '"' && toml.charAt(valueEnd) == '\\') {
        throw invalidToml("SIGNING_KEY value contains an escape");
      }
      valueEnd++;
    }
    if (valueEnd >= lineEnd) {
      throw invalidToml("SIGNING_KEY value is not terminated");
    }
    int after = skipBlanks(toml, valueEnd + 1, lineEnd);
    if (after < lineEnd && toml.charAt(after) != '#') {
      throw invalidToml("SIGNING_KEY value is followed by other text");
    }
    return toml.substring(valueStart, valueEnd);
  }

  /** First index in [from, end) that is not whitespace, or {@code end}. */
  private static int skipBlanks(String s, int from, int end) {
    int i = from;
    while (i < end && Character.isWhitespace(s.charAt(i))) {
      i++;
    }
    return i;
  }

  private static boolean isBareKeyChar(String s, int index, int end) {
    if (index >= end) {
      return false;
    }
    char c = s.charAt(index);
    return (c >= 'A' && c <= 'Z')
        || (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || c == '_'
        || c == '-';
  }

  private static InvalidConfigException invalidToml(String reason) {
    infoF("Rejected TOML document: {}.", reason);
    return new InvalidConfigException(INVALID_TOML_MESSAGE);
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
