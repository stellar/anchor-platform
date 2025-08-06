package org.stellar.anchor.util;

import static org.stellar.anchor.util.Log.errorEx;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.stellar.anchor.api.exception.SepValidationException;
import org.stellar.anchor.config.LanguageConfig;

public class SepLanguageHelper {
  static Map<String, Rfc4646Language> alternativeLangs = null;

  public static String validateLanguage(LanguageConfig languageConfig, String lang) {
    if (alternativeLangs == null) {
      alternativeLangs = prepareAlternativeLangs(languageConfig);
    }

    if (lang != null) {
      List<String> languages = languageConfig.getLanguages();
      if (languages != null && !languages.isEmpty()) {
        if (languages.stream().noneMatch(l -> l.equalsIgnoreCase(lang))) {
          return getAlternative(lang);
        }
      }
      return lang;
    } else {
      return getAlternative(lang);
    }
  }

  public static void reset() {
    alternativeLangs = null;
  }

  static Map<String, Rfc4646Language> prepareAlternativeLangs(LanguageConfig stellarNetworkConfig) {
    Map<String, Rfc4646Language> alternatives = new HashMap<>();
    stellarNetworkConfig
        .getLanguages()
        .forEach(
            lang -> {
              try {
                Rfc4646Language isoLang = Rfc4646Language.of(lang);
                alternatives.putIfAbsent(isoLang.getAltLangKey(), isoLang);
              } catch (SepValidationException ex) {
                errorEx(ex);
              }
            });
    return alternatives;
  }

  static final String LANGUAGE_WITHOUT_LOCALE_DEFAULT = "en";
  static final String LANGUAGE_WITH_LOCALE_DEFAULT = "en-US";

  static String getAlternative(String lang) {
    try {
      Rfc4646Language language = Rfc4646Language.of(lang);
      Rfc4646Language alt = alternativeLangs.get(language.getAltLangKey());
      if (alt == null) {
        if (language.getLocale() == null) {
          return LANGUAGE_WITHOUT_LOCALE_DEFAULT;
        }
        return LANGUAGE_WITH_LOCALE_DEFAULT;
      }
      return alt.toString();
    } catch (SepValidationException e) {
      return LANGUAGE_WITH_LOCALE_DEFAULT;
    }
  }
}

/**
 * The representation class of RFC-4646 to identify a language. <a
 * href="https://www.ietf.org/rfc/rfc4646.txt">https://www.ietf.org/rfc/rfc4646.txt</a>
 */
@Getter
@AllArgsConstructor
class Rfc4646Language {
  String language;
  String locale;

  public static Rfc4646Language of(String lang) throws SepValidationException {
    if (lang == null) {
      throw new SepValidationException("lang is null");
    }
    String[] tokens = lang.split("-");
    return switch (tokens.length) {
      case 1 -> new Rfc4646Language(tokens[0], null);
      case 2 -> new Rfc4646Language(tokens[0], tokens[1]);
      default ->
          throw new SepValidationException(String.format("Invalid language format: %s", lang));
    };
  }

  public String getAltLangKey() {
    if (locale == null) {
      return language;
    }
    return "+" + language;
  }

  public String toString() {
    return (locale == null) ? language : language + "-" + locale;
  }
}
