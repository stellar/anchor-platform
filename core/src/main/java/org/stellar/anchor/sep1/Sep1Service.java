package org.stellar.anchor.sep1;

import static org.stellar.anchor.util.Log.debug;
import static org.stellar.anchor.util.Log.debugF;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.config.Sep1Config;
import org.stellar.anchor.util.Log;
import org.stellar.anchor.util.NetUtil;

public class Sep1Service {
  private String tomlValue;

  /**
   * Construct the Sep1Service that reads the stellar.toml content from Java resource.
   *
   * @param sep1Config The Sep1 configuration.
   * @throws IOException if the file cannot be read.
   * @throws InvalidConfigException if invalid type is specified.
   */
  public Sep1Service(Sep1Config sep1Config) throws IOException, InvalidConfigException {
    if (sep1Config.isEnabled()) {
      debug("sep1Config:", sep1Config);
      switch (sep1Config.getType()) {
        case STRING:
          debug("reading stellar.toml from config[sep1.toml.value]");
          tomlValue = sep1Config.getValue();
          break;
        case FILE:
          debugF("reading stellar.toml from {}", sep1Config.getValue());
          tomlValue = Files.readString(Path.of(sep1Config.getValue()));
          break;
        case URL:
          debugF("reading stellar.toml from {}", sep1Config.getValue());
          tomlValue = NetUtil.fetch(sep1Config.getValue());
          break;
        default:
          throw new InvalidConfigException(
              String.format("invalid sep1.type: %s", sep1Config.getType()));
      }

      Log.info("Sep1Service initialized.");
    }
  }

  public String getStellarToml() {
    return tomlValue;
  }
}
