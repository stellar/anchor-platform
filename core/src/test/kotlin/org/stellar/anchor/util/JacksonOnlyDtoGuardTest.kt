package org.stellar.anchor.util

import com.google.gson.annotations.SerializedName
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider

/**
 * The servers serialize every body with Gson, which ignores `@JsonProperty`: a field annotated for
 * Jackson only goes out under its raw Java name. This guard fails when a DTO in
 * `org.stellar.anchor.api` carries a `@JsonProperty` without a `@SerializedName` of the same value.
 * Jackson's annotation is read by name so the test needs no compile dependency on it.
 */
class JacksonOnlyDtoGuardTest {
  private val jsonProperty = "com.fasterxml.jackson.annotation.JsonProperty"

  private fun jacksonName(field: Field): String? =
    field.annotations
      .firstOrNull { it.annotationClass.java.name == jsonProperty }
      ?.let { it.annotationClass.java.getMethod("value").invoke(it) as String }

  private fun serializedNames(field: Field): Set<String> =
    field.getAnnotation(SerializedName::class.java)?.let { setOf(it.value) + it.alternate }
      ?: emptySet()

  private fun violations(classes: Collection<Class<*>>): List<String> =
    classes.flatMap { cls ->
      cls.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) }
        .mapNotNull { field ->
          val name = jacksonName(field)
          if (name != null && name !in serializedNames(field)) {
            "${cls.name}.${field.name} has @JsonProperty(\"$name\") but no @SerializedName(\"$name\")"
          } else null
        }
    }

  private fun apiClasses(): List<Class<*>> {
    val scanner =
      object : ClassPathScanningCandidateComponentProvider(false) {
        // every class counts, not only concrete top-level ones
        override fun isCandidateComponent(beanDefinition: AnnotatedBeanDefinition) = true
      }
    scanner.addIncludeFilter { _, _ -> true }
    return scanner.findCandidateComponents("org.stellar.anchor.api").map {
      Class.forName(it.beanClassName, false, javaClass.classLoader)
    }
  }

  // WIRE-10
  @Test
  fun `test no DTO field carries a JsonProperty without a matching SerializedName`() {
    val classes = apiClasses()

    // the scan must see the DTOs, or an empty result would pass for the wrong reason
    val annotated =
      classes.flatMap { it.declaredFields.toList() }.filter { jacksonName(it) != null }
    assertTrue(annotated.size >= 10, "scan found only ${annotated.size} @JsonProperty fields")

    assertEquals(emptyList<String>(), violations(classes))
  }

  // WIRE-10
  @Test
  fun `test the guard names a class and field that carry only a JsonProperty`() {
    class JacksonOnly {
      @com.fasterxml.jackson.annotation.JsonProperty("home_domain") var homeDomain: String? = null
    }
    class Both {
      @com.fasterxml.jackson.annotation.JsonProperty("home_domain")
      @SerializedName("home_domain")
      var homeDomain: String? = null
    }
    class Mismatched {
      @com.fasterxml.jackson.annotation.JsonProperty("home_domain")
      @SerializedName("homeDomain")
      var homeDomain: String? = null
    }

    val found =
      violations(listOf(JacksonOnly::class.java, Both::class.java, Mismatched::class.java))

    assertEquals(2, found.size, found.toString())
    assertTrue(found[0].contains("JacksonOnly.homeDomain"), found[0])
    assertTrue(found[1].contains("Mismatched.homeDomain"), found[1])
  }
}
