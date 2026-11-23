package com.hisokauwu.rutadecarga.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

const val TWO_PI = (2.0 * PI).toFloat()
const val PI_F = PI.toFloat()

fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = clamp((x - e0) / (e1 - e0), 0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Normaliza un ángulo al rango [-PI, PI]. */
fun wrapAngle(a: Float): Float {
    var r = a % TWO_PI
    if (r > PI_F) r -= TWO_PI
    if (r < -PI_F) r += TWO_PI
    return r
}

/** Mueve [current] hacia [target] como máximo [maxDelta]. */
fun approach(current: Float, target: Float, maxDelta: Float): Float {
    val d = target - current
    return if (abs(d) <= maxDelta) target else current + if (d > 0) maxDelta else -maxDelta
}

fun length2(x: Float, z: Float): Float = sqrt(x * x + z * z)

/** Ruido pseudoaleatorio determinista en [0,1) a partir de dos enteros. */
fun hash01(a: Int, b: Int): Float {
    var h = a * 374761393 + b * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFFFF) / 16777216f
}

/**
 * Convención de coordenadas del juego:
 *  - Y hacia arriba.
 *  - Un rumbo (heading) h apunta hacia (sin h, cos h) en el plano XZ.
 *  - En el espacio local de un vehículo +Z es adelante y +X es la IZQUIERDA.
 *  - La derecha de una dirección (fx, fz) es (-fz, fx).
 */
object Dir {
    fun rightX(fx: Float, fz: Float) = -fz
    fun rightZ(fx: Float, fz: Float) = fx
}
