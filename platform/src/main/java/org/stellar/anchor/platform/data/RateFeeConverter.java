package org.stellar.anchor.platform.data;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import java.lang.reflect.Type;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.util.GsonUtils;

@Converter
public class RateFeeConverter implements AttributeConverter<FeeDetails, String> {
  private static final Gson gson = GsonUtils.getInstance();

  @Override
  public String convertToDatabaseColumn(FeeDetails priceDetails) {
    return gson.toJson(priceDetails);
  }

  @Override
  public FeeDetails convertToEntityAttribute(String customerInfoJSON) {
    Type type = new TypeToken<FeeDetails>() {}.getType();
    return gson.fromJson(customerInfoJSON, type);
  }
}
