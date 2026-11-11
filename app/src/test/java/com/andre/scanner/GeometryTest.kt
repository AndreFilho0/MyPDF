package com.andre.scanner

import com.andre.scanner.model.Pt
import com.andre.scanner.processing.Geometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    private val tl = Pt(10f, 20f)
    private val tr = Pt(200f, 15f)
    private val br = Pt(210f, 300f)
    private val bl = Pt(5f, 290f)

    @Test fun ordersShuffledCorners() {
        for (perm in listOf(listOf(br, tl, bl, tr), listOf(bl, br, tr, tl), listOf(tr, bl, tl, br))) {
            assertEquals(listOf(tl, tr, br, bl), Geometry.orderCorners(perm))
        }
    }

    @Test fun ordersSheetRotatedNear45Degrees() {
        // Losango: o truque de soma/diferença escolheria o mesmo ponto duas vezes.
        val top = Pt(100f, 0f); val right = Pt(200f, 99f); val bottom = Pt(101f, 200f); val left = Pt(0f, 101f)
        val ordered = Geometry.orderCorners(listOf(bottom, left, top, right))
        assertEquals(4, ordered.toSet().size)
        assertEquals(top, ordered[0])
        assertEquals(listOf(top, right, bottom, left), ordered)
    }

    @Test fun convexity() {
        assertTrue(Geometry.isConvex(listOf(tl, tr, br, bl)))
        assertFalse(Geometry.isConvex(listOf(tl, br, tr, bl)))   // cruzado
        assertFalse(Geometry.isConvex(listOf(tl, tr, Pt(100f, 50f), bl)))  // côncavo
    }

    @Test fun areaAndOrientation() {
        val full = Geometry.fullImage(300, 400)
        assertEquals(120_000.0, Geometry.area(full), 0.01)
        assertFalse(Geometry.isLandscape(full))
        assertTrue(Geometry.isLandscape(Geometry.fullImage(400, 300)))
    }
}
