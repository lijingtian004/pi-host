package works.earendil.pihost.feature.chat.ui

import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j
import java.util.regex.Pattern

/**
 * prism4j 语法表（规格书 §3.3「prism4j 语言覆盖（D5）」的落地形态）。
 *
 * **一个必须记录的事实**（RECON-P2E §1.3）：规格书写的是「`Prism4j.languages()` 全量注入、
 * `Prism4j.GrammarDef` 默认集」，但 `io.noties:prism4j:2.0.0` 这个 jar 里**只有 API 与注解**
 * （编译机实测 `unzip -l`：37 个条目、31 个 `.class`，全部落在 `io/noties/prism4j` 与
 * `io/noties/prism4j/annotations` 两个包，**没有任何一门语言语法**），
 * **语法表从来不是库自带的**，而是 `prism4j-bundler`
 * （一个 KAPT 注解处理器 + 语法定义包）生成的 `Prism4jGrammarLocator`。
 * bundler 既不在 §2 依赖白名单里，也要额外引入 kapt 插件线（本工程全 KSP），所以这里
 * 按规格书的**意图**实现：自己给一套 code-fence 通用语法，覆盖最常见的注释/字符串/
 * 关键字/数字/函数名五类，其余一律不染色。
 *
 * 未注册语言（含 mermaid）经 [FALLBACK] 落到同一套通用语法 = 「按普通 code-block 展示」，
 * D5 保持开档，不引 WebView 后备。
 *
 * 线程：实例挂在 Markwon 上（@Singleton），[grammars] 只在首次访问时构建一次；
 * Prism4j 的 Grammar/Token 都是不可值对象，主线程读、无锁安全。
 */
class PiGrammarLocator : GrammarLocator {

    private val grammars: Map<String, Prism4j.Grammar> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { buildAll() }

    override fun languages(): Set<String> = ALIASES.keys.toSet()

    /**
     * @param language fence 上写的语言名（markwon 原样传下来，可能是 `sh`/`KT`/`c++`）
     * @return 通用语法；**别名表里没有则 null**，由 Prism4jSyntaxHighlight 走 [FALLBACK] 或纯文本
     *
     * 签名照抄接口真实形状（RECON-P2E §1.4 的编译器原文）：`GrammarLocator.grammar` 的
     * language 参数是**非空 `String`**（Kotlin 看到的 `p1: String`），写成 `String?` 时
     * Kotlin 2.2.20 判「overrides nothing」——空值情形由 `isBlank()` 兜住。
     */
    override fun grammar(prism4j: Prism4j, language: String): Prism4j.Grammar? {
        if (language.isBlank()) return null
        val canonical = ALIASES[language.trim().lowercase()] ?: return null
        return grammars[canonical]
    }

    private fun buildAll(): Map<String, Prism4j.Grammar> =
        // 所有注册别名共享同一套「通用」语法定义（P2E 的 D5 决策：不做 per-language 语法表）
        GENERIC_LANGUAGES.associateWith { name -> grammar(name) }

    companion object {
        /** 未注册语言的去处：普通 code-block（§3.3）。对外可见，测试与装配都引用它。 */
        const val FALLBACK: String = "text"

        private val GENERIC_LANGUAGES: List<String> = listOf(
            FALLBACK, "bash", "c", "java", "javascript", "json", "kotlin", "python", "sql", "typescript", "yaml",
        )

        /** fence 写法 → Grammar 名。未列出的写法一律走 [FALLBACK]。 */
        val ALIASES: Map<String, String> = buildMap {
            GENERIC_LANGUAGES.forEach { put(it, it) }
            put("sh", "bash")
            put("shell", "bash")
            put("zsh", "bash")
            put("console", "bash")
            put("js", "javascript")
            put("jsx", "javascript")
            put("node", "javascript")
            put("ts", "typescript")
            put("tsx", "typescript")
            put("kt", "kotlin")
            put("kts", "kotlin")
            put("py", "python")
            put("rs", "rust")
            put("c++", "c")
            put("cpp", "c")
            put("md", FALLBACK)
            put("markdown", FALLBACK)
            put("mermaid", FALLBACK)
            put("html", FALLBACK)
            put("css", FALLBACK)
            put("toml", FALLBACK)
            put("ini", FALLBACK)
            put("plain", FALLBACK)
            put("txt", FALLBACK)
        }

        // ── 五类 token 的正则（java.util.regex，Android 运行时同款）────────────────
        private val COMMENT_LINE: Pattern = Pattern.compile("(?://[^\\n]*|#(?!!)[^\\n]*)")
        private val COMMENT_BLOCK: Pattern = Pattern.compile("/\\*+[^*]*\\\\*+[^/*]*\\*/|/\\*+[\\s\\S]*?\\*/")
        private val STRING_DOUBLE: Pattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
        private val STRING_SINGLE: Pattern = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'")
        private val STRING_BACKTICK: Pattern = Pattern.compile("`(?:[^`\\\\]|\\\\.)*`")
        private val KEYWORD: Pattern = Pattern.compile(
            "\\b(?:fun|val|var|if|else|when|for|while|do|return|import|package|class|object|interface|enum|" +
                "true|false|null|def|elif|then|end|in|echo|export|local|set|unset|const|let|new|async|await|" +
                "try|catch|finally|throw|public|private|protected|static|final|void|this|super|extends|implements|" +
                "switch|case|break|continue|type|struct|from|as|is|typeof|instanceof|fn|Some|None|self|SELECT|FROM|" +
                "WHERE|INSERT|UPDATE|DELETE|JOIN|GROUP|ORDER|BY|LIMIT)\\b",
        )
        private val FUNCTION: Pattern = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*(?=\\s*\\()")
        private val NUMBER: Pattern = Pattern.compile("\\b(?:0[xXbBaAfF])?[0-9]+(?:\\.[0-9]+)?\\b")

        /** name = prism4j 的 token 类名，markwon 的 Prism4jTheme 按它取色（comment/string/keyword/…）。 */
        fun grammar(language: String): Prism4j.Grammar = Prism4j.grammar(
            language,
            listOf(
                Prism4j.token("comment", listOf(pattern(COMMENT_BLOCK), pattern(COMMENT_LINE))),
                Prism4j.token("string", listOf(pattern(STRING_DOUBLE), pattern(STRING_SINGLE), pattern(STRING_BACKTICK))),
                Prism4j.token("keyword", listOf(pattern(KEYWORD))),
                Prism4j.token("function", listOf(pattern(FUNCTION))),
                Prism4j.token("number", listOf(pattern(NUMBER))),
            ),
        )

        /**
         * `pattern(regex, lookbehind)`：第二条恒 false —— 本工程不用「lookbehind 取 group(1)」
         * 这种 prism4j 高级写法（RECON-P2E §1.3：真实行为以冒烟用例实测为准）。
         * 前瞻 `(?=...)` 不吃字符，所以 lookbehind=false 下 [FUNCTION] 依然只命中标识符本身。
         */
        fun pattern(regex: Pattern): Prism4j.Pattern = Prism4j.pattern(regex, false)
    }
}
