package org.stellar.anchor.sep10;

import com.google.gson.Gson;
import io.jsonwebtoken.io.DeserializationException;
import io.jsonwebtoken.io.Deserializer;
import java.io.Reader;
import java.util.Map;

public class JwtsGsonDeserializer implements Deserializer<Map<String, ?>> {
  private static final Gson gson = new Gson();

  public static JwtsGsonDeserializer newInstance() {
    return new JwtsGsonDeserializer();
  }

  @Override
  public Map<String, ?> deserialize(byte[] bytes) throws DeserializationException {
    return gson.fromJson(new String(bytes), Map.class);
  }

  @Override
  public Map<String, ?> deserialize(Reader reader) throws DeserializationException {
    return gson.fromJson(reader, Map.class);
  }
}
