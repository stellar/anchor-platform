package org.stellar.anchor.auth;

import com.google.gson.Gson;
import io.jsonwebtoken.io.SerializationException;
import io.jsonwebtoken.io.Serializer;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.SneakyThrows;
import org.stellar.anchor.util.GsonUtils;

public class JwtsGsonSerializer implements Serializer<Map<String, ?>> {
  private static final Gson gson = GsonUtils.getInstance();

  public static JwtsGsonSerializer newInstance() {
    return new JwtsGsonSerializer();
  }

  @Override
  public byte[] serialize(Map<String, ?> stringMap) throws SerializationException {
    return gson.toJson(stringMap).getBytes(StandardCharsets.UTF_8);
  }

  @SneakyThrows
  @Override
  public void serialize(Map<String, ?> stringMap, OutputStream outputStream)
      throws SerializationException {
    outputStream.write(serialize(stringMap));
  }
}
