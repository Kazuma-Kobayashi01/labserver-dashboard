import AppKit
import SwiftUI

/// The block structure of a (possibly still streaming) Markdown reply.
indirect enum MDBlock: Hashable {
    case para(String)
    case heading(Int, String)
    case item(depth: Int, marker: String, text: String, task: Bool?)
    case code(lang: String, text: String)
    case quote([MDBlock])
    case table(header: [String], rows: [[String]])
    case rule
}

enum MarkdownParser {
    private static func re(_ p: String) -> NSRegularExpression { try! NSRegularExpression(pattern: p) }
    private static let fenceOpen = re(#"^\s*(`{3,}|~{3,})\s*([^\s`]*).*$"#)
    private static let heading = re(#"^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$"#)
    private static let bullet = re(#"^(\s*)[-*+]\s+(.*)$"#)
    private static let numbered = re(#"^(\s*)(\d+[.)])\s+(.*)$"#)
    private static let rule = re(#"^\s{0,3}([-*_])(\s*\1){2,}\s*$"#)
    private static let tableSep = re(#"^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$"#)
    private static let taskBox = re(#"^\[([ xX])\]\s+(.*)$"#)

    private static func match(_ r: NSRegularExpression, _ s: String) -> [String]? {
        let ns = s as NSString
        guard let m = r.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)), m.range.location == 0, m.range.length == ns.length else { return nil }
        return (0..<m.numberOfRanges).map { m.range(at: $0).location == NSNotFound ? "" : ns.substring(with: m.range(at: $0)) }
    }

    private static func indent(_ s: String) -> Int { s.prefix { $0 == " " || $0 == "\t" }.reduce(0) { $0 + ($1 == "\t" ? 4 : 1) } }

    private static func cells(_ s: String) -> [String] {
        var t = s.trimmingCharacters(in: .whitespaces)
        if t.hasPrefix("|") { t.removeFirst() }
        if t.hasSuffix("|") { t.removeLast() }
        return t.components(separatedBy: "|").map { $0.trimmingCharacters(in: .whitespaces) }
    }

    static func parse(_ src: String) -> [MDBlock] {
        var out: [MDBlock] = []
        var para = ""
        func flush() { if !para.isEmpty { out.append(.para(para.trimmingCharacters(in: .newlines))) }; para = "" }
        let lines = src.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
        var fence: String? = nil, lang = "", code = ""
        var indents: [Int] = []
        var i = 0
        while i < lines.count {
            let line = lines[i]
            if let open = fence {
                let t = line.trimmingCharacters(in: .whitespaces)
                if t.count >= open.count, t.allSatisfy({ $0 == open.first! }) {
                    out.append(.code(lang: lang, text: String(code.dropLast(code.hasSuffix("\n") ? 1 : 0)))); fence = nil; code = ""
                } else { code += line + "\n" }
                i += 1; continue
            }
            if let f = match(fenceOpen, line) { flush(); indents = []; fence = f[1]; lang = f[2] }
            else if let h = match(heading, line) { flush(); indents = []; out.append(.heading(h[1].count, h[2])) }
            else if match(rule, line) != nil { flush(); indents = []; out.append(.rule) }
            else if line.trimmingCharacters(in: .whitespaces).hasPrefix(">") {
                flush(); indents = []
                var q = ""
                while i < lines.count, lines[i].trimmingCharacters(in: .whitespaces).hasPrefix(">") {
                    var l = lines[i].trimmingCharacters(in: .whitespaces); l.removeFirst()
                    if l.hasPrefix(" ") { l.removeFirst() }
                    q += l + "\n"; i += 1
                }
                out.append(.quote(parse(q))); continue
            } else if line.contains("|"), i + 1 < lines.count, lines[i + 1].contains("|"), match(tableSep, lines[i + 1]) != nil {
                flush(); indents = []
                let header = cells(line)
                var rows: [[String]] = []
                i += 2
                while i < lines.count, lines[i].contains("|"), !lines[i].trimmingCharacters(in: .whitespaces).isEmpty { rows.append(cells(lines[i])); i += 1 }
                out.append(.table(header: header, rows: rows)); continue
            } else if let b = match(bullet, line) ?? match(numbered, line) {
                flush()
                let isBullet = match(bullet, line) != nil
                let ind = indent(line)
                while let last = indents.last, last > ind { indents.removeLast() }
                if indents.last.map({ $0 < ind }) ?? true { indents.append(ind) }
                let rest = isBullet ? b[2] : b[3]
                let box = match(taskBox, rest)
                out.append(.item(depth: indents.count - 1, marker: isBullet ? "•" : b[2], text: box?[2] ?? rest, task: box.map { $0[1] != " " }))
            } else if line.trimmingCharacters(in: .whitespaces).isEmpty {
                flush()
                if i + 1 >= lines.count || (match(bullet, lines[i + 1]) == nil && match(numbered, lines[i + 1]) == nil) { indents = [] }
            } else if !indents.isEmpty, para.isEmpty, case .item(let d, let m, let t, let k)? = out.last, indent(line) > 0 {
                out[out.count - 1] = .item(depth: d, marker: m, text: t + "\n" + line.trimmingCharacters(in: .whitespaces), task: k)
            } else {
                indents = []
                para += (para.isEmpty ? "" : "\n") + line
            }
            i += 1
        }
        if fence != nil { out.append(.code(lang: lang, text: String(code.dropLast(code.hasSuffix("\n") ? 1 : 0)))) }
        flush()
        return out
    }
}

/// Inline Markdown (bold, italic, code, links) as styled text.
func inlineMarkdown(_ s: String) -> AttributedString {
    var a = (try? AttributedString(markdown: s, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace, failurePolicy: .returnPartiallyParsedIfPossible)))
        ?? AttributedString(s)
    for run in a.runs where run.inlinePresentationIntent?.contains(.code) == true {
        a[run.range].font = .system(.body, design: .monospaced)
        a[run.range].backgroundColor = Color.primary.opacity(0.08)
    }
    return a
}

/// A reply (or a message) laid out from its Markdown.
struct MarkdownView: View {
    var text: String
    var color: Color = .primary
    @Environment(\.chatDetail) private var detail

    var body: some View {
        let blocks = MarkdownParser.parse(text)
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, b in block(b) }
        }
        .foregroundStyle(color)
        .textSelection(.enabled)
    }

    @ViewBuilder private func block(_ b: MDBlock) -> some View {
        switch b {
        case .para(let s):
            Text(inlineMarkdown(s)).font(.body).lineSpacing(3).fixedSize(horizontal: false, vertical: true)
        case .heading(let level, let s):
            Text(inlineMarkdown(s)).font(.system(level == 1 ? .title2 : level == 2 ? .title3 : .headline, design: .rounded)).fontWeight(.heavy).padding(.top, 4)
        case .item(let depth, let marker, let s, let task):
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(task == true ? "☑" : task == false ? "☐" : marker == "•" ? ["•", "◦", "▪"][depth % 3] : marker)
                    .foregroundStyle(.tint).fontWeight(.bold).frame(minWidth: 16, alignment: .leading)
                Text(inlineMarkdown(s)).foregroundStyle(task == true ? .secondary : color).fixedSize(horizontal: false, vertical: true)
            }
            .padding(.leading, CGFloat(depth) * 18)
        case .code(let lang, let s):
            CodeBlock(lang: lang, code: s, fold: detail.codeFoldLines)
        case .quote(let inner):
            HStack(alignment: .top, spacing: 10) {
                RoundedRectangle(cornerRadius: 2).fill(.tint.opacity(0.5)).frame(width: 3)
                MarkdownView(text: inner.isEmpty ? "" : quoteText(inner), color: .secondary)
            }
        case .table(let header, let rows):
            TableBlock(header: header, rows: rows)
        case .rule:
            Divider().padding(.vertical, 4)
        }
    }

    private func quoteText(_ blocks: [MDBlock]) -> String {
        blocks.map { b -> String in
            switch b {
            case .para(let s): s
            case .heading(let l, let s): String(repeating: "#", count: l) + " " + s
            case .item(let d, let m, let s, _): String(repeating: "  ", count: d) + (m == "•" ? "-" : m) + " " + s
            case .code(let l, let s): "```\(l)\n\(s)\n```"
            default: ""
            }
        }.joined(separator: "\n\n")
    }
}

/// A light card with a hairline grid, the header row tinted. Columns get the width their longest cell needs (Chinese
/// characters count double); a narrow table stretches to the full width, a wide one scrolls sideways.
private struct TableBlock: View {
    var header: [String]
    var rows: [[String]]

    var body: some View {
        GeometryReader { g in
            let natural = (0..<header.count).map { columnWidth($0) }
            let total = natural.reduce(0, +)
            let widths = total < g.size.width ? natural.map { $0 * g.size.width / total } : natural
            ScrollView(.horizontal, showsIndicators: false) {
                VStack(spacing: 0) {
                    row(header, widths, bold: true).background(Color.primary.opacity(0.06))
                    ForEach(rows.indices, id: \.self) { r in
                        Divider()
                        row(rows[r], widths, bold: false)
                    }
                }
            }
        }
        .frame(height: CGFloat(rows.count + 1) * 35)
        .background(.background.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Color.primary.opacity(0.08)))
    }

    private static func units(_ s: String) -> Int {
        var n = 0
        for ch in s.unicodeScalars { n += ch.value > 0x2E80 ? 2 : 1 }
        return n
    }

    private func columnWidth(_ c: Int) -> CGFloat {
        var longest = 4
        for r in [header] + rows where c < r.count { longest = max(longest, Self.units(r[c])) }
        return min(max(CGFloat(longest) * 7.5 + 28, 64), 220)
    }

    private func row(_ cells: [String], _ widths: [CGFloat], bold: Bool) -> some View {
        HStack(spacing: 0) {
            ForEach(widths.indices, id: \.self) { c in
                Text(inlineMarkdown(c < cells.count ? cells[c] : "")).fontWeight(bold ? .bold : .regular).lineLimit(1)
                    .padding(.horizontal, 12).frame(width: widths[c], height: 34, alignment: .leading)
            }
        }
    }
}

/// A code panel: the language and a copy button on top; long code folds in the shorter display levels.
struct CodeBlock: View {
    var lang: String
    var code: String
    var fold: Int
    @State private var expanded = false
    @State private var copied = false
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let lines = code.components(separatedBy: "\n").count
        let folded = lines > fold && !expanded
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(lang.isEmpty ? "code" : lang).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    .padding(.horizontal, 8).padding(.vertical, 2).background(Color.primary.opacity(0.07), in: Capsule())
                Spacer()
                Button {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(code, forType: .string)
                    copied = true
                    Task { try? await Task.sleep(for: .seconds(1.5)); copied = false }
                } label: {
                    Label(copied ? L.t("copied") : L.t("copy"), systemImage: copied ? "checkmark" : "doc.on.doc").font(.caption)
                }
                .buttonStyle(.borderless)
            }
            .padding(.horizontal, 12).padding(.vertical, 7)
            .background(Color.primary.opacity(scheme == .dark ? 0.06 : 0.04))
            ScrollView(.horizontal, showsIndicators: false) {
                Text(highlight(code, lang: lang, dark: scheme == .dark))
                    .font(.system(size: 12.5, design: .monospaced)).lineSpacing(3)
                    .lineLimit(folded ? fold : nil)
                    .textSelection(.enabled)
                    .padding(12)
            }
            if lines > fold {
                Button { withAnimation(.snappy) { expanded.toggle() } } label: {
                    HStack {
                        Text(folded ? L.f("code.showAll", lines) : L.t("code.showLess"))
                        Spacer()
                        Image(systemName: folded ? "chevron.down" : "chevron.up")
                    }
                    .font(.caption).foregroundStyle(.secondary).padding(.horizontal, 12).padding(.vertical, 6).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .background(scheme == .dark ? Color(white: 0.11) : .white, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Color.primary.opacity(0.08)))
    }
}

/// Light syntax colours: keywords, strings, numbers and comments (enough to read code, without a parser per language).
func highlight(_ code: String, lang: String, dark: Bool) -> AttributedString {
    var a = AttributedString(code)
    a.foregroundColor = dark ? Color(white: 0.9) : Color(red: 0.14, green: 0.16, blue: 0.18)
    let keyword = dark ? Color(red: 0.78, green: 0.57, blue: 0.92) : Color(red: 0.81, green: 0.13, blue: 0.18)
    let string = dark ? Color(red: 0.76, green: 0.91, blue: 0.55) : Color(red: 0.04, green: 0.19, blue: 0.41)
    let number = dark ? Color(red: 0.97, green: 0.55, blue: 0.42) : Color(red: 0.02, green: 0.31, blue: 0.68)
    let comment = dark ? Color(white: 0.5) : Color(white: 0.45)
    let words = "func|let|var|if|else|for|while|return|import|from|def|class|struct|enum|in|of|and|or|not|true|false|nil|null|None|True|False|const|fn|pub|use|match|case|switch|try|catch|throw|async|await|export|function|new|this|self|echo|then|fi|do|done|sudo|package|interface|type|public|private|static|val"
    let rules: [(String, Color)] = [
        (#"\b(\#(words))\b"#, keyword),
        (#"\b\d+(\.\d+)?\b"#, number),
        (#""(\\.|[^"\\])*"|'(\\.|[^'\\])*'"#, string),
        (#"(//|#)[^\n]*|/\*[\s\S]*?\*/"#, comment),
    ]
    let ns = code as NSString
    for (pattern, color) in rules {
        guard let r = try? NSRegularExpression(pattern: pattern) else { continue }
        for m in r.matches(in: code, range: NSRange(location: 0, length: ns.length)) {
            guard let range = Range(m.range, in: code), let ar = Range(range, in: a) else { continue }
            // `#` starts a comment only in shell-like code
            if color == comment, ns.substring(with: m.range).hasPrefix("#"), !["sh", "bash", "zsh", "python", "py", "yaml", "yml", "toml", "conf", ""].contains(lang.lowercased()) { continue }
            a[ar].foregroundColor = color
        }
    }
    return a
}

private struct ChatDetailKey: EnvironmentKey { static let defaultValue = ChatDetail.standard }
extension EnvironmentValues {
    var chatDetail: ChatDetail {
        get { self[ChatDetailKey.self] }
        set { self[ChatDetailKey.self] = newValue }
    }
}
