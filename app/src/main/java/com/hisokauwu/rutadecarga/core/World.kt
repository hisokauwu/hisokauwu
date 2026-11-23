package com.hisokauwu.rutadecarga.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class Depot(
    val id: Int,
    val name: String,
    val x: Float,
    val z: Float,
    val y: Float,
    val roadIndex: Int,
    /** Rumbo de la carretera junto al depósito. */
    val heading: Float,
    /** Lado de la carretera en el que está (+1 derecha, -1 izquierda). */
    val side: Float,
    val color: Int,
) {
    val radius = 14f
}

class Tree(val x: Float, val y: Float, val z: Float, val scale: Float, val kind: Int)

/** Obstáculo sólido: círculo (r > 0, hx=hz=0) o caja orientada (hx, hz > 0). */
class Obstacle(
    val x: Float,
    val z: Float,
    val radius: Float,
    val hx: Float = 0f,
    val hz: Float = 0f,
    val yaw: Float = 0f,
) {
    val isBox get() = hx > 0f
    /** Radio envolvente para descartes rápidos. */
    val bound = if (hx > 0f) sqrt(hx * hx + hz * hz) else radius
}

class Building(
    val x: Float, val y: Float, val z: Float,
    val hx: Float, val hy: Float, val hz: Float,
    val yaw: Float, val color: Int, val roofColor: Int,
    /** Lado local (±1 en X) donde están los portones; 0 = sin portones. */
    val doorSide: Float = 0f,
)

/** Mundo del juego: terreno, carretera, depósitos y decorado. Todo determinista. */
class World(seed: Long = 1977L) {
    val mapRadius = 700f
    val road: Road
    val depots: List<Depot>
    val trees: List<Tree>
    val buildings: List<Building>
    val obstacles: List<Obstacle>

    private val hit = RoadHit()

    init {
        val cps = listOf(
            0f to -520f, 250f to -480f, 470f to -330f, 540f to -80f, 430f to 120f,
            520f to 330f, 380f to 520f, 120f to 470f, -60f to 300f, -250f to 480f,
            -480f to 420f, -540f to 150f, -420f to -60f, -520f to -280f, -330f to -470f,
        )
        road = Road(cps.map { it.first * 0.88f to it.second * 0.88f }, halfWidth = 7f, targetSpacing = 2f, baseHeight = ::baseHeight)

        // Depósitos repartidos por el circuito
        val names = listOf("Puerto", "Aserradero", "Granja", "Fábrica", "Mina", "Ciudad")
        val colors = listOf(0xFF3E7CB1.toInt(), 0xFF8B5A2B.toInt(), 0xFF6BA34A.toInt(),
            0xFF9C9C9C.toInt(), 0xFF7A6248.toInt(), 0xFFC9A227.toInt())
        val dl = ArrayList<Depot>()
        for (i in names.indices) {
            val idx = road.index(road.count * i / names.size + road.count / 20)
            val fx = road.txs[idx]; val fz = road.tzs[idx]
            val rx = Dir.rightX(fx, fz); val rz = Dir.rightZ(fx, fz)
            val off = road.halfWidth + 22f
            // Elige el lado que no quede pegado a otro tramo de carretera
            var side = 1f
            val cxR = road.xs[idx] + rx * off; val czR = road.zs[idx] + rz * off
            road.nearest(cxR, czR, 40f, hit)
            if (hit.found && hit.distance < off - 4f) side = -1f
            val cx = road.xs[idx] + rx * off * side
            val cz = road.zs[idx] + rz * off * side
            dl.add(Depot(i, names[i], cx, cz, road.ys[idx], idx, atan2(fx, fz), side, colors[i]))
        }
        depots = dl

        val rnd = Random(seed)
        val bl = ArrayList<Building>()
        val obs = ArrayList<Obstacle>()
        // Naves junto a cada depósito
        for (d in depots) {
            val fx = sin(d.heading); val fz = cos(d.heading)
            val rx = Dir.rightX(fx, fz) * d.side; val rz = Dir.rightZ(fx, fz) * d.side
            val bx = d.x + rx * 28f
            val bz = d.z + rz * 28f
            val b = Building(bx, d.y, bz, 8f, 5f + rnd.nextFloat() * 2f, 14f, d.heading,
                d.color, 0xFF5A5A60.toInt(), doorSide = d.side)
            bl.add(b)
            obs.add(Obstacle(bx, bz, 0f, b.hx, b.hz, b.yaw))
            // Pequeña caseta
            val sx = d.x + fx * 15f + rx * 14f
            val sz = d.z + fz * 15f + rz * 14f
            val s = Building(sx, d.y, sz, 2.5f, 1.6f, 2.5f, d.heading, 0xFFE8E2D0.toInt(), 0xFFB03A2E.toInt())
            bl.add(s)
            obs.add(Obstacle(sx, sz, 0f, s.hx, s.hz, s.yaw))
        }
        buildings = bl

        // Árboles
        val tl = ArrayList<Tree>()
        var attempts = 0
        while (tl.size < 900 && attempts < 20000) {
            attempts++
            val a = rnd.nextFloat() * TWO_PI
            val r = sqrt(rnd.nextFloat()) * 660f
            val x = cos(a) * r
            val z = sin(a) * r
            road.nearest(x, z, 16f, hit)
            if (hit.found) continue
            if (depots.any { length2(it.x - x, it.z - z) < 70f }) continue
            // Agrupa los árboles en bosques usando ruido
            val forest = sin(x * 0.013f + 1.7f) * cos(z * 0.011f - 0.4f) + 0.35f * sin(x * 0.041f) * sin(z * 0.037f)
            if (forest < 0.05f && rnd.nextFloat() > 0.12f) continue
            val sc = 0.8f + rnd.nextFloat() * 0.8f
            val kind = if (heightAt(x, z) > 30f || rnd.nextFloat() < 0.6f) 0 else 1
            tl.add(Tree(x, heightAt(x, z), z, sc, kind))
            obs.add(Obstacle(x, z, 0.45f * sc))
        }
        trees = tl
        obstacles = obs
    }

    /** Relieve base del terreno, sin carretera. */
    fun baseHeight(x: Float, z: Float): Float {
        var h = 14f * sin(x * 0.0062f + 0.5f) * cos(z * 0.0071f) +
            6f * sin(x * 0.017f + 1.3f) * sin(z * 0.015f + 0.7f) +
            2f * sin(x * 0.051f) * cos(z * 0.047f + 2f)
        val r = sqrt(x * x + z * z)
        if (r > 610f) {
            val e = r - 610f
            h += e * e * 0.006f
        }
        return h
    }

    /** Peso [0,1] de "zona aplanada" (carretera o depósito) calculado en la última llamada a heightAt. */
    var lastFlatWeight = 0f
        private set

    /** Altura física del suelo en (x,z): terreno con carretera y explanadas integradas. */
    fun heightAt(x: Float, z: Float): Float {
        var h = baseHeight(x, z)
        var flat = 0f
        for (d in depots) {
            val dd = abs(d.x - x) + abs(d.z - z)
            if (dd > 120f) continue
            val dist = length2(d.x - x, d.z - z)
            if (dist < 66f) {
                val w = smoothstep(40f, 66f, dist)
                h = lerp(d.y, h, w)
                flat = max(flat, 1f - w)
            }
        }
        road.nearest(x, z, 45f, hit)
        if (hit.found) {
            val w = smoothstep(road.halfWidth + 5f, road.halfWidth + 32f, hit.distance)
            h = lerp(hit.y, h, w)
            flat = 1f - w
        }
        lastFlatWeight = flat
        return h
    }

    /** Altura usada para dibujar el terreno: algo más baja bajo la carretera para que no asome. */
    fun terrainRenderHeight(x: Float, z: Float): Float {
        val h = heightAt(x, z)
        return h - 0.3f * lastFlatWeight
    }

    fun isOnRoad(x: Float, z: Float): Boolean {
        road.nearest(x, z, road.halfWidth + 2f, hit)
        if (hit.found && hit.distance <= road.halfWidth + 1f) return true
        return depots.any { length2(it.x - x, it.z - z) < 30f }
    }

    /** Rumbo de la carretera en el índice dado. */
    fun roadHeading(i: Int): Float = atan2(road.txs[i], road.tzs[i])
}
