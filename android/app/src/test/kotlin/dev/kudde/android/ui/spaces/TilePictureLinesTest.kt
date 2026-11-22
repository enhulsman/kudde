package dev.kudde.android.ui.spaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The tile picture's re-flow (design D11): a screen's lines wrapped to the tile's width.
class TilePictureLinesTest {
    @Test fun plain_lines_shorter_than_the_width_stay() {
        assertEquals(listOf("hello", "world"), tilePictureLines("hello\nworld", 20))
    }

    @Test fun a_long_line_wraps_at_the_width() {
        assertEquals(listOf("abcdefghij", "klmnopqrst", "uv"), tilePictureLines("abcdefghijklmnopqrstuv", 10))
    }

    @Test fun a_wrap_prefers_a_space_in_the_last_third() {
        // 20 columns: the space at index 16 lies in the last third, so the break goes there.
        assertEquals(listOf("the quick brown fox", "jumps over"), tilePictureLines("the quick brown fox jumps over", 20))
        // A space early in the line (index 3) is not used: hard break.
        assertEquals(listOf("abc defghijklmnopqrs", "tuvwxyz"), tilePictureLines("abc defghijklmnopqrstuvwxyz", 20))
    }

    @Test fun a_long_token_breaks_hard() {
        assertEquals(listOf("https://exa", "mple.org/a/", "very/long"), tilePictureLines("https://example.org/a/very/long", 11))
    }

    @Test fun trailing_spaces_are_trimmed() {
        assertEquals(listOf("a", "b"), tilePictureLines("a   \nb      ", 10))
    }

    @Test fun trailing_blank_lines_are_dropped_but_inner_ones_stay() {
        assertEquals(listOf("a", "", "b"), tilePictureLines("a\n\nb\n  \n\n", 10))
    }

    @Test fun empty_text_has_no_lines() {
        assertTrue(tilePictureLines("", 10).isEmpty())
        assertTrue(tilePictureLines("  \n \n", 10).isEmpty())
    }

    @Test fun a_box_frame_line_becomes_one_width_long_rule() {
        val top = "╭" + "─".repeat(150) + "╮"
        assertEquals(listOf("─".repeat(12)), tilePictureLines(top, 12))
        assertEquals(listOf("─".repeat(12)), tilePictureLines("─".repeat(200), 12))
        assertEquals(listOf("=".repeat(12)), tilePictureLines("=".repeat(90) + "  ", 12))
    }

    @Test fun a_run_of_rules_inside_text_collapses_to_the_width() {
        val out = tilePictureLines("build " + "-".repeat(100) + " ok", 12)
        assertTrue(out.all { it.length <= 12 })
        assertTrue("no stack of dashes: $out", out.size <= 3)
    }

    @Test fun a_frame_row_keeps_its_text_without_the_frame_bars() {
        assertEquals(listOf("build ok"), tilePictureLines("│ build ok │", 20))
        assertEquals(listOf("build ok"), tilePictureLines("┃  build ok  ┃", 20))
    }

    @Test fun a_wrapped_frame_row_leaves_no_bar_on_its_own_line() {
        val out = tilePictureLines("│ 12:01:30 [build] page 30 of 38 written to dist │", 12)
        assertTrue("no bar left: $out", out.none { line -> line.any { it == '│' } })
    }

    @Test fun an_indent_without_a_frame_stays() {
        assertEquals(listOf("  1. Yes"), tilePictureLines("  1. Yes", 20))
    }

    @Test fun a_bar_inside_a_line_stays() {
        assertEquals(listOf("a │ b"), tilePictureLines("a │ b", 20))
    }

    @Test fun a_short_dash_is_not_a_rule() {
        assertEquals(listOf("- item"), tilePictureLines("- item", 20))
        assertEquals(listOf("-"), tilePictureLines("-", 20))
    }
}
