import LiveKit
import SwiftUI

/// The screen during a visit. Designed to be glanced at, not read: the technician's
/// hands are busy and the phone is usually in a pocket.
struct VisitView: View {
  @ObservedObject var controller: VisitController
  @ObservedObject private var live: LiveSession
  @ObservedObject private var glasses: GlassesManager
  @EnvironmentObject var model: AppModel
  @EnvironmentObject var settings: AppSettings
  @State private var showConsent = false
  @State private var showReading = false
  @State private var confirmEnd = false
  @State private var preflightDone = false

  init(controller: VisitController) {
    self.controller = controller
    self._live = ObservedObject(wrappedValue: controller.live)
    self._glasses = ObservedObject(wrappedValue: controller.glasses)
  }

  var body: some View {
    Group {
      if !preflightDone {
        PreflightView(controller: controller) {
          preflightDone = true
          Task { await controller.start() }
        }
      } else {
        visitBody
      }
    }
    .sheet(isPresented: $showConsent) { ConsentView(controller: controller) }
    .sheet(isPresented: $showReading) { ReadingEntryView(controller: controller) }
    .confirmationDialog("End this visit?", isPresented: $confirmEnd, titleVisibility: .visible) {
      Button("End visit and build report", role: .destructive) {
        Task {
          await controller.end()
          model.closeVisit()
        }
      }
    } message: {
      Text("Recording stops and the report is drafted from this visit.")
    }
  }

  private var visitBody: some View {
    VStack(spacing: 0) {
      header
      ZStack(alignment: .bottom) {
        preview
        if let caption = live.caption {
          Text(caption.text)
            .font(.callout)
            .padding(10)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
            .padding()
        }
      }
      .frame(maxHeight: 300)
      if let alert = controller.safetyAlert {
        Button {
          Task { await controller.perform(.acknowledge) }
        } label: {
          HStack {
            Image(systemName: "exclamationmark.octagon.fill").font(.title)
            VStack(alignment: .leading) {
              Text("STOP").font(.headline)
              Text(alert).font(.subheadline)
            }
            Spacer()
            Text("Acknowledge").font(.caption.bold())
          }
          .foregroundStyle(.white)
          .padding()
          .background(Color.red)
        }
      }
      if controller.phase == .preVisit {
        Button { showConsent = true } label: {
          Label("Get customer consent", systemImage: "signature")
            .frame(maxWidth: .infinity).padding()
            .background(Color.orange).foregroundStyle(.black).font(.headline)
        }
      }
      controls
      List {
        if !controller.readings.isEmpty {
          Section("Readings") {
            ForEach(Array(controller.readings.enumerated()), id: \.offset) { _, r in
              LabeledContent(r.name, value: "\(r.value) \(r.unit)")
            }
          }
        }
        if let est = controller.estimateDraft {
          Section("Estimate draft (not shown to customer)") {
            ForEach(est.sorted(by: { $0.key < $1.key }), id: \.key) { k, v in LabeledContent(k.capitalized, value: v) }
          }
        }
        Section("Activity") {
          ForEach(controller.notices) { n in
            HStack(alignment: .top) {
              Image(systemName: icon(n.kind)).foregroundStyle(color(n.kind))
              VStack(alignment: .leading) {
                Text(n.title).font(.subheadline.bold())
                if !n.detail.isEmpty { Text(n.detail).font(.caption).foregroundStyle(.secondary) }
              }
            }
          }
        }
        if settings.showTranscript {
          Section("Transcript") {
            if !controller.livePartial.isEmpty { Text(controller.livePartial).italic().foregroundStyle(.secondary) }
            ForEach(Array(controller.transcript.suffix(30).reversed().enumerated()), id: \.offset) { _, line in
              Text(line).font(.footnote)
            }
          }
        }
      }
      .listStyle(.insetGrouped)
    }
    .background(Color(.systemGroupedBackground))
  }

  private var header: some View {
    HStack(spacing: 8) {
      if controller.recording && !controller.cameraPaused {
        Label("REC", systemImage: "record.circle.fill").foregroundStyle(.red).font(.caption.bold())
      } else if controller.cameraPaused {
        Label("PAUSED", systemImage: "pause.circle.fill").foregroundStyle(.yellow).font(.caption.bold())
      }
      chip(phaseText, .gray)
      chip(aiText, live.assistant == .speaking || live.assistant == .listening ? .orange : .gray)
      if live.expertPresent || controller.escalation == "joined" { chip("Expert live", .green) }
      else if controller.escalation == "requested" { chip("Paging expert", .orange) }
      else if controller.escalation == "unanswered" { chip("No expert answered", .red) }
      Spacer()
      if controller.queuedEvents > 0 { Image(systemName: "icloud.slash").foregroundStyle(.secondary) }
      Text(controller.job.title).font(.caption).lineLimit(1)
    }
    .padding(.horizontal)
    .padding(.vertical, 8)
    .background(.bar)
  }

  @ViewBuilder
  private var preview: some View {
    if let track = live.localVideoTrack, !controller.cameraPaused {
      SwiftUIVideoView(track, layoutMode: .fit)
        .background(Color.black)
    } else {
      ZStack {
        Color.black
        VStack(spacing: 8) {
          Image(systemName: controller.cameraPaused ? "video.slash" : "eyeglasses").font(.largeTitle)
          Text(previewText).multilineTextAlignment(.center)
        }
        .foregroundStyle(.white.opacity(0.8))
        .padding()
      }
    }
  }

  private var previewText: String {
    if controller.cameraPaused { return "Camera paused" }
    switch glasses.link {
    case .streaming: return "Connecting video..."
    case .waitingForGlasses: return "Put the glasses on and open the hinges"
    case .notRegistered: return settings.usePhoneCamera ? "Phone camera" : "Glasses not connected. Settings > Connect glasses."
    case .noGlasses: return "Glasses not found. Turn them on near the phone."
    case .error(let m): return m
    default: return "Connecting to glasses..."
    }
  }

  private var controls: some View {
    HStack(spacing: 10) {
      bigButton("Ask", "waveform", .orange) { controller.talkButton() }
      bigButton(controller.cameraPaused ? "Camera on" : "Pause cam", controller.cameraPaused ? "video" : "video.slash", .gray) {
        Task { await controller.setCameraPaused(!controller.cameraPaused) }
      }
      bigButton("Expert", "person.wave.2", .green) { Task { await controller.escalate(reason: "Technician asked for help") } }
      bigButton("Reading", "gauge.with.dots.needle.50percent", .blue) { showReading = true }
      bigButton("End", "stop.circle", .red) { confirmEnd = true }
    }
    .padding(.horizontal)
    .padding(.vertical, 8)
  }

  private func bigButton(_ title: String, _ icon: String, _ color: Color, action: @escaping () -> Void) -> some View {
    Button(action: action) {
      VStack(spacing: 4) {
        Image(systemName: icon).font(.title2)
        Text(title).font(.caption2.bold())
      }
      .frame(maxWidth: .infinity, minHeight: 58)
      .background(color.opacity(0.18), in: RoundedRectangle(cornerRadius: 12))
    }
    .buttonStyle(.plain)
  }

  private func chip(_ text: String, _ color: Color) -> some View {
    Text(text).font(.caption2.bold()).padding(.horizontal, 8).padding(.vertical, 4)
      .background(color.opacity(0.2), in: Capsule())
  }

  private var phaseText: String {
    switch controller.phase {
    case .preparing: return "Starting"
    case .preVisit: return "Needs consent"
    case .active: return "Video on"
    case .noCamera: return controller.consentChoice == "no_recording" ? "No recording" : "Audio only"
    case .ending: return "Ending"
    case .ended: return "Ended"
    case .declined: return "Declined"
    }
  }

  private var aiText: String {
    switch live.state {
    case .connected:
      switch live.assistant {
      case .listening: return "AI listening"
      case .thinking: return "AI thinking"
      case .speaking: return "AI speaking"
      case .muted: return "AI muted"
      default: return "Say \"\(controller.wakePhrase)\""
      }
    case .connecting: return "AI connecting"
    case .reconnecting: return "AI reconnecting"
    case .failed: return "AI offline"
    case .idle: return "AI offline"
    }
  }

  private func icon(_ k: VisitController.Notice.Kind) -> String {
    switch k {
    case .info: return "info.circle"
    case .hint: return "lightbulb"
    case .safety: return "exclamationmark.octagon.fill"
    case .tool: return "checkmark.circle"
    case .escalation: return "person.wave.2"
    case .error: return "wifi.exclamationmark"
    }
  }

  private func color(_ k: VisitController.Notice.Kind) -> Color {
    switch k {
    case .safety: return .red
    case .hint: return .yellow
    case .error: return .orange
    case .escalation: return .green
    default: return .accentColor
    }
  }
}

struct PreflightView: View {
  @ObservedObject var controller: VisitController
  @EnvironmentObject var settings: AppSettings
  let onStart: () -> Void
  @Environment(\.dismiss) var dismiss
  @EnvironmentObject var model: AppModel

  var body: some View {
    NavigationStack {
      List {
        Section {
          Text(controller.job.title).font(.headline)
          Text(controller.job.complaint ?? "").foregroundStyle(.secondary)
        }
        Section("Checks") {
          if let p = controller.preflight {
            check("Glasses connected", p.glasses, settings.usePhoneCamera ? "Using phone camera" : (p.glassesBattery.map { "Battery \($0)%" } ?? ""))
            check("Phone battery above 30%", p.phoneBattery > 30, "\(p.phoneBattery)%")
            check("Storage above 10 GB", p.freeStorageGB > 10, String(format: "%.0f GB free", p.freeStorageGB))
            check("Server reachable", p.server, p.server ? "" : "Check signal. You can still start; data syncs later.")
            check("Speech recognition allowed", p.speech, p.speech ? "" : "Settings > Privacy > Speech Recognition")
          } else {
            HStack { ProgressView(); Text("Checking...") }
          }
        }
        Section {
          Button {
            onStart()
          } label: {
            HStack { Spacer(); Label("Start recording and go", systemImage: "record.circle").font(.headline); Spacer() }
          }
          .disabled(controller.preflight == nil)
          Button("Cancel", role: .cancel) { model.visit = nil }
        } footer: {
          Text("Wake phrase: \"\(controller.wakePhrase)\". Press the clip button once to talk, twice to stop the AI, three times to pause the camera.")
        }
      }
      .navigationTitle("Before the door")
      .task { await controller.checkPreflight() }
    }
  }

  private func check(_ title: String, _ ok: Bool, _ detail: String) -> some View {
    HStack {
      Image(systemName: ok ? "checkmark.circle.fill" : "xmark.circle.fill").foregroundStyle(ok ? .green : .red)
      VStack(alignment: .leading) {
        Text(title)
        if !detail.isEmpty { Text(detail).font(.caption).foregroundStyle(.secondary) }
      }
    }
  }
}

struct ReadingEntryView: View {
  @ObservedObject var controller: VisitController
  @Environment(\.dismiss) var dismiss
  @State private var name = ""
  @State private var value = ""
  @State private var unit = ""
  @State private var note = ""

  var body: some View {
    NavigationStack {
      Form {
        Section("Reading") {
          TextField("What (e.g. Ambient CO)", text: $name)
          TextField("Value", text: $value).keyboardType(.decimalPad)
          TextField("Unit (e.g. ppm, V, psi)", text: $unit)
        }
        Section("Or a note") {
          TextField("Note for the report", text: $note, axis: .vertical).lineLimit(2...5)
        }
      }
      .navigationTitle("Add to visit")
      .toolbar {
        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
        ToolbarItem(placement: .confirmationAction) {
          Button("Save") {
            if !name.isEmpty && !value.isEmpty { controller.addReading(name: name, value: value, unit: unit) }
            if !note.isEmpty { controller.addNote(note) }
            dismiss()
          }
        }
      }
    }
  }
}
