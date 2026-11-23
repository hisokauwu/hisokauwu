package com.hisokauwu.rutadecarga.core

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Tractora + semirremolque.
 * La posición (x,z) es el centro del eje trasero de la tractora (a nivel del suelo).
 * Modelo cinemático de bicicleta para la tractora y remolque "follow-the-leader".
 */
class Truck(private val world: World) {
    companion object {
        const val TRACTOR_MASS = 8200f
        const val TRAILER_MASS = 6800f
        const val WHEELBASE = 3.9f
        const val KINGPIN_OFFSET = 0.3f      // quinta rueda por delante del eje trasero
        const val TRAILER_LENGTH = 8.6f      // pivote -> eje del remolque
        const val WHEEL_RADIUS = 0.52f
        const val TRACK_WIDTH = 2.1f
        const val IDLE_RPM = 650f
        const val MAX_RPM = 2300f
        const val SPEED_LIMIT = 25f          // 90 km/h, limitador típico de camión
        const val REVERSE_LIMIT = 5.5f
        const val FINAL_DRIVE = 3.4f
        const val REVERSE_RATIO = 11.5f
        const val MAX_JACKKNIFE = 1.4f
        val GEARS = floatArrayOf(12.3f, 9.2f, 6.9f, 5.2f, 3.95f, 3.0f, 2.3f, 1.75f, 1.32f, 1.0f)

        private val tractorCircles = floatArrayOf(-0.4f, 1.9f, 4.1f)
        private const val TRACTOR_R = 1.3f
        private val trailerCircles = floatArrayOf(-0.6f, -4.2f, -7.8f, -11.3f)
        private const val TRAILER_R = 1.35f
    }

    // Estado principal
    var x = 0f; var z = 0f; var y = 0f
    var heading = 0f
    var speed = 0f                 // m/s, positivo hacia delante
    var steer = 0f                 // rad, positivo = derecha
    var pitch = 0f                 // rad, positivo = morro arriba
    var roll = 0f                  // rad, positivo = lado izquierdo arriba
    var rpm = IDLE_RPM
    var gear = 1                   // 1..GEARS.size, -1 marcha atrás
    var throttleApplied = 0f
    var brakeApplied = 0f
    var wheelSpin = 0f
    var odometer = 0f
    var onRoad = true
    var damage = 0f                // 0..100
    var cargoMass = 0f
    var lastImpact = 0f            // velocidad del último golpe (para la cámara/HUD)
    var impactTimer = 0f

    // Remolque
    var trailerAxleX = 0f; var trailerAxleZ = 0f
    var trailerHeading = 0f
    var trailerY = 0f
    var trailerPitch = 0f
    var trailerRoll = 0f

    private var shiftTimer = 0f
    private var reverseTimer = 0f
    private var lateralAccel = 0f

    val totalMass get() = TRACTOR_MASS + TRAILER_MASS + cargoMass
    val forwardX get() = sin(heading)
    val forwardZ get() = cos(heading)
    val kingpinX get() = x + forwardX * KINGPIN_OFFSET
    val kingpinZ get() = z + forwardZ * KINGPIN_OFFSET
    val speedKmh get() = speed * 3.6f
    val inShift get() = shiftTimer > 0f

    fun reset(px: Float, pz: Float, h: Float) {
        x = px; z = pz; heading = h
        speed = 0f; steer = 0f; gear = 1; rpm = IDLE_RPM
        trailerHeading = h
        trailerAxleX = kingpinX - sin(h) * TRAILER_LENGTH
        trailerAxleZ = kingpinZ - cos(h) * TRAILER_LENGTH
        updateAttitude(0f)
    }

    private fun engineTorque(r: Float): Float = when {
        r < 1000f -> lerp(1100f, 2300f, (r - IDLE_RPM) / (1000f - IDLE_RPM))
        r < 1450f -> 2300f
        r < 1900f -> lerp(2300f, 1850f, (r - 1450f) / 450f)
        r < MAX_RPM -> lerp(1850f, 0f, (r - 1900f) / (MAX_RPM - 1900f))
        else -> 0f
    }

    private fun currentRatio(): Float =
        if (gear < 0) REVERSE_RATIO * FINAL_DRIVE else GEARS[gear - 1] * FINAL_DRIVE

    /**
     * @param throttleIn acelerador 0..1
     * @param brakeIn freno 0..1 (mantener parado para meter marcha atrás)
     * @param steerIn dirección -1..1 (positivo = derecha)
     */
    fun update(dt: Float, throttleIn: Float, brakeIn: Float, steerIn: Float) {
        val g = 9.81f
        val m = totalMass

        // --- Marcha adelante / atrás automática ---
        if (gear > 0) {
            if (abs(speed) < 0.4f && brakeIn > 0.5f && throttleIn < 0.1f) {
                reverseTimer += dt
                if (reverseTimer > 0.7f) { gear = -1; reverseTimer = 0f }
            } else reverseTimer = 0f
        } else {
            if (abs(speed) < 0.4f && throttleIn > 0.5f && brakeIn < 0.1f) {
                reverseTimer += dt
                if (reverseTimer > 0.2f) { gear = 1; reverseTimer = 0f }
            } else reverseTimer = 0f
        }
        val accel = if (gear > 0) throttleIn else brakeIn
        val brake = if (gear > 0) brakeIn else throttleIn
        throttleApplied = accel
        brakeApplied = brake

        // --- Motor y cambio automático ---
        val ratio = currentRatio()
        val wheelRpm = abs(speed) / WHEEL_RADIUS * 60f / TWO_PI
        val coupledRpm = wheelRpm * ratio
        val targetRpm = if (shiftTimer > 0f) max(IDLE_RPM, coupledRpm * 0.8f)
        else max(coupledRpm, IDLE_RPM + accel * 500f * (1f - clamp(coupledRpm / 1000f, 0f, 1f)))
        rpm = approach(rpm, min(targetRpm, MAX_RPM), 4000f * dt)

        if (shiftTimer > 0f) shiftTimer -= dt
        if (gear > 0 && shiftTimer <= 0f) {
            val upRpm = lerp(1300f, 1800f, accel)
            if (coupledRpm > upRpm && gear < GEARS.size && accel > 0.05f) {
                gear++; shiftTimer = 0.45f
            } else if (coupledRpm < 950f && gear > 1) {
                gear--; shiftTimer = 0.35f
            }
        }

        var drive = 0f
        if (shiftTimer <= 0f && accel > 0f) {
            val effRpm = max(rpm, 1000f)
            drive = accel * engineTorque(effRpm) * ratio * 0.9f / WHEEL_RADIUS
            drive = min(drive, 0.22f * g * m)               // límite de tracción
            if (gear > 0 && speed > SPEED_LIMIT) drive = 0f
            if (gear < 0 && speed < -REVERSE_LIMIT) drive = 0f
            if (gear < 0) drive = -drive
        }

        // --- Resistencias ---
        onRoad = world.isOnRoad(x, z)
        val crr = if (onRoad) 0.008f else 0.055f
        val grade = -m * g * sin(pitch)
        val aero = 4.3f * speed * abs(speed)
        var resist = crr * m * g + brake * 0.62f * g * m
        if (gear > 0 && accel < 0.05f && shiftTimer <= 0f) resist += 0.025f * m * g * clamp(rpm / 1500f, 0.3f, 1.3f)

        var f = drive + grade - aero
        if (abs(speed) < 0.05f) {
            if (abs(f) <= resist) { f = 0f; speed = 0f } else f -= sign(f) * resist
        } else {
            f -= sign(speed) * resist
        }
        val newSpeed = speed + f / m * dt
        speed = if (speed != 0f && newSpeed * speed < 0f && sign(drive + grade) != sign(newSpeed)) 0f else newSpeed

        // --- Dirección ---
        val maxSteer = 0.58f / (1f + abs(speed) * 0.065f)
        steer = approach(steer, clamp(steerIn, -1f, 1f) * maxSteer, 1.4f * dt)

        // --- Integración cinemática ---
        val yawRate = -speed / WHEELBASE * tan(steer)
        heading = wrapAngle(heading + yawRate * dt)
        lateralAccel = lerp(lateralAccel, yawRate * speed, min(1f, dt * 4f))
        x += sin(heading) * speed * dt
        z += cos(heading) * speed * dt
        odometer += abs(speed) * dt
        wheelSpin += speed / WHEEL_RADIUS * dt

        // Remolque: el eje sigue al pivote manteniendo la distancia
        updateTrailer()

        // --- Colisiones ---
        if (impactTimer > 0f) impactTimer -= dt
        collide()
        val r = sqrt(x * x + z * z)
        if (r > world.mapRadius) {
            x *= world.mapRadius / r; z *= world.mapRadius / r; speed *= 0.5f
            updateTrailer()
        }

        updateAttitude(dt)
    }

    private fun updateTrailer() {
        val kx = kingpinX; val kz = kingpinZ
        var dx = kx - trailerAxleX
        var dz = kz - trailerAxleZ
        val l = length2(dx, dz).coerceAtLeast(1e-4f)
        dx /= l; dz /= l
        trailerHeading = atan2(dx, dz)
        // Límite de tijera (jackknife)
        val rel = wrapAngle(heading - trailerHeading)
        if (abs(rel) > MAX_JACKKNIFE) {
            trailerHeading = wrapAngle(heading - sign(rel) * MAX_JACKKNIFE)
            if (abs(speed) > 3f) speed *= 0.97f
        }
        trailerAxleX = kx - sin(trailerHeading) * TRAILER_LENGTH
        trailerAxleZ = kz - cos(trailerHeading) * TRAILER_LENGTH
    }

    private fun updateAttitude(dt: Float) {
        val fx = forwardX; val fz = forwardZ
        val lx = -Dir.rightX(fx, fz); val lz = -Dir.rightZ(fx, fz)   // vector izquierda
        val hRear = world.heightAt(x, z)
        val hFront = world.heightAt(x + fx * WHEELBASE, z + fz * WHEELBASE)
        val hmx = x + fx * WHEELBASE * 0.5f; val hmz = z + fz * WHEELBASE * 0.5f
        val hl = world.heightAt(hmx + lx * TRACK_WIDTH * 0.5f, hmz + lz * TRACK_WIDTH * 0.5f)
        val hr = world.heightAt(hmx - lx * TRACK_WIDTH * 0.5f, hmz - lz * TRACK_WIDTH * 0.5f)
        y = hRear
        pitch = atan((hFront - hRear) / WHEELBASE)
        // Balanceo: terreno + inercia lateral (la cabina se inclina hacia fuera de la curva)
        val bodyRoll = clamp(lateralAccel * 0.012f, -0.08f, 0.08f)
        roll = atan((hl - hr) / TRACK_WIDTH) + bodyRoll
        if (!onRoad && abs(speed) > 2f) {
            roll += sin(odometer * 3.1f) * 0.012f
            pitch += sin(odometer * 2.3f) * 0.008f
        }

        val tfx = sin(trailerHeading); val tfz = cos(trailerHeading)
        val tlx = -Dir.rightX(tfx, tfz); val tlz = -Dir.rightZ(tfx, tfz)
        val hk = world.heightAt(kingpinX, kingpinZ)
        val ha = world.heightAt(trailerAxleX, trailerAxleZ)
        val tl = world.heightAt(trailerAxleX + tlx, trailerAxleZ + tlz)
        val tr = world.heightAt(trailerAxleX - tlx, trailerAxleZ - tlz)
        trailerY = hk
        trailerPitch = atan((hk - ha) / TRAILER_LENGTH)
        trailerRoll = atan((tl - tr) / 2f) + bodyRoll * 0.6f
    }

    private fun collide() {
        val vx = forwardX * speed
        val vz = forwardZ * speed
        for (o in world.obstacles) {
            // Descarte rápido
            val dx0 = o.x - x; val dz0 = o.z - z
            if (abs(dx0) > 30f || abs(dz0) > 30f) continue
            for (c in tractorCircles) {
                val cx = x + forwardX * c; val cz = z + forwardZ * c
                resolve(o, cx, cz, TRACTOR_R, vx, vz, false)
            }
            val tfx = sin(trailerHeading); val tfz = cos(trailerHeading)
            for (c in trailerCircles) {
                val cx = kingpinX + tfx * c; val cz = kingpinZ + tfz * c
                resolve(o, cx, cz, TRAILER_R, vx, vz, true)
            }
        }
    }

    private val push = FloatArray(3)

    private fun resolve(o: Obstacle, cx: Float, cz: Float, r: Float, vx: Float, vz: Float, trailer: Boolean) {
        if (!penetration(o, cx, cz, r, push)) return
        val nx = push[0]; val nz = push[1]; val depth = push[2]
        if (trailer) {
            trailerAxleX += nx * depth; trailerAxleZ += nz * depth
            x += nx * depth * 0.3f; z += nz * depth * 0.3f
        } else {
            x += nx * depth; z += nz * depth
        }
        onImpact(nx, nz, vx, vz)
    }

    /** Contacto con un vehículo del tráfico (n apunta desde el coche hacia el camión). */
    fun hitByCar(nx: Float, nz: Float, depth: Float, carVx: Float, carVz: Float) {
        x += nx * depth; z += nz * depth
        onImpact(nx, nz, forwardX * speed - carVx, forwardZ * speed - carVz)
        updateTrailer()
    }

    private fun onImpact(nx: Float, nz: Float, vx: Float, vz: Float) {
        val vn = -(vx * nx + vz * nz)          // velocidad hacia el obstáculo
        if (vn <= 0f) return
        // Elimina la componente de velocidad hacia el obstáculo (con algo de rebote)
        val fx = forwardX; val fz = forwardZ
        val nvx = vx + nx * vn * 1.2f
        val nvz = vz + nz * vn * 1.2f
        val along = nvx * fx + nvz * fz
        if (abs(along) < abs(speed)) speed = along
        if (vn > 1.5f && impactTimer <= 0f) {
            damage = min(100f, damage + (vn - 1.5f) * 2.2f)
            lastImpact = vn
            impactTimer = 0.5f
        }
    }

    /** Calcula penetración círculo-obstáculo; devuelve normal (desde el obstáculo) y profundidad. */
    private fun penetration(o: Obstacle, cx: Float, cz: Float, r: Float, out: FloatArray): Boolean {
        if (abs(o.x - cx) > o.bound + r || abs(o.z - cz) > o.bound + r) return false
        if (!o.isBox) {
            val dx = cx - o.x; val dz = cz - o.z
            val d = length2(dx, dz)
            val min = o.radius + r
            if (d >= min || d < 1e-5f) return false
            out[0] = dx / d; out[1] = dz / d; out[2] = min - d
            return true
        }
        // Caja orientada: pasa el punto a coordenadas locales
        val s = sin(o.yaw); val c = cos(o.yaw)
        val dx = cx - o.x; val dz = cz - o.z
        val lx = dx * c - dz * s          // eje local X = (cos, -sin)
        val lz = dx * s + dz * c          // eje local Z = (sin, cos)
        val qx = clamp(lx, -o.hx, o.hx)
        val qz = clamp(lz, -o.hz, o.hz)
        var ex = lx - qx; var ez = lz - qz
        var d = length2(ex, ez)
        val depth: Float
        if (d > 1e-5f) {
            if (d >= r) return false
            ex /= d; ez /= d
            depth = r - d
        } else {
            // Centro dentro de la caja: sal por la cara más cercana
            val px = o.hx - abs(lx); val pz = o.hz - abs(lz)
            if (px < pz) { ex = sign(lx); ez = 0f; d = px } else { ex = 0f; ez = sign(lz); d = pz }
            depth = d + r
        }
        // Vuelve a coordenadas del mundo
        out[0] = ex * c + ez * s
        out[1] = -ex * s + ez * c
        out[2] = depth
        return true
    }

    /** Puntos de colisión del camión (para el tráfico). Devuelve pares x,z y radio en [radii]. */
    fun collisionCircles(outXZ: FloatArray, outR: FloatArray): Int {
        var n = 0
        for (c in tractorCircles) {
            outXZ[n * 2] = x + forwardX * c; outXZ[n * 2 + 1] = z + forwardZ * c; outR[n] = TRACTOR_R; n++
        }
        val tfx = sin(trailerHeading); val tfz = cos(trailerHeading)
        for (c in trailerCircles) {
            outXZ[n * 2] = kingpinX + tfx * c; outXZ[n * 2 + 1] = kingpinZ + tfz * c; outR[n] = TRAILER_R; n++
        }
        return n
    }

    fun gearLabel(): String = if (gear < 0) "R" else if (abs(speed) < 0.3f && throttleApplied < 0.05f) "N" else "D$gear"
}
