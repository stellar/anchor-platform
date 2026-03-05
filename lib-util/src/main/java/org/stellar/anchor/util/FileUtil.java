package org.stellar.anchor.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.stellar.anchor.api.exception.SepNotFoundException;

public class FileUtil {
  public static String getResourceFileAsString(String fileName)
      throws IOException, SepNotFoundException {
    ClassLoader classLoader = FileUtil.class.getClassLoader();
    try (InputStream is = classLoader.getResourceAsStream(fileName)) {
      if (is == null) {
        throw new SepNotFoundException(String.format("%s was not found in classpath.", fileName));
      }
      try (InputStreamReader isr = new InputStreamReader(is);
          BufferedReader reader = new BufferedReader(isr)) {
        return reader.lines().collect(Collectors.joining(System.lineSeparator()));
      }
    }
  }

  public static String read(Path path) throws IOException {
    return Files.readString(path);
  }

  public static String read(Path path, long maxSize) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    long totalRead = 0;

    try (InputStream inputStream = Files.newInputStream(path)) {
      int read;
      while ((read = inputStream.read(buffer)) != -1) {
        totalRead += read;
        if (totalRead > maxSize) {
          throw new IOException("File exceeds max size of " + maxSize + " bytes");
        }
        outputStream.write(buffer, 0, read);
      }
    }

    return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
  }
}
