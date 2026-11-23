package com.hisokauwu.rutadecarga.core

import com.hisokauwu.rutadecarga.render.Models
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SimulationTest {
    private val game = Game(seed = 1L)
    private val world = game.world
    private val truck = game.truck

    @Test
    fun roadIsClosedAndSmooth() {
        val road = world.road
        assertTrue("longitud ${road.length}", road.length > 2500f)
        var maxGrade = 0f
        for (i in 0 until road.count) {
            val j = road.index(i + 1)
            assertEquals(road.spacing, length2(road.xs[j] - road.xs[i], road.zs[j] - road.zs[i]), 0.3f)
            maxGrade = maxOf(maxGrade, abs(road.ys[j] - road.ys[i]) / road.spacing)
        }
        assertTrue("pendiente máxima $maxGrade", maxGrade < 0.09f)
    }

    @Test
    fun spawnIsOnRoadAndAtRoadHeight() {
        assertTrue(world.isOnRoad(truck.x, truck.z))
        val hit = world.road.nearest(truck.x, truck.z, 20f, RoadHit())
        assertEquals(hit.y, truck.y, 0.05f)
        assertTrue("lado ${hit.side}", hit.side > 0f) // carril derecho
    }

    @Test
    fun depotsAreReachableAndClear() {
        for (d in world.depots) {
            val hit = world.road.nearest(d.x, d.z, 60f, RoadHit())
            assertTrue(hit.found)
            assertTrue("${d.name} a ${hit.distance} m", hit.distance > world.road.halfWidth + 10f)
            // Ningún obstáculo dentro de la zona de carga
            for (o in world.obstacles) {
                assertTrue("${d.name} bloqueado", length2(o.x - d.x, o.z - d.z) > d.radius + 1f || o.isBox && length2(o.x - d.x, o.z - d.z) > 15f)
            }
        }
    }

    @Test
    fun truckAcceleratesShiftsAndRespectsLimiter() {
        game.controls.throttle = 1f
        var t = 0f
        var maxGear = 1
        var maxSpeed = 0f
        while (t < 60f) {
            truck.update(1f / 120f, 1f, 0f, 0f)
            maxGear = maxOf(maxGear, truck.gear)
            maxSpeed = maxOf(maxSpeed, truck.speed)
            t += 1f / 120f
        }
        assertTrue("marcha $maxGear", maxGear >= 6)
        assertTrue("velocidad ${maxSpeed * 3.6f}", maxSpeed > 12f)
        assertTrue(maxSpeed < Truck.SPEED_LIMIT + 1f)
        assertTrue("rpm ${truck.rpm}", truck.rpm <= Truck.MAX_RPM)
    }

    @Test
    fun brakesStopAndThenReverse() {
        repeat(1200) { truck.update(1f / 120f, 1f, 0f, 0f) }
        assertTrue(truck.speed > 5f)
        repeat(1500) { truck.update(1f / 120f, 0f, 1f, 0f) }
        assertEquals(-1, truck.gear)
        assertTrue("marcha atrás ${truck.speed}", truck.speed < -0.5f)
    }

    @Test
    fun trailerKeepsLengthWhileTurning() {
        repeat(2400) { truck.update(1f / 120f, 0.6f, 0f, 1f) }
        val d = length2(truck.kingpinX - truck.trailerAxleX, truck.kingpinZ - truck.trailerAxleZ)
        assertEquals(Truck.TRAILER_LENGTH, d, 0.05f)
        assertTrue(abs(wrapAngle(truck.heading - truck.trailerHeading)) <= Truck.MAX_JACKKNIFE + 1e-3f)
    }

    @Test
    fun obstaclesAreSolid() {
        val o = world.obstacles.first { !it.isBox }
        truck.reset(o.x, o.z - 12f, 0f)
        repeat(1200) { truck.update(1f / 120f, 1f, 0f, 0f) }
        // El camión no puede atravesar el árbol: su eje trasero sigue antes del árbol
        assertTrue("z ${truck.z} vs ${o.z}", truck.z < o.z || abs(truck.x - o.x) > 1f)
    }

    @Test
    fun fullDeliveryPaysMoney() {
        val m = game.missions
        val start = m.money
        // Teletransporta al depósito de recogida y espera
        truck.reset(m.from.x, m.from.z, 0f)
        repeat(500) { game.update(1f / 120f) }
        assertEquals(MissionState.DELIVERING, m.state)
        assertTrue(truck.cargoMass > 0f)
        val dest = m.to
        truck.reset(dest.x, dest.z, 0f)
        repeat(500) { game.update(1f / 120f) }
        assertTrue("dinero ${m.money}", m.money > start)
        assertEquals(1, m.deliveries)
        assertEquals(dest, m.from)
    }

    @Test
    fun meshesBuild() {
        val t = Models.terrain(world, 60)
        assertEquals(60 * 60 * 6, t.vertexCount)
        assertTrue(Models.road(world).vertexCount > 1000)
        assertTrue(Models.scenery(world).vertexCount > 1000)
        assertTrue(Models.tractor().vertexCount > 100)
    }

    @Test
    fun trafficMovesWithoutLeavingRoad() {
        repeat(1200) { game.traffic.update(1f / 120f, truck) }
        for (c in game.traffic.cars) assertTrue(world.isOnRoad(c.x, c.z))
    }
}
