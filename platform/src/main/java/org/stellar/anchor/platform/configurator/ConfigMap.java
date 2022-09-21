package org.stellar.anchor.platform.configurator;

import java.util.*;
import java.util.stream.Collectors;
import lombok.Data;
import org.stellar.anchor.api.exception.InvalidConfigException;

public class ConfigMap {
  int version;
  Map<String, ConfigEntry> data;

  public ConfigMap() {
    data = new HashMap<>();
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public ConfigEntry get(String name) {
    return data.get(name);
  }

  public void put(String key, String value, ConfigSource source) {
    data.put(key, new ConfigEntry(value.trim(), source));
  }

  public void remove(String name) {
    data.remove(name);
  }

  public String getString(String key) {
    return getString(key, null);
  }

  public String getString(String key, String defaultValue) {
    ConfigEntry entry = data.get(key);
    if (entry == null) return defaultValue;
    return entry.value;
  }

  public Integer getInt(String key) throws InvalidConfigException {
    try {
      return Integer.parseInt(getString(key));
    } catch (NumberFormatException nfex) {
      throw new InvalidConfigException(String.format("[%s] is not an integer.", data.get(key)));
    }
  }

  public Boolean getBoolean(String key) {

    return Boolean.parseBoolean(getString(key));
  }

  public Collection<String> names() {
    return data.keySet();
  }

  public Map<String, String> toStringMap() {
    return data.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getValue()));
  }

  public void merge(ConfigMap config) {
    for (String name : config.names()) {
      data.put(name, config.data.get(name));
    }
  }

  public boolean sameAs(ConfigMap anotherMap) {
    for (String key : names()) {
      if (!anotherMap.getString(key).equals(getString(key))) {
        return false;
      }
    }

    return data.size() == anotherMap.data.size();
  }

  public enum ConfigSource {
    FILE,
    ENV,
    DEFAULT,
    VERSION_SCHEMA
  }

  @Data
  public static class ConfigEntry {
    String value;
    ConfigSource source;

    public ConfigEntry(String value, ConfigSource source) {
      this.value = value;
      this.source = source;
    }
  }

  public String printToString() {
    List<String> lines = new LinkedList<>();
    data.forEach((k, v) -> lines.add(String.format("%s:%s", k, v.value)));
    Collections.sort(lines);
    return String.join(System.lineSeparator(), lines);
  }
}
