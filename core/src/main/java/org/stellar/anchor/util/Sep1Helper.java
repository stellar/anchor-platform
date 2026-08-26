package org.stellar.anchor.util;

import static org.stellar.anchor.util.Log.*;

import com.moandjiezana.toml.Toml;
import java.io.IOException;
import java.util.List;
import okhttp3.OkHttpClient;
import org.stellar.anchor.api.exception.InvalidConfigException;

public class Sep1Helper {
  private static final long DEFAULT_MAX_RESPONSE_SIZE = 100 * 1024;

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
    } catch (Exception e) {
      // Obfuscate the message and rethrow
      String obfuscatedMessage = "Failed to parse TOML content. Invalid Config.";
      Log.error(e.toString()); // Log the parsing exception
      throw new InvalidConfigException(
          obfuscatedMessage); // Preserve the original exception as the cause
    }
  }

  public static class TomlContent {
    private final Toml toml;

    TomlContent(String tomlString) throws InvalidConfigException {
      try {
        toml = new Toml().read(tomlString);
      } catch (Exception e) {
        // Obfuscate the message and rethrow
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
}
