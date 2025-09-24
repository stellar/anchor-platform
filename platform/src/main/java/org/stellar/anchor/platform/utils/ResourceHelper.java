package org.stellar.anchor.platform.utils;

import java.io.File;
import java.io.IOException;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

public class ResourceHelper {
  static final ResourceLoader resourceLoader = new DefaultResourceLoader();

  public static Resource resource(String resource) {
    return resourceLoader.getResource(resource);
  }

  public static File findResourceFile(Resource resource) throws IOException {
    File resourceFile = null;
    if (resource.exists()) resourceFile = resource.getFile();
    if (resourceFile == null || !resourceFile.isFile())
      throw new IOException("Resource not found: " + resource.getFilename());
    return resourceFile;
  }

  /**
   * Find a file in the filesystem. If not existent in the filesystem, then try in the classpath.
   *
   * @param file
   * @return File the found file
   * @throws IOException if the file is not found
   */
  public static File findFileThenResource(String file) throws IOException {
    File f = new File(file);
    if (f.exists()) {
      return f;
    }
    return findResourceFile(resource(file));
  }
}
