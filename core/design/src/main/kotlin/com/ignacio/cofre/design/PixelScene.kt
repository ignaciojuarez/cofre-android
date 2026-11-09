package com.ignacio.cofre.design

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

// §2 #18 PixelScene: Cofre's seeded moonlit lake. Pixel-for-pixel the same art as iOS's PixelSceneArt.swift.

/** The moonlit lake for [seed], [width]×[height] art pixels, filling and cropping to its bounds. Decorative. */
@Composable
public fun PixelScene(
    seed: Int,
    modifier: Modifier = Modifier,
    width: Int = 120,
    height: Int = 80,
) {
    val image = remember(seed, width, height) {
        Bitmap.createBitmap(SceneArt.render(seed, width, height).map { it or 0xff000000.toInt() }.toIntArray(), width, height, Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    }
    Image(
        image,
        contentDescription = null,
        modifier = modifier.clipToBounds(),
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.None,
    )
}

/**
 * Pure pixel math, deterministic per seed, one array entry (0xRRGGBB) per art pixel. The top stays dark for white
 * titles, the moon sits near the middle so narrow crops keep it, and the shore sits near 0.86 of the height.
 */
internal object SceneArt {
    fun render(seed: Int, w: Int, h: Int): IntArray {
        val p = PixelArtColors.Lake
        val c = SceneCanvas(w, h, seed)
        val r = Mulberry32(seed)
        val horizon = c.ph(0.6)
        val shore = c.ph(0.86)
        c.gradient(0 until horizon, p.sky, curve = 1.2)
        c.stars(r, 0 until c.ph(0.45), count = w * h / 110)

        // The moon sits just off center on a random side; the cabin takes the other side.
        val side = if (r.chance(0.5)) 1 else -1
        val m = maxOf(6, c.ph(0.16))
        val mx = w / 2 + side * r.int(w / 24..maxOf(w / 24, w / 10)) - m / 2
        val my = c.ph(0.1 + r.next() * 0.05)
        val mcx = mx.toDouble() + m.toDouble() / 2
        val mcy = my.toDouble() + m.toDouble() / 2
        c.glow(mcx, mcy, m * 1.5, p.halo, strength = 0.75) // before the trees, behind them

        // Cube-tree forest in two rows, the near one rimmed on the side facing the moon.
        for (row in 0 until 2) {
            val colors = p.forest[row]
            val ground = horizon - if (row == 0) 2 else 0
            c.fill(0, ground - 1, w, horizon - ground + 2, colors.first)
            var x = -r.int(0..4)
            while (x < w) {
                val s = if (row == 0) {
                    r.int(maxOf(3, c.ph(0.04))..maxOf(3, c.ph(0.08)))
                } else {
                    r.int(maxOf(4, c.ph(0.06))..maxOf(4, c.ph(0.12)))
                }
                val trunk = r.int(1..maxOf(2, s * 2 / 3)) + if (row == 0) c.ph(0.04) else 0
                // Oak-like: a wide canopy with a narrower step on top.
                val top = ground - trunk - s
                val cap = maxOf(1, s / 3)
                val rim = if (x + s / 2 < mx + m / 2) x + s - 1 else x
                c.fill(x + s / 2, ground - trunk, 1, trunk, colors.first)
                c.fill(x, top, s, s, colors.first)
                c.fill(x + 1, top - cap, s - 2, cap, colors.first)
                c.fill(x, top, s, 1, colors.second)
                c.fill(x + 1, top - cap, s - 2, 1, colors.second)
                c.fill(rim, top, 1, s * 2 / 3, colors.second)
                x += s + r.int(if (row == 0) 0..3 else 1..5)
            }
        }

        // Cabin with a lit window, its warm light spilling onto the trees around it.
        val cw = maxOf(7, c.ph(0.12))
        val ch = maxOf(5, c.ph(0.07))
        val base = horizon - 1
        val cabinX = w / 2 - side * r.int(w / 10..maxOf(w / 10, w / 5)) - cw / 2
        val winW = if (cw >= 9) 3 else 2
        val winX = cabinX + (cw - winW) / 2
        val winY = base - ch + 1 + ch / 4
        c.glow(winX.toDouble() + 1, winY.toDouble() + 1, cw * 1.2, p.glow, strength = 0.85)
        c.fill(cabinX, base - ch, cw, ch + 1, p.cabin.first)
        for (k in 0 until cw / 2 + 1) c.fill(cabinX - 1 + k, base - ch - 1 - k, cw + 2 - 2 * k, 1, p.cabin.second)
        c.fill(winX - 1, winY + 2, winW + 2, 1, p.glow.first) // sill light
        c.fill(winX, winY, winW, 2, p.window.second)
        c.fill(winX, winY, winW - 1, 1, p.window.first)

        // Lake: the sky and forest mirrored, darkened with depth, broken by ripples.
        for (y in horizon until h) { // under the bank too, so its uneven edge never shows a gap
            val sy = horizon - 1 - (y - horizon) * 3 / 2
            val wobble = (c.hash(y, 7) % 3u).toInt() - 1
            val depth = minOf(1.0, (y - horizon).toDouble() / maxOf(1, shore - horizon))
            for (x in 0 until w) c.set(x, y, SceneCanvas.mix(c.at(x + wobble, maxOf(0, sy)), p.WATER, 0.25 + 0.45 * depth))
            if (c.hash(y, 3) % 3u == 0u) {
                val rx = (c.hash(y, 5) % w.toUInt()).toInt()
                val len = 3 + (c.hash(y, 9) % 8u).toInt()
                for (k in 0 until len) c.set(rx + k, y, p.RIPPLE)
            }
        }
        c.fill(0, horizon, w, 1, p.RIPPLE)

        // The moon's face, drawn after the mirror so only its path shows in the water.
        c.fill(mx, my, m, m, p.moon.first)
        c.fill(mx + m - 1, my + 1, 1, m - 1, p.moon.second)
        c.fill(mx + 1, my + m - 1, m - 1, 1, p.moon.second)
        repeat(3) {
            val sx = mx + r.int(1..maxOf(1, m - 4))
            val sy = my + r.int(1..maxOf(1, m - 4))
            c.fill(sx, sy, 2, 2, p.moon.second)
        }

        // Moon path: broken bars that widen and dim toward the shore, bright at the core.
        for (y in (horizon + 1) until shore) {
            if (c.hash(y, 17) % 4u == 0u || (y - horizon) % 2 != 1) continue
            val t = (y - horizon).toDouble() / maxOf(1, shore - horizon)
            val spread = m * (0.5 + 0.9 * t)
            val jitter = (c.hash(y, 11) % 3u).toInt() - 1
            val len = maxOf(1, (spread * (0.45 + (c.hash(y, 13) % 6u).toDouble() * 0.11)).toInt())
            val x0 = mcx.toInt() - len / 2 + jitter
            c.fill(x0, y, len, 1, p.glint[minOf(3, 1 + (t * 3).toInt())])
            if (len > 2) c.fill(x0 + 1, y, len - 2, 1, p.glint[minOf(2, (t * 3).toInt())])
        }
        // Window light on the water.
        for (y in horizon + 2 until minOf(shore, horizon + c.ph(0.18)) step 2) {
            if (c.hash(y, 19) % 3u == 0u) continue
            val near = y - horizon < c.ph(0.08)
            val jitter = (c.hash(y, 23) % 3u).toInt() - 1
            c.fill(winX + jitter, y, if (near) winW + 1 else winW, 1, if (near) p.window.second else p.glow.second)
        }

        // Shore with reeds and fireflies.
        val bank = c.plateaus(r, base = shore, range = c.ph(0.03), widths = 5..14)
        for (x in 0 until w) c.column(x, bank[x], h, p.shore.first, p.shore.second)
        repeat(maxOf(4, w / 10)) {
            val x = r.int(0..w - 1)
            val tall = r.int(2..maxOf(3, c.ph(0.08)))
            for (k in 1..tall) c.set(x, bank[x] - k, p.reed.first)
            if (r.chance(0.5)) c.fill(x, bank[x] - tall - 1, 1, 2, p.reed.second)
        }
        repeat(maxOf(3, w / 30)) {
            val fx = r.int(0..w)
            val fy = r.int(c.ph(0.66)..c.ph(0.92))
            c.set(fx, fy, p.window.second)
        }
        return c.px
    }
}

internal class SceneCanvas(val w: Int, val h: Int, private val seed: Int) {
    val px = IntArray(w * h)

    /** Reads clamp to the edge; writes outside are dropped. */
    fun at(x: Int, y: Int): Int = px[minOf(h - 1, maxOf(0, y)) * w + minOf(w - 1, maxOf(0, x))]

    fun set(x: Int, y: Int, c: Int) {
        if (x in 0 until w && y in 0 until h) px[y * w + x] = c
    }

    fun fill(x: Int, y: Int, fw: Int, fh: Int, c: Int) {
        for (yy in maxOf(0, y) until minOf(h, y + fh)) for (xx in maxOf(0, x) until minOf(w, x + fw)) px[yy * w + xx] = c
    }

    /** A fraction of the height, in pixels (rounded half away from zero, like Swift). */
    fun ph(f: Double): Int = swiftRound(h * f)

    /** Stable per-seed noise for textures, so they don't consume the generator. */
    fun hash(a: Int, b: Int): UInt {
        var x = (a.toUInt() * 0x9E3779B1u) xor (b.toUInt() * 0x85EBCA77u) xor seed.toUInt()
        x = x xor (x shr 15)
        x *= 0x2C1B3C6Du
        x = x xor (x shr 12)
        return x
    }

    /** Dithered pick from a color ramp at [t] in 0..<1. */
    fun ramp(colors: IntArray, t: Double, x: Int, y: Int): Int {
        val v = t * (colors.size - 1)
        val i = v.toInt()
        return if (dither(x, y, v - i)) colors[minOf(i + 1, colors.size - 1)] else colors[i]
    }

    fun gradient(rows: IntRange, colors: IntArray, curve: Double) {
        val count = rows.last - rows.first + 1
        for (y in rows) {
            val t = ((y - rows.first).toDouble() / maxOf(1, count)).pow(curve)
            for (x in 0 until w) set(x, y, ramp(colors, t, x, y))
        }
    }

    fun stars(r: Mulberry32, rows: IntRange, count: Int) {
        val upper = rows.last + 1
        repeat(count) {
            val x = r.int(0..w)
            val y = r.int(rows.first..maxOf(rows.first, upper))
            set(x, y, if (r.next() > 0.75) PixelArtColors.STAR_BRIGHT else PixelArtColors.STAR_DIM)
        }
    }

    /** Blocky skyline: flat runs of random width, each at a random height around [base]. */
    fun plateaus(r: Mulberry32, base: Int, range: Int, widths: IntRange): IntArray {
        val out = ArrayList<Int>(w + widths.last)
        var y = base
        while (out.size < w) {
            val step = maxOf(1, range / 2)
            y = maxOf(base - range, minOf(base + range, y + r.int(-step..step)))
            repeat(r.int(widths)) { out += y }
        }
        return out.take(w).toIntArray()
    }

    fun column(x: Int, top: Int, bottom: Int, fill: Int, edge: Int) {
        for (y in maxOf(0, top) until minOf(h, bottom)) set(x, y, fill)
        set(x, top, edge)
    }

    /** A soft light: a dithered disk, denser toward the middle, in an outer and an inner color. */
    fun glow(cx: Double, cy: Double, radius: Double, c: Pair<Int, Int>, strength: Double) {
        val r = ceil(radius).toInt()
        for (y in (cy.toInt() - r)..(cy.toInt() + r)) {
            for (x in (cx.toInt() - r)..(cx.toInt() + r)) {
                val dx = x - cx
                val dy = y - cy
                val f = (1 - sqrt(dx * dx + dy * dy) / radius) * strength
                if (f > 0 && dither(x, y, f)) set(x, y, if (f > 0.45) c.second else c.first)
            }
        }
    }

    companion object {
        private val bayer = doubleArrayOf(0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0)

        /** Ordered dither: true for about [f] of the pixels. */
        fun dither(x: Int, y: Int, f: Double): Boolean = f * 16 > bayer[(y and 3) * 4 + (x and 3)]

        fun mix(a: Int, b: Int, t: Double): Int {
            fun ch(s: Int): Int {
                val p = ((a shr s) and 0xff).toDouble()
                val q = ((b shr s) and 0xff).toDouble()
                return swiftRound(p + (q - p) * t) shl s
            }
            return ch(16) or ch(8) or ch(0)
        }

        /** Swift's `rounded()`: half away from zero. */
        fun swiftRound(v: Double): Int = (if (v < 0) -(abs(v) + 0.5).toLong() else (v + 0.5).toLong()).toInt()
    }
}
