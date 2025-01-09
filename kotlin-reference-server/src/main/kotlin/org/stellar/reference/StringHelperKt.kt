package org.stellar.reference

/**
 * Converts a dot-separated string to camelCase. For example: - "kt.reference.server.config" becomes
 * "ktReferenceServerConfig"
 *
 * @param s The dot-separated string to be converted.
 * @return A string in camelCase format.
 * @throws NullPointerException If the input string is null.
 */
fun dotToCamelCase(s: String): String {
  return s.split(".") // Split by dot
    .mapIndexed { index, part ->
      if (part.isEmpty()) {
        "" // Ignore empty parts caused by consecutive dots or leading/trailing dots
      } else if (index == 0) {
        part.replaceFirstChar {
          it.lowercaseChar()
        } // Ensure the first part starts with a lowercase letter
      } else {
        part.replaceFirstChar { it.uppercaseChar() } // Capitalize the first letter of other parts
      }
    }
    .joinToString("") // Join all parts back together as camelCase
}
