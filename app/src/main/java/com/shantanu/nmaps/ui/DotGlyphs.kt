package com.shantanu.nmaps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.shantanu.nmaps.ui.theme.LocalPalette
import kotlin.math.min

/**
 * A bitmap icon drawn on a dot grid. `#` is a lit dot, `r` a lit accent (red) dot, `.` an unlit dot.
 * Every icon in the app is one of these, so the whole UI speaks the same dot-matrix language.
 */
class DotGlyph(private val rows: List<String>) {
    val width = rows.maxOf { it.length }
    val height = rows.size

    fun cell(x: Int, y: Int): Char = rows[y].getOrElse(x) { '.' }

    fun mirrored() = DotGlyph(rows.map { it.padEnd(width, '.').reversed() })
}

object Glyphs {
    val Search = DotGlyph(
        listOf(
            ".###...",
            "#...#..",
            "#...#..",
            "#...#..",
            ".####..",
            ".....#.",
            "......#",
        )
    )
    val Locate = DotGlyph(
        listOf(
            "...#...",
            "..###..",
            ".#...#.",
            "##.r.##",
            ".#...#.",
            "..###..",
            "...#...",
        )
    )
    val North = DotGlyph(
        listOf(
            "...r...",
            "..rrr..",
            ".rrrrr.",
            "...#...",
            "...#...",
            "...#...",
            "...#...",
        )
    )
    val Close = DotGlyph(
        listOf(
            "#.....#",
            ".#...#.",
            "..#.#..",
            "...#...",
            "..#.#..",
            ".#...#.",
            "#.....#",
        )
    )
    val Back = DotGlyph(
        listOf(
            "...#...",
            "..#....",
            ".#.....",
            "#######",
            ".#.....",
            "..#....",
            "...#...",
        )
    )
    val Star = DotGlyph(
        listOf(
            "...#...",
            "...#...",
            "#######",
            ".#####.",
            "..###..",
            ".##.##.",
            ".#...#.",
        )
    )
    val StarOn = DotGlyph(
        listOf(
            "...r...",
            "...r...",
            "rrrrrrr",
            ".rrrrr.",
            "..rrr..",
            ".rr.rr.",
            ".r...r.",
        )
    )
    val Share = DotGlyph(
        listOf(
            "...#...",
            "..###..",
            ".#.#.#.",
            "...#...",
            "#..#..#",
            "#.....#",
            "#######",
        )
    )
    val Pin = DotGlyph(
        listOf(
            ".###.",
            "#...#",
            "#.r.#",
            "#...#",
            ".#.#.",
            "..#..",
        )
    )
    val Home = DotGlyph(
        listOf(
            "...#...",
            "..#.#..",
            ".#...#.",
            "#######",
            ".#...#.",
            ".#.r.#.",
            ".#.r.#.",
        )
    )
    val Work = DotGlyph(
        listOf(
            "..###..",
            "..#.#..",
            "#######",
            "#.....#",
            "###r###",
            "#.....#",
            "#######",
        )
    )
    /** The app's pin, as on the launcher icon. */
    val Logo = DotGlyph(
        listOf(
            "...###...",
            ".#######.",
            "###...###",
            "##..r..##",
            "###...###",
            ".#######.",
            "..#####..",
            "...###...",
            "....#....",
        )
    )

    /** Vertical ⋮ for option menus. */
    val More = DotGlyph(listOf("..#..", ".....", "..#..", ".....", "..#.."))
    /** Directions: a turn arrow inside a diamond, like the classic directions sign. */
    val Directions = DotGlyph(
        listOf(
            "....#....",
            "...#.#...",
            "..#..r#..",
            ".#.rrrr#.",
            "#..r..r.#",
            ".#.r...#.",
            "..#r..#..",
            "...#.#...",
            "....#....",
        )
    )
    val Menu = DotGlyph(listOf("#.#.#", ".....", "#.#.#", ".....", "#.#.#"))
    val Layers = DotGlyph(
        listOf(
            "...#...",
            ".##.##.",
            "#.....#",
            ".##.##.",
            "#.###.#",
            ".##.##.",
            "...#...",
        )
    )
    val Download = DotGlyph(
        listOf(
            "...#...",
            "...#...",
            ".#.#.#.",
            "..###..",
            "...#...",
            ".......",
            "#######",
        )
    )
    val Route = DotGlyph(
        listOf(
            "r......",
            ".#.....",
            "..###..",
            ".....#.",
            "..###..",
            ".#.....",
            "..####r",
        )
    )
    val Person = DotGlyph(
        listOf(
            "..###..",
            "..###..",
            "..###..",
            ".......",
            ".#####.",
            "#######",
            "#######",
        )
    )
    val Gear = DotGlyph(
        listOf(
            "..#.#..",
            ".#####.",
            "##...##",
            ".#.r.#.",
            "##...##",
            ".#####.",
            "..#.#..",
        )
    )
    val Parking = DotGlyph(
        listOf(
            "#####..",
            "#....#.",
            "#....#.",
            "#####..",
            "#......",
            "#......",
            "#......",
        )
    )
    val Minus = DotGlyph(listOf(".......", ".......", ".......", "#######", ".......", ".......", "......."))
    val Mic = DotGlyph(
        listOf(
            "..###..",
            "..###..",
            "..###..",
            "#.###.#",
            "#.....#",
            ".#####.",
            "...#...",
        )
    )
    val Plus = DotGlyph(
        listOf(
            "...#...",
            "...#...",
            "...#...",
            "#######",
            "...#...",
            "...#...",
            "...#...",
        )
    )
    val Forward = Back.mirrored()
    val Warning = DotGlyph(
        listOf(
            "....r....",
            "...r.r...",
            "...r#r...",
            "..r.#.r..",
            "..r.#.r..",
            ".r.....r.",
            ".r..#..r.",
            "r.......r",
            "rrrrrrrrr",
        )
    )

    /** Locate button while the map turns with the compass. */
    val Heading = DotGlyph(
        listOf(
            "...#...",
            "..###..",
            "..###..",
            ".#####.",
            ".#####.",
            "##...##",
            "#.....#",
        )
    )
    val Record = DotGlyph(
        listOf(
            "..###..",
            ".#...#.",
            "#.rrr.#",
            "#.rrr.#",
            "#.rrr.#",
            ".#...#.",
            "..###..",
        )
    )
    val Clock = DotGlyph(
        listOf(
            "..###..",
            ".#...#.",
            "#..#..#",
            "#..##.#",
            "#.....#",
            ".#...#.",
            "..###..",
        )
    )

    // Turn arrows on a 9x9 matrix, used in the navigation banner.
    val Straight = DotGlyph(
        listOf(
            "....#....",
            "...###...",
            "..#.#.#..",
            ".#..#..#.",
            "....#....",
            "....#....",
            "....#....",
            "....#....",
            "....#....",
        )
    )
    val Right = DotGlyph(
        listOf(
            ".........",
            "......#..",
            ".......#.",
            "..#######",
            "..#....#.",
            "..#...#..",
            "..#......",
            "..#......",
            "..#......",
        )
    )
    val Left = Right.mirrored()
    val SlightRight = DotGlyph(
        listOf(
            "....#####",
            ".......##",
            "......#.#",
            ".....#..#",
            "....#....",
            "...#.....",
            "...#.....",
            "...#.....",
            "...#.....",
        )
    )
    val SlightLeft = SlightRight.mirrored()
    val UTurn = DotGlyph(
        listOf(
            "..####...",
            ".#....#..",
            ".#....#..",
            ".#....#..",
            ".#....#..",
            "###...#..",
            ".#....#..",
            "......#..",
            "......#..",
        )
    )
    val Roundabout = DotGlyph(
        listOf(
            "....#....",
            "...###...",
            "....#....",
            "..##.##..",
            ".#.....#.",
            ".#.....#.",
            "..##.##..",
            "....#....",
            "....#....",
        )
    )
    val Edit = DotGlyph(
        listOf(
            ".....#.",
            "....#.#",
            "...#.#.",
            "..#.#..",
            ".#.#...",
            "##.....",
            "#......",
        )
    )
    val Steps = DotGlyph(
        listOf(
            ".##....",
            ".##..##",
            "..#..##",
            ".##...#",
            "..#..##",
            ".....##",
            ".....#.",
        )
    )
    val Camera = DotGlyph(
        listOf(
            "..##...",
            ".......",
            "#######",
            "#..#..#",
            "#.#r#.#",
            "#..#..#",
            "#######",
        )
    )
    val Terrain = DotGlyph(
        listOf(
            ".......",
            "...#...",
            "..#.#..",
            ".#...#.",
            "#.#.#.#",
            ".#.#.#.",
            "#######",
        )
    )
    val Train = DotGlyph(
        listOf(
            ".#####.",
            "#.....#",
            "#.#.#.#",
            "#.....#",
            "#######",
            ".#...#.",
            "#.....#",
        )
    )
    val Bike = DotGlyph(
        listOf(
            "...#...",
            "..###..",
            "..#.#..",
            ".#...#.",
            "#.#.#.#",
            "#.#.#.#",
            ".#...#.",
        )
    )
    val Building = DotGlyph(
        listOf(
            "..###..",
            "..#.#..",
            ".##.##.",
            ".#.#.#.",
            "##.#.##",
            "#.#.#.#",
            "#######",
        )
    )
    val Sun = DotGlyph(
        listOf(
            "#..#..#",
            ".......",
            "..###..",
            "#.#r#.#",
            "..###..",
            ".......",
            "#..#..#",
        )
    )
    val Moon = DotGlyph(
        listOf(
            "..###..",
            ".#...#.",
            "#.....#",
            "#..#..#",
            "#..##..",
            ".#.##..",
            "..###..",
        )
    )
    val Cloud = DotGlyph(
        listOf(
            ".......",
            "...##..",
            "..#..#.",
            ".#....#",
            "#......",
            "#......",
            ".######",
        )
    )
    val Rain = DotGlyph(
        listOf(
            "...##..",
            "..#..#.",
            ".#....#",
            ".######",
            ".......",
            ".r.r.r.",
            "r.r.r..",
        )
    )
    val Snow = DotGlyph(
        listOf(
            "...#...",
            "#..#..#",
            ".#.#.#.",
            "..###..",
            ".#.#.#.",
            "#..#..#",
            "...#...",
        )
    )
    val Storm = DotGlyph(
        listOf(
            "...##..",
            "..#..#.",
            ".#....#",
            ".######",
            "...rr..",
            "..rr...",
            "...r...",
        )
    )
    val Arrive = DotGlyph(
        listOf(
            "...###...",
            ".##...##.",
            ".#.....#.",
            "#...r...#",
            "#..rrr..#",
            "#...r...#",
            ".#.....#.",
            ".##...##.",
            "...###...",
        )
    )
}

/**
 * Draws a [DotGlyph]. [offColor] paints the unlit dots too, which gives the full matrix look.
 * [reveal] (0..1) lights dots bottom-up, used when a new turn appears.
 */
@Composable
fun DotIcon(
    glyph: DotGlyph,
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.content,
    accent: Color = LocalPalette.current.red,
    offColor: Color? = null,
    reveal: Float = 1f,
) {
    Canvas(modifier) {
        val cell = min(size.width / glyph.width, size.height / glyph.height)
        val radius = cell * 0.38f
        val ox = (size.width - cell * glyph.width) / 2f + cell / 2f
        val oy = (size.height - cell * glyph.height) / 2f + cell / 2f
        for (y in 0 until glyph.height) {
            // Rows light from the bottom up as reveal goes 0 -> 1.
            val rowLit = (glyph.height - y).toFloat() / glyph.height <= reveal + 0.0001f
            for (x in 0 until glyph.width) {
                val c = glyph.cell(x, y)
                val paint = when {
                    c == '#' && rowLit -> color
                    c == 'r' && rowLit -> accent
                    else -> offColor
                } ?: continue
                drawCircle(paint, radius, Offset(ox + x * cell, oy + y * cell))
            }
        }
    }
}
