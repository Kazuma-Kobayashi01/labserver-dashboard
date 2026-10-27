package com.labserver.lablink.ui

/** What a stretch of code is, for colouring. No colours in here: the theme decides them. */
internal enum class Tok { Plain, Keyword, Type, Str, Comment, Number, Added, Removed }

internal data class Span(val start: Int, val end: Int, val kind: Tok)

private class Lang(
    val keywords: Set<String>,
    val lineComments: List<String> = emptyList(),
    val blockComment: Pair<String, String>? = null,
    val backtickStrings: Boolean = false,
    val ignoreCase: Boolean = false,
    val types: Boolean = true,
)

private fun words(s: String) = s.split(' ', '\n').filter { it.isNotEmpty() }.toSet()

private val cLike = words(
    """if else for while do switch case default break continue return fun func fn function def class struct enum interface impl trait object
    val var let const static public private protected internal final override import package use mod pub async await try catch finally throw throws
    new this self super null nil true false void int long short float double char bool boolean string in is as when match type typealias where from
    export extends implements instanceof typeof namespace using extern unsigned signed sizeof inline virtual template typename yield defer go chan
    select range mut ref dyn crate loop unsafe suspend lateinit open abstract data sealed companion init constructor get set operator readonly
    undefined delete with of let guard protocol extension throwing rethrows fileprivate""")
private val python = words(
    """def class if elif else for while return import from as with try except finally raise pass break continue lambda yield async await None True
    False and or not in is global nonlocal del assert self match case""")
private val shell = words("if then else elif fi for while until do done case esac function in return exit export local sudo select time")
private val sql = words(
    """select from where insert into values update set delete create table alter drop join left right inner outer on group by order having limit
    offset as and or not null primary key foreign references index distinct union all case when then else end exists between like in is
    count sum avg min max begin commit rollback view default unique check""")
private val literals = words("true false null")

private val langs: Map<String, Lang> = buildMap {
    val c = Lang(cLike, listOf("//"), "/*" to "*/")
    for (n in "c cpp c++ cc h hpp java kotlin kt kts swift scala dart cs csharp rust rs go php js javascript jsx ts typescript tsx objc".split(' ')) put(n, c)
    put("js", Lang(cLike, listOf("//"), "/*" to "*/", backtickStrings = true))
    put("javascript", get("js")!!); put("jsx", get("js")!!); put("ts", get("js")!!); put("typescript", get("js")!!); put("tsx", get("js")!!)
    val py = Lang(python, listOf("#"))
    for (n in "py python python3 ipython".split(' ')) put(n, py)
    val sh = Lang(shell, listOf("#"), backtickStrings = true, types = false)
    for (n in "sh bash zsh shell console fish".split(' ')) put(n, sh)
    val hash = Lang(literals, listOf("#"), types = false)
    for (n in "yaml yml toml ini conf dockerfile makefile env properties".split(' ')) put(n, hash)
    put("sql", Lang(sql, listOf("--"), "/*" to "*/", ignoreCase = true, types = false))
    put("json", Lang(literals, types = false)); put("jsonc", Lang(literals, listOf("//"), "/*" to "*/", types = false))
    put("html", Lang(emptySet(), blockComment = "<!--" to "-->", types = false)); put("xml", get("html")!!)
    put("css", Lang(emptySet(), blockComment = "/*" to "*/", types = false))
    put("lua", Lang(words("function end if then else elseif for while do return local nil true false and or not in repeat until break"), listOf("--"), types = false))
    put("ruby", Lang(words("def end class module if elsif else unless while do return require include nil true false self begin rescue ensure yield"), listOf("#")))
    put("rb", get("ruby")!!)
}

private val numberStart = Regex("""0[xX][0-9a-fA-F_]+|\d[\d_]*(\.\d+)?([eE][+-]?\d+)?""")

/**
 * A small, forgiving tokenizer: comments, strings, numbers, keywords and capitalised names, per language family. Unknown
 * languages get strings, numbers and `//`/`#` comments only. `diff` colours added and removed lines.
 */
internal fun tokenize(code: String, language: String): List<Span> {
    val key = language.trim().lowercase()
    if (key == "diff" || key == "patch") return diffSpans(code)
    val lang = langs[key] ?: Lang(literals, listOf("//", "#"), "/*" to "*/", types = false)
    val spans = ArrayList<Span>()
    var i = 0
    fun add(s: Int, e: Int, k: Tok) { if (e > s) spans += Span(s, e, k) }
    while (i < code.length) {
        val c = code[i]
        val lineComment = lang.lineComments.firstOrNull { code.startsWith(it, i) }
        when {
            lang.blockComment != null && code.startsWith(lang.blockComment.first, i) -> {
                val end = code.indexOf(lang.blockComment.second, i + lang.blockComment.first.length).let { if (it < 0) code.length else it + lang.blockComment.second.length }
                add(i, end, Tok.Comment); i = end
            }
            lineComment != null -> {
                val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                add(i, end, Tok.Comment); i = end
            }
            c == '"' || c == '\'' || (c == '`' && lang.backtickStrings) -> {
                var j = i + 1
                while (j < code.length && code[j] != c && (code[j] != '\n' || c == '`')) { if (code[j] == '\\') j++; j++ }
                j = minOf(j, code.length)
                when {
                    j < code.length && code[j] == c -> { add(i, j + 1, Tok.Str); i = j + 1 }
                    c == '"' -> { add(i, j, Tok.Str); i = j }   // an unterminated double-quoted string runs to the end of the line
                    else -> i++                                  // a lone apostrophe or backtick is just a character
                }
            }
            c.isDigit() && (i == 0 || !(code[i - 1].isLetterOrDigit() || code[i - 1] == '_')) -> {
                val m = numberStart.matchAt(code, i)
                if (m != null) { add(i, i + m.value.length, Tok.Number); i += m.value.length } else i++
            }
            c.isLetter() || c == '_' -> {
                var j = i
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val w = code.substring(i, j)
                val probe = if (lang.ignoreCase) w.lowercase() else w
                when {
                    probe in lang.keywords -> add(i, j, Tok.Keyword)
                    lang.types && w[0].isUpperCase() && w.any { it.isLowerCase() } -> add(i, j, Tok.Type)
                }
                i = j
            }
            else -> i++
        }
    }
    return spans
}

private fun diffSpans(code: String): List<Span> {
    val spans = ArrayList<Span>()
    var pos = 0
    for (line in code.split('\n')) {
        val end = pos + line.length
        when {
            line.startsWith("+++") || line.startsWith("---") -> spans += Span(pos, end, Tok.Keyword)
            line.startsWith("@@") -> spans += Span(pos, end, Tok.Type)
            line.startsWith("+") -> spans += Span(pos, end, Tok.Added)
            line.startsWith("-") -> spans += Span(pos, end, Tok.Removed)
        }
        pos = end + 1
    }
    return spans.filter { it.end > it.start }
}
