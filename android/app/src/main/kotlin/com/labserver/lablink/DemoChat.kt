package com.labserver.lablink

import com.labserver.lablink.data.ChatMessage

/** A sample conversation for looking at the chat screen in debug builds (never saved). */
object DemoChat {
    fun messages(): List<ChatMessage> = listOf(
        ChatMessage("user", "帮我比较一下 Rust 和 Go 写命令行工具的取舍"),
        ChatMessage(
            "assistant",
            """## 简短结论

**Go** 上手快、编译快、交叉编译省心；**Rust** 性能和二进制体积更好，但学习曲线陡。

| 方面 | Go | Rust |
|---|---|---|
| 学习成本 | 低 | 高 |
| 启动速度 | 快 | 更快 |
| 生态 | cobra | clap |

1. 团队里没人写过 Rust：选 Go
2. 需要极致性能或嵌入式：选 Rust

```go
func main() {
    fmt.Println("hello")
}
```""",
            reasoning = "用户想要取舍对比，给一个表格加一句结论最清楚。",
            thinkingMs = 4200,
        ),
        ChatMessage("user", "那就用 Go，帮我起个项目骨架"),
        ChatMessage(
            "assistant",
            "先看看工作目录里有什么。\n\n好了，骨架建好了：`cmd/`、`internal/` 和 `go.mod` 都在。",
            tools = listOf(
                com.labserver.lablink.data.ToolRun(
                    id = "demo-1", label = "List the workspace", command = "ls -la /workspace && go version",
                    output = "total 8\ndrwxr-xr-x 2 root root 4096 .\ndrwxr-xr-x 1 root root 4096 ..\ngo version go1.26.6 linux/amd64",
                    exitCode = 0, durationMs = 412, done = true, at = 13,
                ),
                com.labserver.lablink.data.ToolRun(
                    id = "demo-2", label = "Create the module", command = "mkdir -p cmd/app internal && go mod init example.com/app",
                    output = "go: creating new go.mod: module example.com/app", exitCode = 0, durationMs = 1830, done = true, at = 13,
                ),
            ),
        ),
    )
}
