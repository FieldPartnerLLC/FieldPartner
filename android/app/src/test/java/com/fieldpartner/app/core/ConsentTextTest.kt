package com.fieldpartner.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentTextTest {
  @Test fun feeShownWhenSet() {
    assertTrue(ConsentText.serviceAuthorization("Acme", 8900, "en").contains("$89.00"))
    assertFalse(ConsentText.serviceAuthorization("Acme", null, "en").contains("$"))
    assertTrue(ConsentText.serviceAuthorization("Acme", 8900, "es").contains("$89.00"))
  }

  @Test fun noLeadingIndentation() {
    for (lang in listOf("en", "es")) {
      ConsentText.serviceAuthorization("Acme", 100, lang).lines().forEach { assertFalse(it.startsWith(" ")) }
      ConsentText.recording(lang).lines().forEach { assertFalse(it.startsWith(" ")) }
    }
  }
}
