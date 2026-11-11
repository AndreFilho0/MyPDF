package com.andre.scanner.processing

import com.andre.scanner.model.Pt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Geometria pura (sem OpenCV nem Android), para poder testar na JVM. */
object Geometry {

    /**
     * Ordena 4 pontos como sup. esq., sup. dir., inf. dir., inf. esq.
     *
     * Ordena pelo ângulo em volta do centro e começa pelo ponto de menor x + y. Diferente do
     * truque só com soma/diferença, funciona mesmo com a folha girada perto de 45°.
     */
    fun orderCorners(p: List<Pt>): List<Pt> {
        require(p.size == 4) { "esperava 4 pontos, veio ${p.size}" }
        val cx = p.sumOf { it.x.toDouble() } / 4
        val cy = p.sumOf { it.y.toDouble() } / 4
        // Com y para baixo, ângulo crescente = sentido horário na tela.
        val clockwise = p.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = clockwise.indices.minBy { clockwise[it].x + clockwise[it].y }
        return List(4) { clockwise[(start + it) % 4] }
    }

    fun fullImage(w: Int, h: Int): List<Pt> = listOf(
        Pt(0f, 0f), Pt(w.toFloat(), 0f), Pt(w.toFloat(), h.toFloat()), Pt(0f, h.toFloat()),
    )

    fun area(q: List<Pt>): Double {
        var s = 0.0
        for (i in q.indices) {
            val a = q[i]; val b = q[(i + 1) % q.size]
            s += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return abs(s) / 2
    }

    fun isConvex(q: List<Pt>): Boolean {
        if (q.size != 4) return false
        var sign = 0
        for (i in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]; val c = q[(i + 2) % 4]
            val cross = (b.x - a.x).toDouble() * (c.y - b.y) - (b.y - a.y).toDouble() * (c.x - b.x)
            if (abs(cross) < 1e-6) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** true quando a folha aparece deitada na foto (mais larga que alta). */
    fun isLandscape(q: List<Pt>): Boolean {
        val (tl, tr, br, bl) = q
        val w = (dist(tl, tr) + dist(bl, br)) / 2
        val h = (dist(tl, bl) + dist(tr, br)) / 2
        return w > h
    }

    fun dist(a: Pt, b: Pt) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    fun scale(q: List<Pt>, f: Float) = q.map { Pt(it.x * f, it.y * f) }
}
