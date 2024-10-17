package org.stellar.anchor.platform.data;

import com.google.gson.Gson;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.stellar.anchor.api.shared.StellarId;
import org.stellar.anchor.util.GsonUtils;

@Converter
public class StellarIdConverter implements AttributeConverter<StellarId, String> {
  private static final Gson gson = GsonUtils.getInstance();

  @Override
  public String convertToDatabaseColumn(StellarId stellarId) {
    return gson.toJson(stellarId);
  }

  @Override
  public StellarId convertToEntityAttribute(String stellarIdJson) {
    return gson.fromJson(stellarIdJson, StellarId.class);
  }
}
