package com.fieldpartner.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as the iOS CommandParserTests, plus extra field phrasing. Both phones must agree. */
class CommandParserTest {
  private val p = CommandParser("hey dispatch")

  @Test fun questionAfterWakePhrase() =
    assertEquals(VoiceCommand.Ask("what does three flashes mean on this board"), p.parse("Hey Dispatch, what does three flashes mean on this board?"))

  @Test fun recognizerVariants() {
    assertEquals(VoiceCommand.Ask("read the plate"), p.parse("Hi dispatch read the plate"))
    assertEquals(VoiceCommand.Ask("read the plate"), p.parse("okay dispatch read the plate"))
    assertEquals(VoiceCommand.Ask("read the plate"), p.parse("hey dispatcher read the plate"))
    assertEquals(VoiceCommand.Ask("read the plate"), p.parse("hey this patch read the plate"))
    assertEquals(VoiceCommand.Ask("read the plate"), p.parse("OK Dispatch: read the plate."))
  }

  @Test fun wakeMidSentence() = assertEquals(VoiceCommand.Ask("is this capacitor bad"), p.parse("alright so hey dispatch is this capacitor bad"))

  @Test fun wakeAloneOpensMic() = assertEquals(VoiceCommand.Ask(""), p.parse("Hey dispatch."))

  @Test fun customerTalkIgnored() {
    assertNull(p.parse("It started making that noise yesterday, can you stop it?"))
    assertNull(p.parse("Stop."))
    assertNull(p.parse("We called dispatch twice already"))
    assertNull(p.parse("Hey, did dispatch send you?"))
    assertNull(p.parse(""))
    assertNull(p.parse("   "))
  }

  @Test fun shortCommandsAfterWake() {
    assertEquals(VoiceCommand.Stop, p.parse("hey dispatch stop"))
    assertEquals(VoiceCommand.PauseCamera, p.parse("hey dispatch pause camera"))
    assertEquals(VoiceCommand.ResumeCamera, p.parse("hey dispatch camera on"))
    assertEquals(VoiceCommand.PlayHint, p.parse("hey dispatch go ahead"))
    assertEquals(VoiceCommand.CallExpert, p.parse("hey dispatch call Mitch"))
    assertEquals(VoiceCommand.CallExpert, p.parse("hey dispatch call supervisor"))
    assertEquals(VoiceCommand.CallExpert, p.parse("hey dispatch get help"))
    assertEquals(VoiceCommand.StopRecording, p.parse("hey dispatch stop recording"))
    assertEquals(VoiceCommand.ContinueLast, p.parse("hey dispatch repeat that"))
  }

  @Test fun questionsThatStartLikeCommandsStayQuestions() {
    // "call" followed by a long sentence is a question, not a page.
    assertEquals(VoiceCommand.Ask("call me crazy but is this wire hot"), p.parse("hey dispatch call me crazy but is this wire hot"))
    assertEquals(VoiceCommand.Ask("stopping distance for this ladder"), p.parse("hey dispatch stopping distance for this ladder"))
  }

  @Test fun shortCommandsByName() {
    assertEquals(VoiceCommand.Stop, p.parse("Partner, stop."))
    assertEquals(VoiceCommand.ContinueLast, p.parse("partner continue"))
    assertEquals(VoiceCommand.Acknowledge, p.parse("partner acknowledged"))
    assertNull(p.parse("my partner will be here tomorrow"))
    assertNull(p.parse("partner what is this")) // the name alone never opens a question
  }

  @Test fun disagreement() {
    val r = p.parse("hey dispatch that's wrong, it's a heat pump")
    assertTrue(r is VoiceCommand.Disputed)
  }

  @Test fun partialWake() {
    assertTrue(p.wakeHeard("hey dispatch what"))
    assertFalse(p.wakeHeard("hey dis"))
  }

  @Test fun customWakePhrase() {
    val roofing = CommandParser("Tech Assist")
    assertEquals(VoiceCommand.Ask("is this flashing lifted"), roofing.parse("tech assist, is this flashing lifted?"))
    assertNull(roofing.parse("hey dispatch is this flashing lifted"))
  }

  @Test fun wordBoundary() {
    val short = CommandParser("hey max")
    assertNull(short.parse("they maximum load is fine"))
    assertNull(short.parse("hey maxwell come here"))
  }

  @Test fun talkAboutADispatchDoesNotWake() {
    assertNull(p.parse("We sent a dispatch out to the site yesterday"))
    assertNull(p.parse("I said okay, dispatch will call you back"))
    assertNull(p.parse("Hey, dispatching the truck now"))
  }

  @Test fun everydayWordVariantsWorkAtTheStart() {
    assertEquals(VoiceCommand.Ask("read the plate"), p.parse("Okay dispatch, read the plate"))
    assertEquals(VoiceCommand.Ask("what is this code"), p.parse("A dispatch what is this code"))
  }

  @Test fun wakePhraseNeedsAWordBoundaryAfterIt() =
    assertNull(CommandParser("hey max").parse("hey maxwell is here"))

  @Test fun laterOccurrenceIsFound() =
    assertEquals(VoiceCommand.Ask("is this ok"), CommandParser("hey max").parse("they max out, hey max is this ok"))

  @Test fun spanishSpeechStillMatchesWake() =
    assertEquals(VoiceCommand.Ask("qué significa esta luz"), p.parse("Hey dispatch, ¿qué significa esta luz?"))
}
