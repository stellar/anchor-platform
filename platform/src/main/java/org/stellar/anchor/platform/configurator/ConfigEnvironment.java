package org.stellar.anchor.platform.configurator;

import static org.stellar.anchor.util.StringHelper.toPosixForm;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class ConfigEnvironment {
  static volatile Map<String, String> env;

  static {
    rebuild();
  }

  public static void rebuild(Map<String, String> extra) {
    Map<String, String> newEnv = new HashMap<>();

    if (extra != null) {
      for (Map.Entry<String, String> entry : extra.entrySet()) {
        newEnv.put(toPosixForm(entry.getKey()), entry.getValue());
      }
    }

    // Read all env variables and convert everything to POSIX form
    for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
      newEnv.put(toPosixForm(entry.getKey()), entry.getValue());
    }

    Properties sysProps = System.getProperties();
    for (Map.Entry<Object, Object> entry : sysProps.entrySet()) {
      newEnv.put(toPosixForm(String.valueOf(entry.getKey())), String.valueOf(entry.getValue()));
    }

    env = newEnv;
  }

  public static void rebuild() {
    rebuild(null);
  }

  /**
   * This class seems redundant but it is necessary for JUnit test for creating configuration with
   * environment variables.
   *
   * @param name the name of the environment variable
   * @return the value of the environment variable. If the variable is not set, null is returned.
   */
  public static String getenv(String name) {
    String envValue = env.get(toPosixForm(name));
    if (envValue != null) {
      envValue = envValue.replace("\\n", "\n").replace("\\\"", "\"");
    }
    return envValue;
  }

  public static Collection<String> names() {
    return env.keySet();
  }
}
