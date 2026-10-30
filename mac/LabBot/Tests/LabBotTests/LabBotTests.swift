import XCTest
@testable import LabBot

final class ProtocolTests: XCTestCase {
    func testStreamEvents() {
        let c = Manager.parse(event: "", data: #"{"choices":[{"delta":{"content":"hi","reasoning_content":"hmm"}}]}"#)
        XCTAssertEqual(c.count, 2)
        if case .reasoning(let r) = c[0] { XCTAssertEqual(r, "hmm") } else { XCTFail() }
        if case .content(let s) = c[1] { XCTAssertEqual(s, "hi") } else { XCTFail() }

        let t = Manager.parse(event: "hermes.tool.progress", data: #"{"toolCallId":"t1","status":"running","tool":"terminal","label":"ls"}"#)
        XCTAssertEqual(t.count, 2)
        if case .toolStart(let id, let tool, let label) = t[1] { XCTAssertEqual([id, tool, label], ["t1", "terminal", "ls"]) } else { XCTFail() }

        let d = Manager.parse(event: "lablink.tool", data: #"{"toolCallId":"t1","status":"completed","command":"ls -la","output":"x","exitCode":2,"durationMs":40}"#)
        if case .toolDetail(_, let cmd, let done, let out, let code, _, let ms) = d[0] {
            XCTAssertEqual(cmd, "ls -la"); XCTAssertTrue(done); XCTAssertEqual(out, "x"); XCTAssertEqual(code, 2); XCTAssertEqual(ms, 40)
        } else { XCTFail() }
    }

    func testApprovalKinds() {
        let a = ApprovalRequest.parse(#"{"run_id":"r1","request_id":"q","pattern_key":"plugin_rule:lablink-system","description":"sudo reboot","choices":["once","session","deny"]}"#)
        XCTAssertEqual(a?.kind, "system")
        XCTAssertEqual(a?.command, "sudo reboot")
        XCTAssertEqual(a?.choices, ["once", "session", "deny"])
        XCTAssertEqual(ApprovalRequest.parse(#"{"run_id":"r2","command":"rm -rf /","pattern_key":"dangerous"}"#)?.kind, "danger")
        XCTAssertNil(ApprovalRequest.parse(#"{"command":"x"}"#))
    }

    func testBotKinds() {
        XCTAssertTrue(Bot.lab.hasTerminal)
        XCTAssertFalse(Bot.lab.hasSandbox)
        XCTAssertEqual(Bot.lab.profile, "labbot")
        let plain = Manager.bot(["id": "b0123456789", "name": "W", "sandboxed": false])
        XCTAssertFalse(plain.hasTerminal)
        XCTAssertEqual(plain.profile, "b0123456789")
        XCTAssertTrue(Manager.bot(["id": "b0123456789", "name": "Old"]).hasTerminal)   // older servers: all have one
    }

    func testPictureSplitting() {
        let parts = ReplyParts.split("Here:\nMEDIA:/root/out/plot.png.\nDone")
        XCTAssertEqual(parts, [.text("Here:"), .image(src: "media:/root/out/plot.png", alt: ""), .text("Done")])
        // a path still streaming in (no extension yet) stays text; code blocks are left alone
        XCTAssertEqual(ReplyParts.split("MEDIA:/root/out/plo"), [.text("MEDIA:/root/out/plo")])
        XCTAssertEqual(ReplyParts.split("```\nMEDIA:/a/b.png\n```"), [.text("```\nMEDIA:/a/b.png\n```")])
    }

    func testToolsSitBetweenTheirWords() {
        let tools = [ToolRun(id: "a", command: "ls", at: 6), ToolRun(id: "b", command: "pwd", at: 6)]
        let p = ReplyParts.pieces("Look: then done", tools, group: false)
        XCTAssertEqual(p.count, 4)
        if case .tool(let t) = p[1] { XCTAssertEqual(t.id, "a") } else { XCTFail() }
        let g = ReplyParts.pieces("Look: then done", tools, group: true)
        XCTAssertEqual(g.count, 3)
        if case .tools(let ts) = g[1] { XCTAssertEqual(ts.map(\.id), ["a", "b"]) } else { XCTFail() }
    }

    func testMarkdownBlocks() {
        let b = MarkdownParser.parse("# Title\n\n- one\n  - two\n- [x] done\n\n```swift\nlet x = 1\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n> quoted")
        XCTAssertEqual(b[0], .heading(1, "Title"))
        XCTAssertEqual(b[1], .item(depth: 0, marker: "•", text: "one", task: nil))
        XCTAssertEqual(b[2], .item(depth: 1, marker: "•", text: "two", task: nil))
        XCTAssertEqual(b[3], .item(depth: 0, marker: "•", text: "done", task: true))
        XCTAssertEqual(b[4], .code(lang: "swift", text: "let x = 1"))
        XCTAssertEqual(b[5], .table(header: ["a", "b"], rows: [["1", "2"]]))
        XCTAssertEqual(b[6], .quote([.para("quoted")]))
        // a reply still streaming inside a code block
        XCTAssertEqual(MarkdownParser.parse("```py\nprint(1)"), [.code(lang: "py", text: "print(1)")])
    }

    func testPayloadKeepsPicturesOnlyInLatestTurns() {
        var msgs: [ChatMessage] = []
        for i in 0..<5 {
            msgs.append(ChatMessage(role: "user", content: "q\(i)", attachments: [Attachment(name: "p\(i).png", mime: "image/png", size: 1, kind: .image, local: "/nonexistent")]))
        }
        let p = AppModel.payload(msgs)
        XCTAssertEqual(p.count, 5)
        // the file is gone, so every turn just names its picture
        XCTAssertTrue((p[0]["content"] as? String)?.contains("[an image was attached here: p0.png]") == true)
    }
}
