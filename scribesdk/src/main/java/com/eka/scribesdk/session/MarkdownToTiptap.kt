package com.eka.scribesdk.session

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Markdown -> web editor (Tiptap, StarterKit nodes) JSON, for text edited on Android that the web
 * shows from its editor JSON. Covers what free text uses: paragraphs (lines kept as hard breaks),
 * `#` headings, `-`/`*`/`+` and `1.` lists, `**bold**` and `*italic*`/`_italic_`.
 * Anything else stays as plain text.
 */
internal object MarkdownToTiptap {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^\\d+[.)]\\s+(.*)$")
    private val INLINE = Regex("\\*\\*(.+?)\\*\\*|\\*(\\S(?:.*?\\S)?)\\*|_(\\S(?:.*?\\S)?)_")
    private val ESCAPED = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!>|])")

    fun convert(markdown: String): JsonObject {
        val blocks = JsonArray()
        val lines = markdown.replace("\r\n", "\n").split("\n")
        val paragraph = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            val content = JsonArray()
            paragraph.forEachIndexed { index, line ->
                if (index > 0) content.add(node("hardBreak"))
                inline(line).forEach(content::add)
            }
            blocks.add(node("paragraph", content))
            paragraph.clear()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val heading = HEADING.matchEntire(line)
            when {
                line.isEmpty() -> {
                    flushParagraph()
                    i++
                }

                heading != null -> {
                    flushParagraph()
                    blocks.add(node("heading", inline(heading.groupValues[2])).apply {
                        add("attrs", JsonObject().apply { addProperty("level", heading.groupValues[1].length) })
                    })
                    i++
                }

                BULLET.matches(line) || ORDERED.matches(line) -> {
                    flushParagraph()
                    val pattern = if (ORDERED.matches(line)) ORDERED else BULLET
                    val items = JsonArray()
                    while (i < lines.size) {
                        val item = pattern.matchEntire(lines[i].trim()) ?: break
                        items.add(node("listItem", JsonArray().apply { add(node("paragraph", inline(item.groupValues[1]))) }))
                        i++
                    }
                    blocks.add(node(if (pattern == ORDERED) "orderedList" else "bulletList", items))
                }

                else -> {
                    paragraph += line
                    i++
                }
            }
        }
        flushParagraph()
        // The web rejects a document with no content
        if (blocks.size() == 0) blocks.add(node("paragraph"))

        return node("doc", blocks)
    }

    /** Text nodes for one line; empty text nodes are not allowed, so blank input gives none. */
    private fun inline(text: String): JsonArray {
        val nodes = JsonArray()
        var last = 0
        for (match in INLINE.findAll(text)) {
            addText(nodes, text.substring(last, match.range.first), null)
            val (bold, star, underscore) = match.destructured
            if (bold.isNotEmpty()) addText(nodes, bold, "bold") else addText(nodes, star.ifEmpty { underscore }, "italic")
            last = match.range.last + 1
        }
        addText(nodes, text.substring(last), null)
        return nodes
    }

    private fun addText(nodes: JsonArray, raw: String, mark: String?) {
        val text = raw.replace(ESCAPED, "$1")
        if (text.isEmpty()) return
        nodes.add(JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", text)
            if (mark != null) {
                add("marks", JsonArray().apply { add(JsonObject().apply { addProperty("type", mark) }) })
            }
        })
    }

    private fun node(type: String, content: JsonArray? = null): JsonObject = JsonObject().apply {
        addProperty("type", type)
        if (content != null && content.size() > 0) add("content", content)
    }
}
