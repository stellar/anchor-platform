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
        items = new ArrayList<>();
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
