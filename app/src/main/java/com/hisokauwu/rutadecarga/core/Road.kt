package com.hisokauwu.rutadecarga.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** Resultado reutilizable de una consulta de punto más cercano en la carretera. */
class RoadHit {
    var found = false
    var index = 0          // índice de la muestra inicial del segmento más cercano
    var t = 0f             // posición dentro del segmento [0,1]
    var distance = Float.MAX_VALUE
    var y = 0f             // altura de la carretera en el punto proyectado
    var side = 0f          // >0 a la derecha del sentido de avance, <0 a la izquierda
}

/**
 * Carretera cerrada generada a partir de puntos de control con una spline Catmull-Rom,
 * re-muestreada a distancia constante y con perfil de altura suavizado.
 */
class Road(
    controlPoints: List<Pair<Float, Float>>,
    val halfWidth: Float,
    targetSpacing: Float,
    baseHeight: (Float, Float) -> Float,
) {
    val count: Int
    val spacing: Float
    val length: Float
    val xs: FloatArray
    val zs: FloatArray
    val ys: FloatArray
    val txs: FloatArray
    val tzs: FloatArray

    private val cellSize = 20f
    private val grid = HashMap<Int, IntArray>()

    init {
        // 1) Muestreo denso de la spline
        val n = controlPoints.size
        val steps = 120
        val dx = FloatArray(n * steps)
        val dz = FloatArray(n * steps)
        for (i in 0 until n) {
            val p0 = controlPoints[(i - 1 + n) % n]
            val p1 = controlPoints[i]
            val p2 = controlPoints[(i + 1) % n]
            val p3 = controlPoints[(i + 2) % n]
            for (s in 0 until steps) {
                val t = s.toFloat() / steps
                dx[i * steps + s] = catmull(p0.first, p1.first, p2.first, p3.first, t)
                dz[i * steps + s] = catmull(p0.second, p1.second, p2.second, p3.second, t)
            }
        }
        val dn = dx.size
        val cum = FloatArray(dn + 1)
        for (i in 0 until dn) {
            val j = (i + 1) % dn
            cum[i + 1] = cum[i] + length2(dx[j] - dx[i], dz[j] - dz[i])
        }
        length = cum[dn]
        count = (length / targetSpacing).toInt()
        spacing = length / count

        // 2) Re-muestreo por longitud de arco
        xs = FloatArray(count); zs = FloatArray(count)
        var seg = 0
        for (k in 0 until count) {
            val target = k * spacing
            while (seg < dn - 1 && cum[seg + 1] < target) seg++
            val segLen = cum[seg + 1] - cum[seg]
            val f = if (segLen > 1e-5f) (target - cum[seg]) / segLen else 0f
            val j = (seg + 1) % dn
            xs[k] = lerp(dx[seg], dx[j], f)
            zs[k] = lerp(dz[seg], dz[j], f)
        }

        // 3) Tangentes
        txs = FloatArray(count); tzs = FloatArray(count)
        for (k in 0 until count) {
            val a = (k - 1 + count) % count
            val b = (k + 1) % count
            val tx = xs[b] - xs[a]
            val tz = zs[b] - zs[a]
            val l = length2(tx, tz).coerceAtLeast(1e-5f)
            txs[k] = tx / l; tzs[k] = tz / l
        }

        // 4) Altura: sigue al terreno pero suavizada para pendientes razonables
        var h = FloatArray(count) { baseHeight(xs[it], zs[it]) }
        val radius = (100f / spacing).toInt().coerceAtLeast(1)
        repeat(3) {
            val out = FloatArray(count)
            var sum = 0f
            for (o in -radius..radius) sum += h[index(o)]
            for (k in 0 until count) {
                out[k] = sum / (2 * radius + 1)
                sum += h[index(k + radius + 1)] - h[index(k - radius)]
            }
            h = out
        }
        ys = h

        // 5) Rejilla espacial para búsquedas rápidas
        val tmp = HashMap<Int, ArrayList<Int>>()
        for (k in 0 until count) {
            tmp.getOrPut(cellKey(cellOf(xs[k]), cellOf(zs[k]))) { ArrayList() }.add(k)
        }
        for ((key, list) in tmp) grid[key] = list.toIntArray()
    }

    fun index(i: Int): Int = ((i % count) + count) % count

    private fun cellOf(v: Float) = floor(v / cellSize).toInt()
    private fun cellKey(cx: Int, cz: Int) = (cx + 2000) * 4096 + (cz + 2000)

    /** Busca el punto de la carretera más cercano a (x,z) dentro de [maxDist]. */
    fun nearest(x: Float, z: Float, maxDist: Float, out: RoadHit): RoadHit {
        out.found = false
        out.distance = Float.MAX_VALUE
        val r = (maxDist / cellSize).toInt() + 1
        val cx = cellOf(x)
        val cz = cellOf(z)
        var best = -1
        var bestD2 = maxDist * maxDist
        for (ix in cx - r..cx + r) for (iz in cz - r..cz + r) {
            val arr = grid[cellKey(ix, iz)] ?: continue
            for (k in arr) {
                val ddx = xs[k] - x
                val ddz = zs[k] - z
                val d2 = ddx * ddx + ddz * ddz
                if (d2 < bestD2) { bestD2 = d2; best = k }
            }
        }
        if (best < 0) return out
        // Proyección exacta sobre los dos segmentos adyacentes
        projectSegment(index(best - 1), x, z, out)
        val d1 = out.distance; val i1 = out.index; val t1 = out.t; val y1 = out.y; val s1 = out.side
        projectSegment(best, x, z, out)
        if (d1 < out.distance) {
            out.distance = d1; out.index = i1; out.t = t1; out.y = y1; out.side = s1
        }
        out.found = out.distance <= maxDist
        return out
    }

    private fun projectSegment(i: Int, x: Float, z: Float, out: RoadHit) {
        val j = index(i + 1)
        val ax = xs[i]; val az = zs[i]
        val sx = xs[j] - ax; val sz = zs[j] - az
        val l2 = sx * sx + sz * sz
        val t = if (l2 > 1e-6f) clamp(((x - ax) * sx + (z - az) * sz) / l2, 0f, 1f) else 0f
        val px = ax + sx * t
        val pz = az + sz * t
        val ex = x - px
        val ez = z - pz
        out.distance = sqrt(ex * ex + ez * ez)
        out.index = i
        out.t = t
        out.y = lerp(ys[i], ys[j], t)
        // lado: producto escalar con el vector derecha del segmento
        val rx = Dir.rightX(sx, sz)
        val rz = Dir.rightZ(sx, sz)
        out.side = ex * rx + ez * rz
    }

    /** Distancia a lo largo de la carretera entre dos índices (la más corta en el circuito). */
    fun roadDistance(a: Int, b: Int): Float {
        val d = abs(a - b)
        return min(d, count - d) * spacing
    }

    private fun catmull(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
            (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
    }
}
