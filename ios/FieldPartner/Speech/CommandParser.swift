import Foundation

/// Turns the running on-device transcript into commands. Pure logic, unit tested.
///
/// The wake phrase starts a question ("hey dispatch, what's this error code?").
/// A few short commands work with the assistant's name alone ("partner, stop") or
/// right after the wake phrase ("hey dispatch, pause camera").
enum VoiceCommand: Equatable {
  case ask(String)  // wake phrase + question text (may be empty: tech is still talking)
  case stop
  case continueLast
  case pauseCamera
  case resumeCamera
  case playHint
  case acknowledge
  case callExpert
  case stopRecording
  case disputed(String)
}

struct CommandParser {
  var wakePhrase: String
  /// Short name usable for quick commands, e.g. "partner, stop".
  var assistantName: String = "partner"

  /// Recognizers hear "hey dispatch" in many ways. These variants all count.
  var wakeVariants: [String] {
    let base = normalize(wakePhrase)
    var v: Set<String> = [base]
    if base.hasPrefix("hey ") {
      let rest = String(base.dropFirst(4))
      v.insert("hi \(rest)")
      v.insert("hay \(rest)")
      v.insert("a \(rest)")
      v.insert("ok \(rest)")
      v.insert("okay \(rest)")
    }
    if base.contains("dispatch") {
      v.insert(base.replacingOccurrences(of: "dispatch", with: "dispatcher"))
      v.insert(base.replacingOccurrences(of: "dispatch", with: "this patch"))
    }
    return Array(v).sorted { $0.count > $1.count }
  }

  func normalize(_ s: String) -> String {
    let lowered = s.lowercased()
    let cleaned = lowered.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) || $0 == " " ? Character($0) : " " }
    return String(cleaned).split(separator: " ").joined(separator: " ")
  }

  /// Finds the wake phrase in an utterance. Returns the text after it, or nil if absent.
  func textAfterWake(in utterance: String) -> String? {
    let n = normalize(utterance)
    for variant in wakeVariants {
      if let range = n.range(of: variant) {
        // Must be at a word boundary: "hey dispatcher" should not match inside other words.
        let before = n[n.startIndex..<range.lowerBound]
        if !before.isEmpty && !before.hasSuffix(" ") { continue }
        return String(n[range.upperBound...]).trimmingCharacters(in: .whitespaces)
      }
    }
    return nil
  }

  /// Short commands. Only recognized when addressed (after the wake phrase or the name),
  /// so a customer saying "stop" never stops anything.
  func shortCommand(_ text: String) -> VoiceCommand? {
    let t = normalize(text)
    let table: [(String, VoiceCommand)] = [
      ("stop recording", .stopRecording),
      ("stop", .stop),
      ("quiet", .stop),
      ("hold on", .stop),
      ("continue", .continueLast),
      ("go on", .continueLast),
      ("repeat", .continueLast),
      ("pause camera", .pauseCamera),
      ("camera off", .pauseCamera),
      ("resume camera", .resumeCamera),
      ("camera on", .resumeCamera),
      ("go ahead", .playHint),
      ("acknowledged", .acknowledge),
      ("acknowledge", .acknowledge),
      ("call expert", .callExpert),
      ("call supervisor", .callExpert),
      ("get help", .callExpert),
    ]
    for (phrase, cmd) in table where t == phrase || t.hasPrefix(phrase + " ") {
      return cmd
    }
    if t.hasPrefix("call ") && t.split(separator: " ").count <= 3 { return .callExpert }
    if t.hasPrefix("that s wrong") || t.hasPrefix("thats wrong") || t.hasPrefix("i disagree") {
      return .disputed(t)
    }
    return nil
  }

  /// Interprets one finished utterance from the transcript.
  func parse(finalUtterance utterance: String) -> VoiceCommand? {
    if let rest = textAfterWake(in: utterance) {
      if let cmd = shortCommand(rest) { return cmd }
      return .ask(rest)
    }
    let n = normalize(utterance)
    let name = normalize(assistantName)
    if n == name || n.hasPrefix(name + " ") {
      let rest = String(n.dropFirst(name.count)).trimmingCharacters(in: .whitespaces)
      return shortCommand(rest)
    }
    return nil
  }

  /// Early wake detection on partial results, so the chime plays while the tech is still talking.
  func wakeHeard(inPartial partial: String) -> Bool {
    textAfterWake(in: partial) != nil
  }
}
