package com.hisokauwu.rutadecarga.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class TrafficCar(var s: Float, val dir: Int, val cruise: Float, val color: Int) {
    var speed = cruise
    var x = 0f; var y = 0f; var z = 0f
    var heading = 0f
    var stunned = 0f
    var wheelSpin = 0f
    val fx get() = kotlin.math.sin(heading)
    val fz get() = kotlin.math.cos(heading)
}

/** Coches que circulan por su carril siguiendo la carretera, frenan ante obstáculos y chocan con el camión. */
class Traffic(private val world: World, count: Int = 10, seed: Long = 42L) {
    val cars: List<TrafficCar>
    private val laneOffset = 1.8f
    private val carRadius = 1.9f
    private val circlesXZ = FloatArray(32)
    private val circlesR = FloatArray(16)

    init {
        val rnd = Random(seed)
        val palette = intArrayOf(0xFFD63B30.toInt(), 0xFF2F6FD6.toInt(), 0xFFEFEFEF.toInt(),
            0xFF2B2B2B.toInt(), 0xFFE0B020.toInt(), 0xFF3E9E55.toInt(), 0xFF8A3FA8.toInt())
        val road = world.road
        cars = List(count) { i ->
            val dir = if (i % 2 == 0) 1 else -1
            TrafficCar(
                s = road.count * (i + rnd.nextFloat() * 0.5f) / count,
                dir = dir,
                cruise = 14f + rnd.nextFloat() * 7f,
                color = palette[rnd.nextInt(palette.size)],
            )
        }
        cars.forEach { place(it) }
    }

    private fun place(c: TrafficCar) {
        val road = world.road
        val n = road.count
        c.s = ((c.s % n) + n) % n
        val i = floor(c.s).toInt()
        val j = road.index(i + 1)
        val f = c.s - i
        val px = lerp(road.xs[i], road.xs[j], f)
        val pz = lerp(road.zs[i], road.zs[j], f)
        val tx = lerp(road.txs[i], road.txs[j], f)
        val tz = lerp(road.tzs[i], road.tzs[j], f)
        val rx = Dir.rightX(tx, tz); val rz = Dir.rightZ(tx, tz)
        c.x = px + rx * laneOffset * c.dir
        c.z = pz + rz * laneOffset * c.dir
        c.y = lerp(road.ys[i], road.ys[j], f)
        c.heading = atan2(tx * c.dir, tz * c.dir)
    }

    fun update(dt: Float, truck: Truck) {
        val n = truck.collisionCircles(circlesXZ, circlesR)
        for (c in cars) {
            var target = c.cruise
            val fx = c.fx; val fz = c.fz
            // Obstáculos delante: el camión y otros coches
            for (k in 0 until n) {
                target = min(target, gapSpeed(c, fx, fz, circlesXZ[k * 2], circlesXZ[k * 2 + 1], circlesR[k],
                    if (truck.speed * (truck.forwardX * fx + truck.forwardZ * fz) > 0) abs(truck.speed) else 0f))
            }
            for (o in cars) {
                if (o === c) continue
                target = min(target, gapSpeed(c, fx, fz, o.x, o.z, carRadius, if (o.dir == c.dir) o.speed else 0f))
            }
            if (c.stunned > 0f) { c.stunned -= dt; target = 0f }
            c.speed = if (target < c.speed) max(target, c.speed - 7f * dt) else min(target, c.speed + 2.5f * dt)
            c.s += c.dir * c.speed * dt / world.road.spacing
            c.wheelSpin += c.speed / 0.35f * dt
            place(c)

            // Choque con el camión
            for (k in 0 until n) {
                val dx = circlesXZ[k * 2] - c.x
                val dz = circlesXZ[k * 2 + 1] - c.z
                val d = length2(dx, dz)
                val minD = circlesR[k] + carRadius
                if (d < minD && d > 1e-4f) {
                    truck.hitByCar(dx / d, dz / d, (minD - d) * 0.8f, fx * c.speed, fz * c.speed)
                    c.speed = 0f
                    c.stunned = 2.5f
                    break
                }
            }
        }
    }

    /** Velocidad máxima segura para no alcanzar el punto (ox,oz) que está delante en el carril. */
    private fun gapSpeed(c: TrafficCar, fx: Float, fz: Float, ox: Float, oz: Float, r: Float, otherSpeed: Float): Float {
        val dx = ox - c.x; val dz = oz - c.z
        val ahead = dx * fx + dz * fz
        if (ahead <= 0f || ahead > 40f) return Float.MAX_VALUE
        val lateral = abs(dx * Dir.rightX(fx, fz) + dz * Dir.rightZ(fx, fz))
        if (lateral > r + 1.6f) return Float.MAX_VALUE
        val gap = ahead - r - carRadius - 3f
        return max(0f, otherSpeed * 0.9f + gap * 0.5f)
    }
}
