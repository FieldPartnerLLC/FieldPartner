import MWDATCore
import SwiftUI

@main
struct FieldPartnerApp: App {
  @StateObject private var model: AppModel

  init() {
    do {
      try Wearables.configure()
    } catch {
      NSLog("[FieldPartner] Wearables SDK configure failed: %@", String(describing: error))
    }
    _model = StateObject(wrappedValue: AppModel())
  }

  var body: some Scene {
    WindowGroup {
      RootView()
        .environmentObject(model)
        .environmentObject(model.glasses)
        .environmentObject(AppSettings.shared)
        .onOpenURL { url in
          Task { await GlassesManager.handle(url: url) }
        }
        .tint(.orange)
    }
  }
}

struct RootView: View {
  @EnvironmentObject var model: AppModel

  var body: some View {
    Group {
      if model.signedIn {
        JobsView()
      } else {
        LoginView()
      }
    }
    .alert("Something went wrong", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
      Button("OK", role: .cancel) {}
    } message: {
      Text(model.errorMessage ?? "")
    }
    .fullScreenCover(item: Binding(get: { model.visit.map(VisitBox.init) }, set: { if $0 == nil { model.closeVisit() } })) { box in
      VisitView(controller: box.controller)
    }
    .sheet(item: Binding(get: { model.reportVisitId.map(IdBox.init) }, set: { model.reportVisitId = $0?.id })) { box in
      ReportReviewView(visitId: box.id)
    }
  }
}

struct VisitBox: Identifiable {
  let controller: VisitController
  var id: String { controller.visit.id }
}

struct IdBox: Identifiable { let id: String }
