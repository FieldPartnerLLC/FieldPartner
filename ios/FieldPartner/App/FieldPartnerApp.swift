import MWDATCore
import SwiftUI

@main
struct FieldPartnerApp: App {
  @StateObject private var model: AppModel

  init() {
    do {
      try Wearables.configure()
      GlassesManager.sdkReady = true
      NSLog("[FieldPartner] Wearables SDK configured")
    } catch {
      GlassesManager.sdkError = String(describing: error)
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
        .tint(Color(red: 0.078, green: 0.227, blue: 0.353))  // FieldPartner navy #143A5A, same as Android and the portal
        #if DEBUG && targetEnvironment(simulator)
        // The iOS Simulator has no glasses and no camera: play the demo video as the glasses feed.
        .task {
          model.glasses.enableSimulatedGlasses(demoVideo: true)
          NSLog("[FieldPartner] Simulated glasses: %@",
                model.glasses.simulated ? "paired" : "not paired (\(model.glasses.errorMessage ?? "Wearables SDK not configured"))")
        }
        #endif
    }
  }
}

struct RootView: View {
  @EnvironmentObject var model: AppModel

  var body: some View {
    Group {
      if model.signedIn && model.needsSetup {
        SetupView()
      } else if model.signedIn {
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
