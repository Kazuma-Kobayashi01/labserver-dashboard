package com.labserver.lablink.data

/**
 * How much of a bot's work a reply shows, from everything to just the answer (Settings → Conversation display).
 *  - [Full]: the thinking open, every command window open and left open, code in full.
 *  - [Standard]: the thinking folded, a command open while it runs and folded once it is done.
 *  - [Compact]: commands folded even while they run (one line with a spinner), long code folded.
 *  - [Minimal]: no thinking, the commands of a reply gathered into one line, code folded sooner.
 */
enum class ChatDetail {
    Full, Standard, Compact, Minimal;

    val thoughtsOpen: Boolean get() = this == Full
    val showThoughts: Boolean get() = this != Minimal
    val toolOpenWhileRunning: Boolean get() = this <= Standard
    val toolStaysOpen: Boolean get() = this == Full
    val groupTools: Boolean get() = this == Minimal

    /** Code blocks longer than this show only this many lines until opened. */
    val codeFoldLines: Int get() = when (this) {
        Full, Standard -> Int.MAX_VALUE
        Compact -> 14
        Minimal -> 6
    }

    companion object {
        fun of(name: String?): ChatDetail = entries.firstOrNull { it.name == name } ?: Standard
    }
}
