package org.stellar.anchor.util;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class GsonUtils {
  private static Gson instance = null;

  public static GsonBuilder builder() {
    return new GsonBuilder()
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .registerTypeAdapter(Instant.class, new InstantConverter())
        .registerTypeAdapter(Duration.class, new DurationConverter())
        .registerTypeHierarchyAdapter(byte[].class, new ByteArrayToBase64TypeAdapter());
  }

  public static Gson getInstance() {
    if (instance == null) instance = builder().create();
    return instance;
  }

  public static Map<String, String> fromJsonToMap(String json) {
    return getInstance().fromJson(json, new TypeToken<HashMap<String, String>>() {}.getType());
  }
}

class DurationConverter implements JsonSerializer<Duration>, JsonDeserializer<Duration> {
  @Override
  public JsonElement serialize(Duration src, Type typeOfSrc, JsonSerializationContext context) {
    return new JsonPrimitive(src.toMillis());
  }

  @Override
  public Duration deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
      throws JsonParseException {
    return Duration.of(Long.parseLong(json.getAsString()), ChronoUnit.MILLIS);
  }
}

class InstantConverter implements JsonSerializer<Instant>, JsonDeserializer<Instant> {
  private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ISO_INSTANT;

  @Override
  public JsonElement serialize(Instant src, Type typeOfSrc, JsonSerializationContext context) {
    return new JsonPrimitive(dateTimeFormatter.format(src));
  }

  @Override
  public Instant deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
      throws JsonParseException {
    return dateTimeFormatter.parse(json.getAsString(), Instant::from);
  }
}

class ByteArrayToBase64TypeAdapter implements JsonSerializer<byte[]>, JsonDeserializer<byte[]> {

  @Override
  public byte[] deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
      throws JsonParseException {
    return Base64.getDecoder().decode(json.getAsString());
  }

  @Override
  public JsonElement serialize(byte[] src, Type typeOfSrc, JsonSerializationContext context) {
    return new JsonPrimitive(Base64.getEncoder().encodeToString(src));
  }
}
