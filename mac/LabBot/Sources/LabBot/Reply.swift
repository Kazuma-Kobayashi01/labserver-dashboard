import AppKit
import SwiftUI

/// A reply cut into what is read and what is looked at, with each command window where it happened.
enum ReplyPiece: Hashable {
    case text(String)
    case image(src: String, alt: String)   // src: "media:<path on the server>" or an http(s)/data URL
    case tool(ToolRun)
    case tools([ToolRun])                  // several commands in a row, as one line (the minimal display)
}

enum ReplyParts {
    private static let media = try! NSRegularExpression(pattern: #"MEDIA:(\S+)"#)
    private static let mdImage = try! NSRegularExpression(pattern: #"!\[([^\]]*)\]\(\s*<?((?:https?://|data:image/)[^)\s>]+)>?(?:\s+"[^"]*")?\s*\)"#)
    private static let trailing = CharacterSet(charactersIn: ".,;:!?)]\"'。，；：！？）」`")

    /// Pulls the pictures out of a reply (`MEDIA:/path` and `![alt](url)`), leaving code blocks alone.
    static func split(_ content: String) -> [ReplyPiece] {
        guard content.contains("MEDIA:") || content.contains("![") else { return [.text(content)] }
        var out: [ReplyPiece] = []
        var text = ""
        func flush() {
            let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if !t.unicodeScalars.allSatisfy({ trailing.contains($0) || CharacterSet.whitespacesAndNewlines.contains($0) }) { out.append(.text(t)) }
            text = ""
        }
        var inFence = false
        for line in content.components(separatedBy: "\n") {
            if line.trimmingCharacters(in: .whitespaces).hasPrefix("```") || line.trimmingCharacters(in: .whitespaces).hasPrefix("~~~") {
                inFence.toggle(); text += line + "\n"; continue
            }
            if inFence { text += line + "\n"; continue }
            var rest = line
            while true {
                let ns = rest as NSString
                let full = NSRange(location: 0, length: ns.length)
                let m = media.firstMatch(in: rest, range: full), i = mdImage.firstMatch(in: rest, range: full)
                guard let next = [m, i].compactMap({ $0 }).min(by: { $0.range.location < $1.range.location }) else { break }
                text += ns.substring(to: next.range.location)
                let after = ns.substring(from: next.range.location + next.range.length)
                if next === m {
                    let raw = ns.substring(with: next.range(at: 1))
                    let path = raw.trimmingCharacters(in: trailing)
                    // shown once its path is complete (it has an extension), not while it streams in
                    if path.hasPrefix("/"), (path.split(separator: "/").last ?? "").contains(".") {
                        flush(); out.append(.image(src: "media:" + path, alt: ""))
                        // the full stop or comma left behind a picture at the end of a line is not worth keeping
                        let left = String(raw.dropFirst(path.count))
                        rest = (after.trimmingCharacters(in: .whitespaces).isEmpty ? "" : left) + after
                    } else {
                        text += ns.substring(with: next.range); rest = after
                    }
                } else {
                    flush(); out.append(.image(src: ns.substring(with: next.range(at: 2)), alt: ns.substring(with: next.range(at: 1))))
                    rest = after
                }
            }
            text += rest + "\n"
        }
        flush()
        return out
    }

    /// The reply's text cut where its tools ran, with each tool between the words it came between.
    static func pieces(_ content: String, _ tools: [ToolRun], group: Bool) -> [ReplyPiece] {
        var out: [ReplyPiece] = []
        var from = content.startIndex
        for t in tools.sorted(by: { $0.at < $1.at }) {
            let to = content.index(content.startIndex, offsetBy: min(max(t.at, content.distance(from: content.startIndex, to: from)), content.count))
            if to > from { out += split(String(content[from..<to])).filter { if case .text(let s) = $0 { return !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }; return true } }
            out.append(.tool(t))
            from = to
        }
        if from < content.endIndex { out += split(String(content[from...])) }
        guard group else { return out }
        var grouped: [ReplyPiece] = [], run: [ToolRun] = []
        for p in out {
            if case .tool(let t) = p { run.append(t); continue }
            if !run.isEmpty { grouped.append(.tools(run)); run = [] }
            grouped.append(p)
        }
        if !run.isEmpty { grouped.append(.tools(run)) }
        return grouped
    }

    /// The reply without its pictures (for copying).
    static func plain(_ content: String) -> String {
        split(content).compactMap { if case .text(let s) = $0 { return s }; return nil }.joined(separator: "\n\n")
    }
}

/// A picture in a reply: fetched from the bot-manager (pictures the bot made on the server) or from the web.
struct ReplyImage: View {
    var src: String
    var profile: String
    @State private var image: NSImage?
    @State private var failed = false

    var body: some View {
        Group {
            if let image {
                Image(nsImage: image).resizable().aspectRatio(contentMode: .fit)
                    .frame(maxWidth: min(image.size.width, 480), maxHeight: 420)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .contextMenu {
                        Button(L.t("copy")) { NSPasteboard.general.clearContents(); NSPasteboard.general.writeObjects([image]) }
                    }
            } else {
                RoundedRectangle(cornerRadius: 14).fill(Color.primary.opacity(0.06)).frame(width: 260, height: 180)
                    .overlay { if failed { Image(systemName: "photo.badge.exclamationmark").foregroundStyle(.secondary) } else { ProgressView().controlSize(.small) } }
            }
        }
        .task(id: src) { await load() }
    }

    private func load() async {
        do {
            let data: Data
            if src.hasPrefix("media:") {
                data = try await Manager.current.media(profile: profile, path: String(src.dropFirst(6)))
            } else if src.hasPrefix("data:"), let comma = src.firstIndex(of: ",") {
                data = Data(base64Encoded: String(src[src.index(after: comma)...])) ?? Data()
            } else if let url = URL(string: src) {
                data = try await URLSession.shared.data(from: url).0
            } else { throw URLError(.badURL) }
            image = NSImage(data: data)
            failed = image == nil
        } catch { failed = true }
    }
}
