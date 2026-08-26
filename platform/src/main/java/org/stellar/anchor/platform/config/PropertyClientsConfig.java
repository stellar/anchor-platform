package org.stellar.anchor.platform.config;

import static org.apache.commons.lang3.ObjectUtils.isEmpty;
import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.error;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;
import lombok.Data;
import org.apache.commons.io.FilenameUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.client.*;
import org.stellar.anchor.client.ClientConfig.ClientType;
import org.stellar.anchor.config.ClientsConfig;
import org.stellar.anchor.util.FileUtil;
import org.stellar.anchor.util.GsonUtils;
import org.yaml.snakeyaml.Yaml;

@Data
public class PropertyClientsConfig implements ClientsConfig, Validator {
  ClientsConfigType type;
  String value;
  List<RawClient> items = new ArrayList<>();

  Gson gson = GsonUtils.getInstance();

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return ClientsConfig.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    // Parse the file and validate the contents
    try {
      parseConfigIntoItemList();
    } catch (InvalidConfigException e) {
      error("Error loading clients config value", e);
      errors.reject(
          "clients-value-not-valid", "Cannot read from clients config value: " + this.getValue());
    }

    // validate custodial client and noncustodial client
    for (RawClient item : items) {
      if (ClientType.CUSTODIAL.equals(item.getType())) {
        validateCustodialClient(item.toCustodialClient(), errors);
      } else if (ClientType.NONCUSTODIAL.equals(item.getType())) {
        validateNonCustodialClient(item.toNonCustodialClient(), errors);
      } else {
        errors.reject(
            "invalid-client-type", String.format("Client type %s is invalid", item.getType()));
      }
    }
  }

  void validateCustodialClient(CustodialClient client, Errors errors) {
    debugF("Validating custodial client {}", client);
    if (client.getSigningKeys() == null || client.getSigningKeys().isEmpty()) {
      errors.reject(
          "invalid-custodial-client-config",
          String.format(
              "Custodial client %s must have at least one signing key", client.getName()));
    }
    validateCallbackUrls(client, errors);
  }

  void validateNonCustodialClient(NonCustodialClient client, Errors errors) {
    debugF("Validating noncustodial client {}", client);
    if (client.getDomains() == null || client.getDomains().isEmpty()) {
      errors.reject(
          "invalid-noncustodial-client-config",
          String.format("NonCustodial client %s must have at least one domain", client.getName()));
    }
    validateCallbackUrls(client, errors);
  }

  void validateCallbackUrls(ClientConfig client, Errors errors) {
    debugF("Validating client {}", client);
    ImmutableMap.of(
            "callback_urls_sep6",
            Optional.ofNullable(client.getCallbackUrls())
                .map(ClientConfig.CallbackUrls::getSep6)
                .orElse(""),
            "callback_urls_sep24",
            Optional.ofNullable(client.getCallbackUrls())
                .map(ClientConfig.CallbackUrls::getSep24)
                .orElse(""),
            "callback_urls_sep31",
            Optional.ofNullable(client.getCallbackUrls())
                .map(ClientConfig.CallbackUrls::getSep31)
                .orElse(""),
            "callback_urls_sep12",
            Optional.ofNullable(client.getCallbackUrls())
                .map(ClientConfig.CallbackUrls::getSep12)
                .orElse(""))
        .forEach(
            (key, value) -> {
              if (!isEmpty(value)) {
                try {
                  new URL(value);
                } catch (MalformedURLException e) {
                  errors.reject("client-invalid-" + key, "The client." + key + " is invalid");
                }
              }
            });
  }

  private void parseConfigIntoItemList() throws InvalidConfigException {
    if (this.getType().equals(ClientsConfigType.INLINE) || isEmpty(this.getValue())) {
      return;
    }

    // 1. Parse the content into a map with "items" as the key and a List<Object> as the value.
    Map<String, List<Object>> contentMap = new HashMap<>();
    switch (this.getType()) {
      case FILE:
        contentMap = parseFileToMap(this.getValue());
        break;
      case JSON:
        contentMap = parseJsonStringToMap(this.getValue());
        break;
      case YAML:
        contentMap = parseYamlStringToMap(this.getValue());
        break;
      case DB:
        if (!isEmpty(this.getValue())) {
          items = parseLegacyValueForMigration(this.getValue());
        }
        return;
      default:
        throw new InvalidConfigException(
            String.format("client file type %s is not supported", type));
    }

    // 2. Process the map into a list of RawClient objects.
    contentMap.get("items").removeIf(Objects::isNull);
    items =
        gson.fromJson(
            gson.toJson(contentMap.get("items")), new TypeToken<List<RawClient>>() {}.getType());
  }

  private List<RawClient> parseLegacyValueForMigration(String value) throws InvalidConfigException {
    debugF("Migrating clients.value to the database, parsing: {}", value);
    Map<String, List<Object>> contentMap =
        looksLikeExistingFile(value) ? parseFileToMap(value) : parseStringToMap(value);
    if (contentMap == null || contentMap.get("items") == null) {
      throw new InvalidConfigException(
          List.of(String.format("clients.value parsed but had no 'items' key: %s", contentMap)));
    }
    contentMap.get("items").removeIf(Objects::isNull);
    List<RawClient> parsed =
        gson.fromJson(
            gson.toJson(contentMap.get("items")), new TypeToken<List<RawClient>>() {}.getType());
    debugF("Parsed {} client(s) from clients.value for migration", parsed.size());
    return parsed;
  }

  private boolean looksLikeExistingFile(String value) {
    try {
      boolean exists = java.nio.file.Files.exists(Path.of(value));
      debugF("clients.value looksLikeExistingFile check for [{}]: {}", value, exists);
      return exists;
    } catch (java.nio.file.InvalidPathException e) {
      debugF("clients.value is not a valid file path: {}", e.getMessage());
      return false;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, List<Object>> parseStringToMap(String value) throws InvalidConfigException {
    try {
      Map<String, List<Object>> asJson = parseJsonStringToMap(value);
      if (asJson != null) {
        return asJson;
      }
    } catch (Exception e) {
      debugF("clients.value is not valid JSON, trying YAML instead: {}", e.getMessage());
    }
    try {
      Object loaded = new Yaml().load(value);
      if (loaded instanceof Map) {
        return (Map<String, List<Object>>) loaded;
      }
      debugF("clients.value parsed as YAML but was not a map: {}", loaded);
    } catch (Exception e) {
      debugF("clients.value is not valid YAML either: {}", e.getMessage());
    }
    throw new InvalidConfigException(
        List.of("clients.value is not a valid file path, JSON, or YAML string"));
  }

  private Map<String, List<Object>> parseFileToMap(String filePath) throws InvalidConfigException {
    try {
      String fileContent = FileUtil.read(Path.of(filePath));
      String fileExtension = FilenameUtils.getExtension(filePath).toLowerCase();
      if ("yaml".equals(fileExtension) || "yml".equals(fileExtension)) {
        return parseYamlStringToMap(fileContent);
      } else if ("json".equals(fileExtension)) {
        return parseJsonStringToMap(fileContent);
      } else {
        throw new InvalidConfigException(
            String.format("%s is not a supported file format", filePath));
      }
    } catch (Exception ex) {
      throw new InvalidConfigException(
          List.of(String.format("Cannot read from clients file: %s", filePath)), ex);
    }
  }

  private Map<String, List<Object>> parseYamlStringToMap(String yamlString) {
    return new Yaml().load(yamlString);
  }

  private Map<String, List<Object>> parseJsonStringToMap(String jsonString) {
    return gson.fromJson(jsonString, new TypeToken<Map<String, List<Object>>>() {}.getType());
  }
}
