package dev.kudde.android.ui.spaces

// The tile picture's re-flow (design D11): `terminal.read` joins soft wraps, so each line of the
// text is a logical line; the tile wraps them again to its own width, as a phone-sized terminal.

private fun isBox(c: Char) = c in '─'..'╿'

private fun isVertical(c: Char) = c in "│┃║╎╏┆┇┊┋"

private fun isAsciiRule(c: Char) = c == '-' || c == '=' || c == '_'

/** The lines of [text] wrapped to [columns], trailing blanks dropped, rules not stacked. */
internal fun tilePictureLines(text: String, columns: Int): List<String> {
    val width = columns.coerceAtLeast(1)
    val logical = text.lines().map { stripFrame(it.trimEnd()) }.dropLastWhile { it.isEmpty() }
    return logical.flatMap { line -> wrap(collapseRuns(line, width), width) }
}

/** A frame's side bars at either edge of a row go (with the spaces inside them), so wrapping leaves none stray. */
private fun stripFrame(line: String): String {
    var s = line
    val start = s.indexOfFirst { it != ' ' }
    if (start < 0) return ""
    if (s.length - start > 1 && isVertical(s[start])) s = s.substring(start + 1).trimStart()
    if (s.trim().length > 1 && isVertical(s.last())) s = s.dropLast(1).trimEnd()
    return s
}

/** A line of nothing but rule characters (and spaces) is one rule; else long runs shrink to the width. */
private fun collapseRuns(line: String, width: Int): String {
    val solid = line.filter { it != ' ' }
    if (solid.length >= 3 && solid.all { isBox(it) || isAsciiRule(it) } && solid.any { !isVertical(it) }) {
        val ch = if (solid.any { isBox(it) }) '─' else solid.first()
        return ch.toString().repeat(width)
    }
    val out = StringBuilder()
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        var j = i
        while (j < line.length && line[j] == ch) j++
        val run = j - i
        val shrink = run > width && (isBox(ch) || (isAsciiRule(ch) && run >= 8))
        repeat(if (shrink) width else run) { out.append(ch) }
        i = j
    }
    return out.toString()
}

private fun wrap(line: String, width: Int): List<String> {
    if (line.length <= width) return listOf(line)
    val out = mutableListOf<String>()
    var rest = line
    while (rest.length > width) {
        val cut = rest.lastIndexOf(' ', width)
        val atSpace = cut >= width - width / 3 && cut > 0
        if (atSpace) {
            out += rest.substring(0, cut).trimEnd()
            rest = rest.substring(cut + 1).trimStart()
        } else {
            out += rest.substring(0, width)
            rest = rest.substring(width)
        }
    }
    if (rest.isNotEmpty()) out += rest
    return out
}
