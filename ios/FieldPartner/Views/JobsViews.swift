import SwiftUI

struct LoginView: View {
  @EnvironmentObject var model: AppModel
  @EnvironmentObject var settings: AppSettings
  @State private var server = AppSettings.shared.apiBaseURL
  @State private var email = ""
  @State private var password = ""
  @State private var busy = false

  var body: some View {
    NavigationStack {
      Form {
        Section {
          VStack(alignment: .leading, spacing: 6) {
            Text("FieldPartner").font(.largeTitle.bold())
            Text("Your AI partner on every job.").foregroundStyle(.secondary)
          }
          .padding(.vertical, 8)
        }
        Section("Company server") {
          TextField("https://api.yourcompany.com", text: $server)
            .textInputAutocapitalization(.never)
            .keyboardType(.URL)
            .autocorrectionDisabled()
        }
        Section("Sign in") {
          TextField("Email", text: $email)
            .textInputAutocapitalization(.never)
            .keyboardType(.emailAddress)
            .textContentType(.username)
            .autocorrectionDisabled()
          SecureField("Password", text: $password)
            .textContentType(.password)
        }
        Section {
          Button {
            busy = true
            Task {
              await model.login(server: server, email: email, password: password)
              busy = false
            }
          } label: {
            HStack {
              Spacer()
              if busy { ProgressView() } else { Text("Sign in").bold() }
              Spacer()
            }
          }
          .disabled(busy || email.isEmpty || password.isEmpty || server.isEmpty)
        }
      }
    }
  }
}

struct JobsView: View {
  @EnvironmentObject var model: AppModel
  @EnvironmentObject var glasses: GlassesManager
  @State private var showSettings = false
  @State private var showWalkIn = false

  var body: some View {
    NavigationStack {
      List {
        Section {
          GlassesStatusRow()
        }
        Section("Today") {
          if model.jobs.isEmpty && !model.loadingJobs {
            Text("No jobs today. Tap + for a walk-in job.").foregroundStyle(.secondary)
          }
          ForEach(model.jobs) { job in
            NavigationLink(value: job) { JobRow(job: job, tradeName: model.trade(job.trade)?.name ?? job.trade) }
          }
        }
      }
      .navigationTitle(model.company?.name ?? "Jobs")
      .navigationDestination(for: Job.self) { JobDetailView(job: $0) }
      .refreshable { await model.loadJobs() }
      .toolbar {
        ToolbarItem(placement: .topBarLeading) {
          Button { showSettings = true } label: { Image(systemName: "gearshape") }
        }
        ToolbarItem(placement: .topBarTrailing) {
          Button { showWalkIn = true } label: { Image(systemName: "plus") }
        }
      }
      .sheet(isPresented: $showSettings) { SettingsView() }
      .sheet(isPresented: $showWalkIn) { WalkInJobView() }
    }
  }
}

extension Job: Hashable {
  func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

struct JobRow: View {
  let job: Job
  let tradeName: String

  var body: some View {
    VStack(alignment: .leading, spacing: 4) {
      HStack {
        Text(job.title).font(.headline)
        Spacer()
        if let d = job.scheduledDate { Text(d, style: .time).font(.subheadline).foregroundStyle(.secondary) }
      }
      Text("\(tradeName) · \(job.customer_name ?? "No customer") · \(job.address ?? "")")
        .font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
      if job.status == "in_progress" {
        Label("In progress", systemImage: "record.circle").font(.caption).foregroundStyle(.orange)
      } else if job.status == "completed" {
        Label("Completed", systemImage: "checkmark.circle").font(.caption).foregroundStyle(.green)
      }
    }
    .padding(.vertical, 4)
  }
}

struct GlassesStatusRow: View {
  @EnvironmentObject var glasses: GlassesManager
  @EnvironmentObject var settings: AppSettings

  var body: some View {
    HStack(spacing: 12) {
      Image(systemName: "eyeglasses").font(.title2)
      VStack(alignment: .leading) {
        Text(title).font(.headline)
        Text(detail).font(.caption).foregroundStyle(.secondary)
      }
      Spacer()
      if glasses.registration != .registered && !settings.usePhoneCamera {
        Button("Connect") { glasses.register() }.buttonStyle(.borderedProminent)
      }
    }
  }

  private var title: String {
    if settings.usePhoneCamera { return "Using phone camera" }
    switch glasses.registration {
    case .registered: return glasses.hasActiveDevice ? (glasses.deviceName ?? "Glasses ready") : "Glasses not found"
    case .registering: return "Connecting in Meta AI..."
    default: return "Glasses not connected"
    }
  }

  private var detail: String {
    if settings.usePhoneCamera { return "Change in Settings to use glasses." }
    if let b = glasses.batteryLevel { return "Battery \(b)%" }
    if glasses.registration != .registered { return "Tap Connect. The Meta AI app will open to approve." }
    return "Put the glasses on and open the hinges."
  }
}

struct JobDetailView: View {
  @EnvironmentObject var model: AppModel
  let job: Job
  @State private var starting = false

  var body: some View {
    List {
      Section("Problem") {
        Text(job.complaint ?? "No description").font(.body)
      }
      Section("Customer") {
        LabeledContent("Name", value: job.customer_name ?? "-")
        if let phone = job.customer_phone, let url = URL(string: "tel:\(phone.filter { $0.isNumber || $0 == "+" })") {
          Link(destination: url) { LabeledContent("Phone", value: phone) }
        }
        if let address = job.address {
          let q = address.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
          if let url = URL(string: "http://maps.apple.com/?q=\(q)") {
            Link(destination: url) { LabeledContent("Address", value: address) }
          }
        }
        if let notes = job.access_notes, !notes.isEmpty { LabeledContent("Access", value: notes) }
        if job.recording_pref == "no_recording" {
          Label("Customer asked for no recording", systemImage: "video.slash").foregroundStyle(.red)
        }
      }
      if let trade = model.trade(job.trade) {
        Section("\(trade.name) safety") {
          ForEach(trade.profile.preWorkReadings ?? [], id: \.self) { p in
            Label(p.label, systemImage: "gauge.with.dots.needle.33percent")
          }
          ForEach(trade.profile.stopRules ?? [], id: \.self) { r in
            Label(r, systemImage: "exclamationmark.octagon").font(.footnote)
          }
        }
      }
      Section {
        Button {
          starting = true
          Task {
            _ = await model.openVisit(for: job)
            starting = false
          }
        } label: {
          HStack {
            Spacer()
            if starting { ProgressView() } else { Label(job.status == "in_progress" ? "Resume visit" : "Start visit", systemImage: "play.circle.fill").font(.headline) }
            Spacer()
          }
        }
        .disabled(starting)
      } footer: {
        Text("Start in the truck before you go to the door. Recording starts right away.")
      }
    }
    .navigationTitle(job.title)
    .navigationBarTitleDisplayMode(.inline)
  }
}

struct WalkInJobView: View {
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) var dismiss
  @State private var trade = "general"
  @State private var title = ""
  @State private var complaint = ""
  @State private var address = ""
  @State private var customer = ""
  @State private var phone = ""
  @State private var busy = false

  var body: some View {
    NavigationStack {
      Form {
        Picker("Trade", selection: $trade) {
          ForEach(model.trades) { Text($0.name).tag($0.id) }
        }
        TextField("What's the job? (e.g. No heat)", text: $title)
        TextField("Customer's description", text: $complaint, axis: .vertical).lineLimit(2...5)
        TextField("Address", text: $address)
        TextField("Customer name", text: $customer)
        TextField("Customer phone", text: $phone).keyboardType(.phonePad)
      }
      .navigationTitle("Walk-in job")
      .toolbar {
        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
        ToolbarItem(placement: .confirmationAction) {
          Button("Create") {
            busy = true
            Task {
              if await model.createWalkIn(trade: trade, title: title, complaint: complaint, address: address, customer: customer, phone: phone) != nil {
                dismiss()
              }
              busy = false
            }
          }
          .disabled(title.isEmpty || busy)
        }
      }
    }
  }
}

struct SettingsView: View {
  @EnvironmentObject var model: AppModel
  @EnvironmentObject var glasses: GlassesManager
  @EnvironmentObject var settings: AppSettings
  @Environment(\.dismiss) var dismiss
  @State private var pendingUploads = 0

  var body: some View {
    NavigationStack {
      Form {
        Section("Account") {
          LabeledContent("Name", value: model.user?.name ?? "-")
          LabeledContent("Company", value: model.company?.name ?? "-")
          LabeledContent("Server", value: settings.apiBaseURL)
          Button("Sign out", role: .destructive) {
            model.logout()
            dismiss()
          }
        }
        Section("Glasses") {
          Toggle("Use phone camera instead of glasses", isOn: $settings.usePhoneCamera)
          if glasses.registration == .registered {
            Button("Disconnect glasses from this app", role: .destructive) { glasses.unregister() }
          } else {
            Button("Connect glasses") { glasses.register() }
          }
          #if DEBUG
          Button("Use simulated glasses (testing)") { glasses.enableSimulatedGlasses() }
          #endif
        }
        Section("AI partner") {
          LabeledContent("Wake phrase", value: settings.wakePhraseOverride.isEmpty ? (model.company?.wake_phrase ?? "hey dispatch") : settings.wakePhraseOverride)
          TextField("Override wake phrase (optional)", text: $settings.wakePhraseOverride)
            .textInputAutocapitalization(.never)
          VStack(alignment: .leading) {
            Text("Announcement volume")
            Slider(value: $settings.aiVoiceVolume, in: 0.1...1)
          }
          Picker("Transcript language", selection: $settings.language) {
            Text("English").tag("en-US")
            Text("Spanish").tag("es-US")
          }
          Toggle("Show live transcript", isOn: $settings.showTranscript)
        }
        Section("Uploads") {
          Toggle("Upload video on cellular", isOn: $settings.uploadVideoOnCellular)
          LabeledContent("Waiting to upload", value: "\(pendingUploads) files")
          Button("Upload now") { Task { await UploadManager.shared.processQueue(); pendingUploads = await UploadManager.shared.pendingCount } }
        }
      }
      .navigationTitle("Settings")
      .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
      .task { pendingUploads = await UploadManager.shared.pendingCount }
    }
  }
}
