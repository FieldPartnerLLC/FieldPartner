import SwiftUI

/// Shown to the customer at the door. Large type, plain language, English or Spanish.
struct ConsentView: View {
  @ObservedObject var controller: VisitController
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) var dismiss
  @State private var language = "en"
  @State private var choice = "video_audio"
  @State private var privateAreas = false
  @State private var name = ""
  @State private var role = "owner"
  @State private var lines: [[CGPoint]] = []
  @State private var busy = false
  @State private var confirmDecline = false

  private var company: String { model.company?.name ?? "Our company" }

  var body: some View {
    NavigationStack {
      Form {
        Picker("Language", selection: $language) {
          Text("English").tag("en")
          Text("Español").tag("es")
        }
        .pickerStyle(.segmented)
        Section(language == "es" ? "Autorización de servicio" : "Service authorization") {
          Text(ConsentText.serviceAuthorization(company: company, fee: model.company?.diagnostic_fee_cents, language: language))
            .font(.body)
        }
        Section(language == "es" ? "Grabación" : "Recording") {
          Text(ConsentText.recording(company: company, language: language)).font(.body)
          Picker(language == "es" ? "Elija" : "Choose", selection: $choice) {
            ForEach(ConsentText.choices, id: \.id) { c in Text(language == "es" ? c.es : c.en).tag(c.id) }
          }
          .pickerStyle(.inline)
          if choice == "video_audio" {
            Toggle(language == "es" ? "OK grabar en baños o dormitorios si el equipo está ahí" : "OK to record in bathrooms or bedrooms if equipment is there", isOn: $privateAreas)
          }
        }
        Section(language == "es" ? "Firma" : "Signature") {
          TextField(language == "es" ? "Nombre completo" : "Full name", text: $name)
            .textContentType(.name)
          Picker(language == "es" ? "Yo soy" : "I am the", selection: $role) {
            Text(language == "es" ? "Dueño" : "Owner").tag("owner")
            Text(language == "es" ? "Inquilino" : "Tenant").tag("tenant")
            Text(language == "es" ? "Autorizado por el dueño" : "Authorized by owner").tag("authorized")
          }
          SignaturePad(lines: $lines).frame(height: 160)
          Button(language == "es" ? "Borrar firma" : "Clear signature") { lines = [] }.font(.caption)
        }
        Section {
          Button {
            busy = true
            Task {
              let ok = await controller.submitConsent(
                choice: choice, signerName: name, signerRole: role, privateAreasOk: privateAreas,
                signature: SignaturePad.render(lines: lines), language: language)
              busy = false
              if ok { dismiss() }
            }
          } label: {
            HStack { Spacer(); if busy { ProgressView() } else { Text(language == "es" ? "Firmar y aceptar" : "Sign and agree").bold() }; Spacer() }
          }
          .disabled(busy || name.trimmingCharacters(in: .whitespaces).isEmpty || lines.isEmpty)
          Button(language == "es" ? "El cliente no acepta el servicio" : "Customer declines service", role: .destructive) { confirmDecline = true }
        }
        if let err = controller.errorBanner {
          Text(err).foregroundStyle(.red).font(.footnote)
        }
      }
      .navigationTitle(language == "es" ? "Consentimiento" : "Consent")
      .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
      .confirmationDialog("Customer declines service?", isPresented: $confirmDecline, titleVisibility: .visible) {
        Button("Yes, end the visit", role: .destructive) {
          Task {
            _ = await controller.submitConsent(choice: "declined_service", signerName: name, signerRole: role, privateAreasOk: false, signature: nil, language: language)
            dismiss()
          }
        }
      } message: {
        Text("Don't enter. Tell them they can call the office any time.")
      }
    }
  }
}

struct SignaturePad: View {
  @Binding var lines: [[CGPoint]]

  var body: some View {
    Canvas { ctx, _ in
      for line in lines where line.count > 1 {
        var path = Path()
        path.addLines(line)
        ctx.stroke(path, with: .color(.primary), lineWidth: 2.5)
      }
    }
    .background(RoundedRectangle(cornerRadius: 8).stroke(.secondary.opacity(0.4)))
    .contentShape(Rectangle())
    .gesture(
      DragGesture(minimumDistance: 0)
        .onChanged { v in
          if v.translation == .zero { lines.append([v.location]) } else if !lines.isEmpty { lines[lines.count - 1].append(v.location) }
        }
    )
  }

  @MainActor
  static func render(lines: [[CGPoint]]) -> UIImage? {
    guard !lines.isEmpty else { return nil }
    let size = CGSize(width: 600, height: 200)
    let maxX = lines.flatMap { $0 }.map(\.x).max() ?? 1
    let maxY = lines.flatMap { $0 }.map(\.y).max() ?? 1
    let scale = min(size.width / max(maxX, 1), size.height / max(maxY, 1), 2)
    let renderer = UIGraphicsImageRenderer(size: size)
    return renderer.image { ctx in
      UIColor.white.setFill()
      ctx.fill(CGRect(origin: .zero, size: size))
      UIColor.black.setStroke()
      for line in lines where line.count > 1 {
        let path = UIBezierPath()
        path.lineWidth = 3
        path.move(to: CGPoint(x: line[0].x * scale, y: line[0].y * scale))
        for p in line.dropFirst() { path.addLine(to: CGPoint(x: p.x * scale, y: p.y * scale)) }
        path.stroke()
      }
    }
  }
}

/// After the visit: the AI drafts, the technician corrects and approves. Nothing reaches the customer before approval.
struct ReportReviewView: View {
  let visitId: String
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) var dismiss
  @State private var report: ServiceReport?
  @State private var status = "generating"
  @State private var sendSms = true
  @State private var sendEmail = true
  @State private var busy = false
  @State private var result: String?

  var body: some View {
    NavigationStack {
      Form {
        if let binding = Binding($report) {
          Section("Summary") { TextField("Summary", text: binding.summary, axis: .vertical).lineLimit(2...6) }
          Section("Customer complaint") { TextField("", text: binding.customerComplaint, axis: .vertical) }
          Section("Equipment") { TextField("", text: binding.equipment, axis: .vertical) }
          listSection("Findings", binding.findings)
          listSection("Work performed", binding.workPerformed)
          listSection("Parts used", binding.partsUsed)
          listSection("Readings", binding.readings)
          listSection("Follow-up", binding.followUp)
          Section("Send to customer") {
            Toggle("Text message", isOn: $sendSms)
            Toggle("Email with PDF", isOn: $sendEmail)
            Button {
              busy = true
              Task {
                do {
                  let r = try await model.api.approveReport(visitId: visitId, report: report!, sendSms: sendSms, sendEmail: sendEmail)
                  result = r.delivered.isEmpty ? "Approved. Not sent (no customer phone or email, or messaging not set up). Link: \(r.url)" : "Sent by \(r.delivered.joined(separator: " and "))."
                } catch {
                  result = error.localizedDescription
                }
                busy = false
              }
            } label: {
              HStack { Spacer(); if busy { ProgressView() } else { Text("Approve and send").bold() }; Spacer() }
            }
            .disabled(busy)
            if let result { Text(result).font(.footnote) }
          }
        } else {
          Section {
            HStack { ProgressView(); Text(status == "failed" ? "Drafting failed. Pull to retry." : "The AI is drafting the report...") }
          }
        }
      }
      .navigationTitle("Service report")
      .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
      .task { await poll() }
      .refreshable { await poll() }
    }
  }

  private func listSection(_ title: String, _ items: Binding<[String]>) -> some View {
    Section(title) {
      TextField("One per line", text: Binding(get: { items.wrappedValue.joined(separator: "\n") }, set: { items.wrappedValue = $0.split(separator: "\n").map(String.init) }), axis: .vertical)
        .lineLimit(1...12)
    }
  }

  private func poll() async {
    for _ in 0..<40 {
      if let r = try? await model.api.report(visitId: visitId) {
        status = r.status
        if let rep = r.report, report == nil { report = rep }
        if report != nil || r.status == "failed" { return }
      }
      try? await Task.sleep(nanoseconds: 1_500_000_000)
    }
  }
}
