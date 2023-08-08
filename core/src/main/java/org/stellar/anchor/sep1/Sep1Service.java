package org.stellar.anchor.sep1;

import static org.stellar.anchor.util.Log.debugF;

import java.io.IOException;
import java.nio.file.Path;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.config.Sep1Config;
import org.stellar.anchor.util.FileUtil;
import org.stellar.anchor.util.NetUtil;

public class Sep1Service {

  private String tomlValue;

  public Sep1Service(Sep1Config sep1Config) throws IOException, InvalidConfigException {
    if (sep1Config.isEnabled()) {
      this.tomlValue = handleConfigType(sep1Config);
    }
  }

  private String handleConfigType(Sep1Config sep1Config)
      throws IOException, InvalidConfigException {
    switch (sep1Config.getType()) {
      case STRING:
        debugF("reading stellar.toml from config[sep1.toml.value]");
        return sep1Config.getValue();
      case FILE:
        debugF("reading stellar.toml from {}", sep1Config.getValue());
        return FileUtil.read(Path.of(sep1Config.getValue()));
      case URL:
        debugF("reading stellar.toml from {}", sep1Config.getValue());
        return NetUtil.fetch(sep1Config.getValue());
      default:
        throw new InvalidConfigException(
            String.format("invalid sep1.type: %s", sep1Config.getType()));
    }
  }

  public String getStellarToml() {
    return tomlValue;
  }
}
