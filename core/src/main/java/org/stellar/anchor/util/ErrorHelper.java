package org.stellar.anchor.util;

import static org.stellar.anchor.util.Log.*;

public class ErrorHelper {
  public static <T extends Exception> void logErrorAndThrow(String message, Class<T> exceptionClass)
      throws T {
    error(message);
    try {
      // Create a new instance of the exception class with the message
      throw exceptionClass.getDeclaredConstructor(String.class).newInstance(message);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("Error creating exception", e);
    }
  }
}
