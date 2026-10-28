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
    )
}
