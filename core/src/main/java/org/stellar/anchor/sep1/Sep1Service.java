package org.stellar.anchor.sep1;

import java.io.IOException;
import org.stellar.anchor.api.exception.SepNotFoundException;
import org.stellar.anchor.config.Sep1Config;
import org.stellar.anchor.util.FileUtil;
import org.stellar.anchor.util.ResourceReader;

public class Sep1Service {
  private final Sep1Config sep1Config;
  private ResourceReader resourceReader;

  /**
   * Construct the Sep1Service that reads the stellar.toml content from Java resource.
   *
   * @param sep1Config The Sep1 configuration.
   */
  public Sep1Service(Sep1Config sep1Config) {
    this.sep1Config = sep1Config;
  }

  /**
   * Construct the Sep1Service that reads the stellar.toml content by the resourceReader.
   *
   * @param sep1Config The Sep1 configuration.
   * @param resourceReader The resource reader that reads the sep1 contents.
   */
  public Sep1Service(Sep1Config sep1Config, ResourceReader resourceReader) {
    this.sep1Config = sep1Config;
    this.resourceReader = resourceReader;
  }

  public String getStellarToml() throws IOException, SepNotFoundException {
    if (resourceReader == null) {
      return FileUtil.getResourceFileAsString(sep1Config.getStellarFile());
    }

    return resourceReader.readResourceAsString(sep1Config.getStellarFile());
  }
}
