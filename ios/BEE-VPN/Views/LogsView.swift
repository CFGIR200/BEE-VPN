import SwiftUI
import Combine

/// Logs — Android's openLogsScreen: tabs over the log streams, COPY/SHARE
/// tools, pause and clear. iOS shows the app log and the tunnel extension
/// log from the shared container (Android splits All / App / Core; here the
/// core-side stream is the tunnel extension).
struct LogsView: View {
    @Environment(\.beePalette) private var palette
    @Environment(\.dismiss) private var dismiss

    enum Stream: String, CaseIterable {
        case all, app, tunnel
        var file: String? {
            switch self {
            case .all: return nil
            case .app: return SharedStore.appLog
            case .tunnel: return SharedStore.tunnelLog
            }
        }
        var label: String {
            switch self {
            case .all: return BeeStrings.t("Tab: All")
            case .app: return BeeStrings.t("Tab: App")
            case .tunnel: return BeeStrings.t("Tab: Tunnel")
            }
        }
    }

    @State private var stream: Stream = .all
    @State private var content = ""
    @State private var paused = false

    let timer = Timer.publish(every: 2, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            ZStack {
                palette.canvas.ignoresSafeArea()
                VStack(spacing: 0) {
                    Picker("", selection: $stream) {
                        ForEach(Stream.allCases, id: \.self) { s in
                            Text(s.label).tag(s)
                        }
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)

                    ScrollViewReader { proxy in
                        ScrollView {
                            Text(content.isEmpty ? BeeStrings.t("Tunnel and VPN events") : content)
                                .font(.system(size: 10, design: .monospaced))
                                .foregroundColor(content.isEmpty ? palette.muted : palette.ink)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(12)
                                .textSelection(.enabled)
                            Color.clear
                                .frame(height: 1)
                                .id("bottom")
                        }
                        .onChange(of: content) { _ in
                            withAnimation { proxy.scrollTo("bottom", anchor: .bottom) }
                        }
                    }
                }
            }
            .navigationTitle(BeeStrings.t("Log"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button {
                        UIPasteboard.general.string = content
                    } label: {
                        Text(BeeStrings.t("COPY"))
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(palette.primary)
                    }
                    if let url = shareURL {
                        ShareLink(item: url) {
                            Text(BeeStrings.t("SHARE"))
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(palette.primary)
                        }
                    }
                    Button {
                        paused.toggle()
                    } label: {
                        Image(systemName: paused ? "play.circle" : "pause.circle")
                            .foregroundColor(palette.muted)
                    }
                    Button {
                        SharedStore.clear([SharedStore.appLog, SharedStore.tunnelLog])
                        content = ""
                        BeeLog.write("logs cleared")
                    } label: {
                        Image(systemName: "trash")
                            .foregroundColor(palette.danger)
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(BeeStrings.t("Done")) { dismiss() }
                        .foregroundColor(palette.primary)
                }
            }
            .onReceive(timer) { _ in
                if !paused { reload() }
            }
            .onAppear { reload() }
            .onChange(of: stream) { _ in reload() }
        }
    }

    private func reload() {
        if let file = stream.file {
            content = SharedStore.read(file)
        } else {
            let app = SharedStore.read(SharedStore.appLog)
            let tunnelLog = SharedStore.read(SharedStore.tunnelLog)
            content = ([app, tunnelLog].filter { !$0.isEmpty }).joined(separator: "\n")
        }
    }

    private var shareURL: URL? {
        guard let dir = SharedStore.containerURL else { return nil }
        let combined = dir.appendingPathComponent("bee-logs.txt")
        try? content.data(using: .utf8)?.write(to: combined)
        return combined
    }
}
