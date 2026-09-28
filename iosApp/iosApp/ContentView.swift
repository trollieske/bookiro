import SwiftUI
import UIKit
import Shared

/// Hosts the Compose Multiplatform UI (shared Kotlin) inside SwiftUI.
/// Works on both iPhone and iPad — the Compose UI adapts to the window size.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
    }
}