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

final class SyncTests: XCTestCase {
    func testAChatFromThePhoneReads() {
        // as the phone writes it: every field, Long times, attachment kinds in lower case, no local path
        let phone = #"{"conversation":{"id":"c1","title":"状态","created":1,"updated":1700000000123,"pinned":true,"renamed":false},"messages":[{"role":"user","content":"hi","reasoning":"","id":"m1","time":10,"attachments":[{"id":"a1","name":"p.png","mime":"image/png","size":3,"kind":"image","remotePath":""}],"failed":false,"thinkingMs":0,"tools":[]},{"role":"assistant","content":"ok","reasoning":"hmm","id":"m2","time":11,"attachments":[],"failed":false,"thinkingMs":900,"tools":[{"id":"t1","tool":"terminal","label":"","command":"ls","output":"x","exitCode":0,"error":null,"durationMs":40,"done":true,"at":2}]}]}"#
        guard let (c, m) = ChatWire.decode(Data(phone.utf8)) else { return XCTFail("did not read") }
        XCTAssertEqual(c.title, "状态")
        XCTAssertTrue(c.pinned)
        XCTAssertEqual(ChatWire.ms(c.updated), 1700000000123)
        XCTAssertEqual(m[0].attachments[0].kind, .image)
        XCTAssertEqual(m[0].attachments[0].local, "")
        XCTAssertEqual(m[1].tools[0].command, "ls")
        XCTAssertEqual(m[1].thinkingMs, 900)
    }

    func testWhatTheMacSendsHasNoLocalPaths() throws {
        let c = Conversation(id: "x", title: "t", created: Date(timeIntervalSince1970: 1), updated: Date(timeIntervalSince1970: 2))
        let m = [ChatMessage(role: "user", content: "hi", attachments: [Attachment(name: "a.txt", mime: "text/plain", size: 1, kind: .text, local: "/Users/me/a.txt")])]
        let data = try XCTUnwrap(ChatWire.encode(c, m))
        let o = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual((o["conversation"] as? [String: Any])?["updated"] as? Int, 2000)
        let att = ((o["messages"] as? [[String: Any]])?.first?["attachments"] as? [[String: Any]])?.first
        XCTAssertNil(att?["local"])
        XCTAssertEqual(att?["kind"] as? String, "text")
        let (back, msgs) = try XCTUnwrap(ChatWire.decode(data))
        XCTAssertEqual(back.id, "x")
        XCTAssertEqual(msgs[0].content, "hi")
    }
}

final class ModelTests: XCTestCase {
    private func catalog() throws -> Catalog {
        let json = #"{"providers":[{"id":"openai-codex","name":"ChatGPT","models":[{"id":"t","name":"GPT-6.1 Terra","efforts":["low","high","xhigh"],"fast":true}]},{"id":"deepseek","name":"DeepSeek","models":[{"id":"f","name":"Flash","efforts":["none","high"]}]}]}"#
        return try JSONDecoder().decode(Catalog.self, from: Data(json.utf8))
    }

    func testFastComesFromTheServer() throws {
        let c = try catalog()
        XCTAssertTrue(c.providers[0].models[0].fast)
        XCTAssertFalse(c.providers[1].models[0].fast)   // an older server does not say
        XCTAssertEqual(c.providers[0].models[0].top, "xhigh")
    }

    func testTheLabelNamesUltraAndFast() throws {
        let c = try catalog()
        XCTAssertEqual(c.label(ModelPick(provider: "openai-codex", model: "t", reasoning: "xhigh", fast: true)), "⚡ GPT-6.1 Terra Ultra")
        XCTAssertEqual(c.label(ModelPick(provider: "openai-codex", model: "t", reasoning: "high")), "GPT-6.1 Terra · \(L.t("effort.high"))")
        // Fast only shows for models that have it
        XCTAssertEqual(c.label(ModelPick(provider: "deepseek", model: "f", reasoning: "high", fast: true)), "Flash Ultra")
        XCTAssertEqual(c.label(ModelPick()), L.t("model.default"))
    }

    func testAnOlderSavedPickStillReads() throws {
        let p = try JSONDecoder().decode(ModelPick.self, from: Data(#"{"provider":"a","model":"b","reasoning":"high"}"#.utf8))
        XCTAssertEqual(p, ModelPick(provider: "a", model: "b", reasoning: "high", fast: false))
    }
}
