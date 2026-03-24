package org.stellar.anchor.platform.controller.custody;

import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;

public class CustodyControllerConfig {
  @InitBinder
  void initBinder(final WebDataBinder binder) {
    // Maps empty strings to null when a @RequestParam is being bound.
    // This is required due to a bug in Spring.
    binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
  }
}
