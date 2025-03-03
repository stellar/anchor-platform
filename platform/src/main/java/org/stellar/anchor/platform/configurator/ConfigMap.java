package org.stellar.anchor.platform.configurator;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.stellar.anchor.api.exception.InvalidConfigException;

public class ConfigMap {

  @Getter @Setter int version;
  final TreeMap<String, ConfigEntry> data;

  // ConfigMap keys will be in normalized form (dot separated hierarchy)
  public ConfigMap() {
    data = new TreeMap<>();
  }

  public ConfigEntry get(String name) {
    return data.get(name);
  }

  public void put(String key, String value, ConfigSource source) {
    if (value == null) {
      throw new IllegalArgumentException("value cannot be null" + key);
    }
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

  // TODO: instead of merging, create a property source with higher precedence
  public void merge(ConfigMap config) {
    // Matches any string of the form: <listName>[<index>].<elementName>
    // <listName> can be a dot separated hierarchy of names.
    Pattern pattern =
        Pattern.compile("^([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)*)\\[(\\d+)](?:\\.[a-zA-Z0-9_]+)*$");
    for (String name : config.names()) {
      Matcher matcher = pattern.matcher(name);
      // If the name is a list element, remove potential conflicting list names.
      if (matcher.find()) {
        String listName = matcher.group(1);
        data.remove(listName);
      }
      data.put(name, config.data.get(name));
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof ConfigMap)) {
      return false;
    }

    ConfigMap anotherMap = (ConfigMap) obj;
    if (data.size() != anotherMap.data.size()) {
      return false;
    }

    for (String key : names()) {
      if (!anotherMap.getString(key).equals(getString(key))) {
        return false;
      }
    }
    return true;
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
