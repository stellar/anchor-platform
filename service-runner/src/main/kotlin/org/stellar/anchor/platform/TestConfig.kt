package org.stellar.anchor.platform

import org.stellar.anchor.util.StringHelper.isNotEmpty

/**
 * TestConfig is a class that reads and merges env variables from the following files in the
 * following order: The later files override the previous ones.
 * - profiles/default/test.env
 * - profiles/default/config.env
 * - profiles/{testProfileName}/test.env (if testProfileName is not empty)
 * - profiles/{testProfileName}/config.env (if testProfileName is not empty)
 * - system env variables
 *
 * It also allows to override env variables with a custom function.
 *
 * @param testProfileName - name of the test profile to use. If null, the default test profile will
 *   be used.
 * @param customize - a function that allows to override env variables
 * @constructor creates a TestConfig instance
 */
class TestConfig {
  companion object {
    const val TEST_PROFILE_NAME = "TEST_PROFILE_NAME"
  }
  val env = mutableMapOf<String, String>()
  // override test profile name with TEST_PROFILE_NAME system env variable
  private val envProfileName = System.getenv(TEST_PROFILE_NAME) ?: null

  constructor(testProfileName: String? = null, customize: () -> Unit = {}) {
    var profileName = testProfileName
    if (System.getenv(TEST_PROFILE_NAME) != null) profileName = System.getenv(TEST_PROFILE_NAME)

    if (this.envProfileName != null) profileName = this.envProfileName
    // starting from the default test.env file
    env.putAll(readResourceAsMap("profiles/default/test.env"))
    env.putAll(readResourceAsMap("profiles/default/config.env"))
    // if test profile name is not "default", read test.env and config.env files
    if (isNotEmpty(profileName) && !"default".equals(profileName, ignoreCase = true)) {
      // read and merge test.env file
      env.putAll(readResourceAsMap("profiles/${profileName}/test.env"))
      // read and merge config.env file
      env.putAll(readResourceAsMap("profiles/${profileName}/config.env"))
    }

    // customize env variables
    customize()

    // read and merge system env variables
    env.putAll(readSystemEnvAsMap())
  }

  private fun readSystemEnvAsMap(): Map<String, String> {
    val env = mutableMapOf<String, String>()
    System.getenv().forEach { (key, value) ->
      val mappedKey = key.replace("_", ".").lowercase()
      env[mappedKey] = value
    }
    return env
  }

  fun get(key: String): String? {
    return env[key]
  }
}
