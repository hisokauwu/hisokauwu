package com.hisokauwu.rutadecarga.render

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Construye geometría low-poly en una lista de floats intercalados:
 * posición (3), normal (3), color (3) = 9 floats por vértice. Sombreado plano.
 * No depende de Android, así que se puede probar en la JVM.
 */
class MeshBuilder(initialVertices: Int = 1024) {
    var data = FloatArray(initialVertices * STRIDE); private set
    var size = 0; private set
    val vertexCount get() = size / STRIDE

    companion object {
        const val STRIDE = 9
        fun r(c: Int) = ((c shr 16) and 0xFF) / 255f
        fun g(c: Int) = ((c shr 8) and 0xFF) / 255f
        fun b(c: Int) = (c and 0xFF) / 255f
        fun shade(c: Int, f: Float): Int {
            fun ch(v: Int) = (v * f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch((c shr 16) and 0xFF) shl 16) or (ch((c shr 8) and 0xFF) shl 8) or ch(c and 0xFF)
        }
    }

    fun toArray(): FloatArray = data.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
    }

    private fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, c: Int) {
        val d = data
        var i = size
        d[i++] = x; d[i++] = y; d[i++] = z
        d[i++] = nx; d[i++] = ny; d[i++] = nz
        d[i++] = r(c); d[i++] = g(c); d[i++] = b(c)
        size = i
    }

    /**
     * Triángulo con normal plana. La normal se orienta para apuntar en sentido contrario
     * al punto interior (ix,iy,iz), así no hay que preocuparse por el orden de vértices.
     */
    fun tri(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        color: Int,
        ix: Float, iy: Float, iz: Float,
    ) {
        ensure(3 * STRIDE)
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l < 1e-8f) return
        nx /= l; ny /= l; nz /= l
        val mx = (ax + bx + cx) / 3f - ix
        val my = (ay + by + cy) / 3f - iy
        val mz = (az + bz + cz) / 3f - iz
        if (nx * mx + ny * my + nz * mz < 0f) { nx = -nx; ny = -ny; nz = -nz }
        vertex(ax, ay, az, nx, ny, nz, color)
        vertex(bx, by, bz, nx, ny, nz, color)
        vertex(cx, cy, cz, nx, ny, nz, color)
    }

    /** Triángulo con normal hacia arriba (para suelos). */
    fun triUp(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, color: Int) =
        tri(ax, ay, az, bx, by, bz, cx, cy, cz, color, (ax + bx + cx) / 3f, minOf(ay, by, cy) - 100f, (az + bz + cz) / 3f)

    fun quadUp(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float, color: Int,
    ) {
        triUp(ax, ay, az, bx, by, bz, cx, cy, cz, color)
        triUp(ax, ay, az, cx, cy, cz, dx, dy, dz, color)
    }

    /**
     * Caja con centro (cx,cy,cz), semiejes (hx,hy,hz), girada [yaw] rad alrededor de Y.
     * Opcionalmente la cara superior con otro color.
     */
    fun box(
        cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float,
        color: Int, yaw: Float = 0f, topColor: Int = color,
    ) {
        val c = cos(yaw); val s = sin(yaw)
        val px = FloatArray(8); val py = FloatArray(8); val pz = FloatArray(8)
        for (i in 0 until 8) {
            val lx = if (i and 1 == 0) -hx else hx
            val ly = if (i and 2 == 0) -hy else hy
            val lz = if (i and 4 == 0) -hz else hz
            // Rotación sobre Y: (1,0,0)->(cos,0,-sin), (0,0,1)->(sin,0,cos)
            px[i] = cx + lx * c + lz * s
            py[i] = cy + ly
            pz[i] = cz - lx * s + lz * c
        }
        fun face(a: Int, b: Int, d: Int, e: Int, col: Int) {
            tri(px[a], py[a], pz[a], px[b], py[b], pz[b], px[d], py[d], pz[d], col, cx, cy, cz)
            tri(px[a], py[a], pz[a], px[d], py[d], pz[d], px[e], py[e], pz[e], col, cx, cy, cz)
        }
        val side = shade(color, 0.92f)
        face(0, 1, 3, 2, color)      // -z (trasera)
        face(4, 5, 7, 6, color)      // +z (frontal)
        face(0, 2, 6, 4, side)       // -x
        face(1, 3, 7, 5, side)       // +x
        face(2, 3, 7, 6, topColor)   // +y
        face(0, 1, 5, 4, shade(color, 0.6f)) // -y
    }

    /** Cilindro con eje X (ruedas). */
    fun cylinderX(cx: Float, cy: Float, cz: Float, radius: Float, halfWidth: Float, segments: Int, color: Int, capColor: Int) {
        for (i in 0 until segments) {
            val a0 = i * 2f * Math.PI.toFloat() / segments
            val a1 = (i + 1) * 2f * Math.PI.toFloat() / segments
            val y0 = cy + cos(a0) * radius; val z0 = cz + sin(a0) * radius
            val y1 = cy + cos(a1) * radius; val z1 = cz + sin(a1) * radius
            val xl = cx - halfWidth; val xr = cx + halfWidth
            tri(xl, y0, z0, xr, y0, z0, xr, y1, z1, color, cx, cy, cz)
            tri(xl, y0, z0, xr, y1, z1, xl, y1, z1, color, cx, cy, cz)
            tri(xl, cy, cz, xl, y0, z0, xl, y1, z1, capColor, cx + 1f, cy, cz)
            tri(xr, cy, cz, xr, y0, z0, xr, y1, z1, capColor, cx - 1f, cy, cz)
        }
    }

    /** Cono con base en (cx,by,cz). */
    fun cone(cx: Float, by: Float, cz: Float, radius: Float, height: Float, segments: Int, color: Int, phase: Float = 0f) {
        val top = by + height
        for (i in 0 until segments) {
            val a0 = phase + i * 2f * Math.PI.toFloat() / segments
            val a1 = phase + (i + 1) * 2f * Math.PI.toFloat() / segments
            val x0 = cx + cos(a0) * radius; val z0 = cz + sin(a0) * radius
            val x1 = cx + cos(a1) * radius; val z1 = cz + sin(a1) * radius
            val col = if (i % 2 == 0) color else shade(color, 0.9f)
            tri(x0, by, z0, x1, by, z1, cx, top, cz, col, cx, by + height * 0.3f, cz)
            tri(x0, by, z0, x1, by, z1, cx, by, cz, shade(color, 0.6f), cx, by + 1f, cz)
        }
    }

    /** Esfera low-poly (icosaedro aproximado con anillos), útil para copas redondas. */
    fun blob(cx: Float, cy: Float, cz: Float, radius: Float, color: Int) {
        val rings = 3; val segs = 6
        for (r in 0 until rings) {
            val p0 = Math.PI.toFloat() * r / rings
            val p1 = Math.PI.toFloat() * (r + 1) / rings
            for (s in 0 until segs) {
                val t0 = 2f * Math.PI.toFloat() * s / segs + r * 0.5f
                val t1 = 2f * Math.PI.toFloat() * (s + 1) / segs + r * 0.5f
                fun px(p: Float, t: Float) = cx + sin(p) * cos(t) * radius
                fun py(p: Float) = cy + cos(p) * radius * 0.85f
                fun pz(p: Float, t: Float) = cz + sin(p) * sin(t) * radius
                val col = if ((r + s) % 2 == 0) color else shade(color, 0.88f)
                tri(px(p0, t0), py(p0), pz(p0, t0), px(p1, t0), py(p1), pz(p1, t0), px(p1, t1), py(p1), pz(p1, t1), col, cx, cy, cz)
                tri(px(p0, t0), py(p0), pz(p0, t0), px(p1, t1), py(p1), pz(p1, t1), px(p0, t1), py(p0), pz(p0, t1), col, cx, cy, cz)
            }
        }
    }
}
