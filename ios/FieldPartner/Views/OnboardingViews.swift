import AVFoundation
import CoreLocation
import Speech
import SwiftUI

// MARK: - Join with an invite code

/// A new technician types the 8-letter code from their manager, sees who it is for,
/// picks a password, and is signed in.
struct JoinView: View {
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) var dismiss
  @State private var server = AppSettings.shared.apiBaseURL
  @State private var code = ""
  @State private var info: InviteInfo?
  @State private var password = ""
  @State private var password2 = ""
  @State private var busy = false

  private var codeReady: Bool { code.filter { $0.isLetter || $0.isNumber }.count >= 8 }

  var body: some View {
    NavigationStack {
      Form {
        Section {
          Text("Your manager sent you an 8-letter invite code by text, WhatsApp or email.")
        }
        Section("Server address (from the invite message)") {
          TextField("https://api.yourcompany.com", text: $server)
            .textInputAutocapitalization(.never)
            .keyboardType(.URL)
            .autocorrectionDisabled()
        }
        Section("Invite code") {
          TextField("ABCD-2345", text: $code)
            .textInputAutocapitalization(.characters)
            .autocorrectionDisabled()
            .onChange(of: code) { _, _ in info = nil }
          if info == nil {
            Button {
              busy = true
              Task {
                info = await model.checkInvite(server: server, code: code)
                busy = false
              }
            } label: {
              HStack { Spacer(); if busy { ProgressView() } else { Text("Check code").bold() }; Spacer() }
            }
            .disabled(busy || !codeReady)
          }
        }
        if let info {
          Section {
            VStack(alignment: .leading, spacing: 4) {
              Text(info.companyName).font(.headline)
              Text("invited \(info.name) (\(info.email)) as \(info.role == "tech" ? "a technician" : "an \(info.role)").")
              Text("Not you? Ask your manager for your own code.").font(.footnote).foregroundStyle(.secondary)
            }
          }
          Section("Choose a password (10+ characters)") {
            SecureField("Password", text: $password).textContentType(.newPassword)
            SecureField("Password again", text: $password2).textContentType(.newPassword)
            if !password2.isEmpty && password != password2 {
              Text("The two passwords don't match.").foregroundStyle(.red).font(.footnote)
            }
          }
          Section {
            Button {
              busy = true
              Task {
                await model.acceptInvite(code: code, password: password)
                busy = false
                if model.signedIn { dismiss() }
              }
            } label: {
              HStack { Spacer(); if busy { ProgressView() } else { Text("Create my account").bold() }; Spacer() }
            }
            .disabled(busy || password.count < 10 || password != password2)
            Text("Sign in next time with \(info.email) and this password.").font(.footnote).foregroundStyle(.secondary)
          }
        }
      }
      .navigationTitle("Join your company")
      .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
    }
  }
}

// MARK: - First-run setup

/// Permissions, camera choice, Meta glasses connection and a live video test.
/// Shown once per user per phone; Settings > Run setup again brings it back.
struct SetupView: View {
  @EnvironmentObject var model: AppModel
  @EnvironmentObject var glasses: GlassesManager
  @ObservedObject private var settings = AppSettings.shared
  @State private var step = 1
  @State private var useGlasses = true

  var body: some View {
    NavigationStack {
      Form {
        switch step {
        case 1: PermissionsStep(onNext: { step = 2 })
        case 2: cameraStep
        case 3: ConnectStep(onNext: { step = 4 })
        case 4: TestStep(useGlasses: useGlasses, onNext: { step = 5 })
        default: readyStep
        }
        Section {
          Button("Skip setup for now (you can run it again in Settings)") { model.finishSetup() }
            .font(.footnote)
        }
      }
      .navigationTitle(title)
      .navigationBarTitleDisplayMode(.inline)
    }
  }

  private var title: String {
    switch step {
    case 1: return "Setup 1 of 4: Allow access"
    case 2: return "Setup 2 of 4: Your camera"
    case 3: return "Setup 3 of 4: Connect glasses"
    case 4: return "Setup 4 of 4: Test the video"
    default: return "You're ready"
    }
  }

  private var cameraStep: some View {
    Group {
      Section("What will you wear on the job?") {
        Picker("Camera", selection: $useGlasses) {
          VStack(alignment: .leading) {
            Text("Meta glasses")
            Text("Ray-Ban Meta or Oakley Meta. Hands free: the AI sees what you see.").font(.footnote).foregroundStyle(.secondary)
          }.tag(true)
          VStack(alignment: .leading) {
            Text("Phone camera")
            Text("No glasses yet. Uses the phone's back camera.").font(.footnote).foregroundStyle(.secondary)
          }.tag(false)
        }
        .pickerStyle(.inline)
        .labelsHidden()
        #if DEBUG
        Button {
          glasses.enableSimulatedGlasses(demoVideo: true)
          useGlasses = true
          settings.usePhoneCamera = false
          step = 4
        } label: {
          Label("Use simulated glasses (demo video, for testing)", systemImage: "play.rectangle")
        }
        #endif
      }
      Section {
        Button {
          settings.usePhoneCamera = !useGlasses
          if useGlasses {
            step = 3
          } else {
            AVCaptureDevice.requestAccess(for: .video) { _ in
              Task { @MainActor in step = 4 }
            }
          }
        } label: {
          HStack { Spacer(); Text("Next").bold(); Spacer() }
        }
      }
    }
  }

  private var readyStep: some View {
    let wake = settings.wakePhraseOverride.isEmpty ? (model.company?.wake_phrase ?? "hey dispatch") : settings.wakePhraseOverride
    let tips: [(String, String)] = [
      ("play.circle", "Open a job and tap Start visit. Recording starts at the door."),
      ("signature", "Get the customer's consent on the phone before you go in."),
      ("waveform", "The AI listens only when you call it. Say \"\(wake), what does this error code mean?\""),
      ("mic", "Or tap Talk, press your Bluetooth clip button, or type a question."),
      ("hand.raised", "\"Partner, stop\" stops it."),
      ("person.wave.2", "\"\(wake), call expert\" pages your expert, who can see and talk to you."),
      ("video.slash", "\"\(wake), pause camera\" before you enter a bathroom or bedroom."),
      ("doc.text", "Tap End when you leave. Check the report, then Approve and send."),
    ]
    return Group {
      Section("How to work with your AI partner") {
        ForEach(tips, id: \.1) { icon, text in
          Label { Text(text) } icon: { Image(systemName: icon).foregroundStyle(Color.accentColor) }
        }
      }
      Section {
        Button {
          model.finishSetup()
        } label: {
          HStack { Spacer(); Text("Go to my jobs").bold(); Spacer() }
        }
      }
    }
  }
}

private struct StatusRow: View {
  let mark: String?
  let title: String
  let detail: String?
  let ok: Bool

  var body: some View {
    HStack(alignment: .top, spacing: 12) {
      if ok {
        Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).font(.title3)
      } else if let mark {
        Text(mark).font(.caption.bold()).frame(width: 22, height: 22).background(Circle().fill(Color(.systemGray5)))
      } else {
        Image(systemName: "circle").foregroundStyle(.secondary).font(.title3)
      }
      VStack(alignment: .leading, spacing: 2) {
        Text(title).font(.subheadline.weight(.semibold))
        if let detail { Text(detail).font(.footnote).foregroundStyle(.secondary) }
      }
    }
    .padding(.vertical, 2)
  }
}

private struct PermissionsStep: View {
  let onNext: () -> Void
  @State private var mic = AVAudioApplication.shared.recordPermission == .granted
  @State private var speech = SFSpeechRecognizer.authorizationStatus() == .authorized
  @State private var location = false
  @State private var asked = false
  private let locationManager = CLLocationManager()

  var body: some View {
    Group {
      Section {
        Text("FieldPartner needs these. Tap Allow, then accept each prompt.")
        StatusRow(mark: nil, title: "Microphone", detail: "Required. The AI and your expert hear you, and the visit is recorded.", ok: mic)
        StatusRow(mark: nil, title: "Speech recognition", detail: "Hears the wake phrase on the phone. Nothing goes to the AI until you call it.", ok: speech)
        StatusRow(mark: nil, title: "Location", detail: "Stamps where the customer signed consent.", ok: location)
        Text("Bluetooth is asked for when you connect the glasses.").font(.footnote).foregroundStyle(.secondary)
      }
      Section {
        Button {
          asked = true
          Task {
            mic = await AVAudioApplication.requestRecordPermission()
            speech = await TranscriptEngine.requestPermission()
            locationManager.requestWhenInUseAuthorization()
            try? await Task.sleep(nanoseconds: 800_000_000)
            location = [.authorizedWhenInUse, .authorizedAlways].contains(locationManager.authorizationStatus)
          }
        } label: {
          HStack { Spacer(); Label("Allow", systemImage: "lock.shield").bold(); Spacer() }
        }
        if asked && !mic {
          Text("The microphone is required. Open Settings > FieldPartner and turn on Microphone.").font(.footnote).foregroundStyle(.red)
          Button("Open Settings") { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) }
        }
        Button {
          onNext()
        } label: {
          HStack { Spacer(); Text("Next").bold(); Spacer() }
        }
        .disabled(!mic)
      }
    }
    .onAppear { location = [.authorizedWhenInUse, .authorizedAlways].contains(locationManager.authorizationStatus) }
  }
}

private struct ConnectStep: View {
  let onNext: () -> Void
  @EnvironmentObject var glasses: GlassesManager
  @State private var cameraOk = false
  private let tick = Timer.publish(every: 2, on: .main, in: .common).autoconnect()

  private var registered: Bool { glasses.registration == .registered }

  var body: some View {
    Group {
      Section {
        Text("Do these in order. Each line turns into a green check when it's done.")
        StatusRow(mark: "A", title: "Meta AI app installed and your glasses paired in it", detail: "Open Meta AI, sign in with your Meta account, tap the glasses icon and follow its pairing steps.", ok: registered)
        Button {
          UIApplication.shared.open(URL(string: "itms-apps://search.itunes.apple.com/WebObjects/MZSearch.woa/wa/search?media=software&term=Meta%20AI")!)
        } label: {
          Label("Get Meta AI on the App Store", systemImage: "arrow.down.app")
        }
        StatusRow(mark: "B", title: "Developer Mode on in Meta AI", detail: "In Meta AI: Settings > App info > tap the version number 5 times > turn on Developer Mode. Needed until your company registers FieldPartner with Meta.", ok: registered)
        StatusRow(mark: "C", title: "FieldPartner connected to your glasses", detail: "Tap Connect. Meta AI opens and asks to allow FieldPartner. Tap Allow, then come back here.", ok: registered)
        Button {
          glasses.errorMessage = nil
          glasses.register()
        } label: {
          Label(registered ? "Connected. Connect again" : "Connect", systemImage: "link")
        }
        StatusRow(mark: "D", title: "Glasses camera allowed", detail: "Tap Allow camera. Meta AI asks once. Choose Allow always.", ok: cameraOk)
        Button {
          Task {
            await glasses.requestCameraPermission()
            cameraOk = await glasses.cameraPermissionGranted()
          }
        } label: {
          Label("Allow camera", systemImage: "camera")
        }
        .disabled(!registered)
        StatusRow(
          mark: "E", title: "Glasses found",
          detail: glasses.hasActiveDevice
            ? [glasses.deviceName, glasses.batteryLevel.map { "battery \($0)%" }].compactMap { $0 }.joined(separator: ", ")
            : "Take the glasses out of the case, unfold them and put them on. Bluetooth must be on.",
          ok: glasses.hasActiveDevice)
        if let err = glasses.errorMessage { Text(err).font(.footnote).foregroundStyle(.red) }
      }
      Section {
        Button {
          onNext()
        } label: {
          HStack { Spacer(); Text("Next: test the video").bold(); Spacer() }
        }
        .disabled(!(registered && cameraOk))
      }
    }
    .onReceive(tick) { _ in
      guard registered else { return }
      Task { cameraOk = await glasses.cameraPermissionGranted() }
    }
  }
}

private struct TestStep: View {
  let useGlasses: Bool
  let onNext: () -> Void
  @EnvironmentObject var glasses: GlassesManager
  @State private var running = false
  @State private var startFrames = 0

  private var received: Int { max(0, glasses.totalFrames - startFrames) }
  private var ok: Bool { running && received >= 15 }

  var body: some View {
    Group {
      if !useGlasses {
        Section {
          Text("You chose the phone camera. It turns on when you start a visit: hold the phone or use a chest mount with the back camera facing the work.")
          Button { onNext() } label: { HStack { Spacer(); Text("Next").bold(); Spacer() } }
        }
      } else {
        Section {
          Text(glasses.simulated ? "Plays the demo video through Meta's glasses simulator, the same path real glasses use." : "Put the glasses on and look at something. You should see it below within 10 seconds.")
          ZStack {
            Rectangle().fill(Color.black)
            if running, let img = glasses.previewImage {
              Image(uiImage: img).resizable().scaledToFit()
            } else {
              Text(running ? "Waiting for video..." : "Tap Start test").foregroundStyle(.white)
            }
          }
          .frame(height: 280)
          .listRowInsets(EdgeInsets())
          StatusRow(mark: nil, title: "Video received", detail: running ? "\(received) frames" : nil, ok: ok)
          if let err = glasses.errorMessage { Text(err).font(.footnote).foregroundStyle(.red) }
          HStack {
            Button {
              glasses.errorMessage = nil
              startFrames = glasses.totalFrames
              glasses.previewEnabled = true
              glasses.startStreaming()
              running = true
            } label: { Label("Start test", systemImage: "play.fill") }
              .disabled(running)
            Spacer()
            Button {
              glasses.previewEnabled = false
              glasses.stopStreaming()
              running = false
            } label: { Label("Stop", systemImage: "stop.fill") }
              .disabled(!running)
          }
          .buttonStyle(.bordered)
          if running && !ok {
            Text("No video after 20 seconds? Check: glasses unfolded and worn, Bluetooth on, step D allowed. Then Stop and Start again.").font(.footnote).foregroundStyle(.secondary)
          }
        }
        Section {
          Button { onNext() } label: { HStack { Spacer(); Text("Next").bold(); Spacer() } }
            .disabled(!ok)
        }
      }
    }
    .onDisappear {
      glasses.previewEnabled = false
      if running { glasses.stopStreaming() }
    }
  }
}
