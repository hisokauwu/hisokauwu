package com.hisokauwu.rutadecarga.render

import com.hisokauwu.rutadecarga.core.Dir
import com.hisokauwu.rutadecarga.core.World
import com.hisokauwu.rutadecarga.core.clamp
import com.hisokauwu.rutadecarga.core.hash01
import com.hisokauwu.rutadecarga.core.lerp
import kotlin.math.cos
import kotlin.math.sin

/** Geometría procedural de todo el juego (sin dependencias de Android). */
object Models {

    // ----------------------------------------------------------------- Terreno
    fun terrain(world: World, cells: Int = 170): MeshBuilder {
        val size = world.mapRadius * 2f + 160f
        val half = size / 2f
        val step = size / cells
        val n = cells + 1
        val hs = FloatArray(n * n)
        for (j in 0 until n) for (i in 0 until n) {
            hs[j * n + i] = world.terrainRenderHeight(-half + i * step, -half + j * step)
        }
        val mb = MeshBuilder(cells * cells * 6)
        for (j in 0 until cells) for (i in 0 until cells) {
            val x0 = -half + i * step; val x1 = x0 + step
            val z0 = -half + j * step; val z1 = z0 + step
            val h00 = hs[j * n + i]; val h10 = hs[j * n + i + 1]
            val h01 = hs[(j + 1) * n + i]; val h11 = hs[(j + 1) * n + i + 1]
            val flip = (i + j) % 2 == 0
            if (flip) {
                mb.triUp(x0, h00, z0, x1, h10, z0, x1, h11, z1, terrainColor(world, x0, z0, h00, h10, h11, i, j, 0))
                mb.triUp(x0, h00, z0, x1, h11, z1, x0, h01, z1, terrainColor(world, x0, z1, h00, h11, h01, i, j, 1))
            } else {
                mb.triUp(x0, h00, z0, x1, h10, z0, x0, h01, z1, terrainColor(world, x0, z0, h00, h10, h01, i, j, 0))
                mb.triUp(x1, h10, z0, x1, h11, z1, x0, h01, z1, terrainColor(world, x1, z1, h10, h11, h01, i, j, 1))
            }
        }
        return mb
    }

    private fun terrainColor(world: World, x: Float, z: Float, a: Float, b: Float, c: Float, i: Int, j: Int, k: Int): Int {
        val h = (a + b + c) / 3f
        val relief = maxOf(a, b, c) - minOf(a, b, c)
        val noise = hash01(i * 2 + k, j)
        world.heightAt(x, z)
        val flat = world.lastFlatWeight
        val grass = mix(0xFF5E9A3C.toInt(), 0xFF7BAF45.toInt(), noise)
        var col = grass
        if (h > 22f) col = mix(col, 0xFF8C8A6A.toInt(), clamp((h - 22f) / 18f, 0f, 1f))
        if (relief > 6f) col = mix(col, 0xFF8A8478.toInt(), clamp((relief - 6f) / 8f, 0f, 1f))
        if (h > 55f) col = mix(col, 0xFFF2F4F6.toInt(), clamp((h - 55f) / 15f, 0f, 1f))
        if (flat > 0.6f) col = mix(col, 0xFFA08A62.toInt(), clamp((flat - 0.6f) * 2.5f, 0f, 0.8f))
        return col
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        val r = lerp(MeshBuilder.r(a), MeshBuilder.r(b), t)
        val g = lerp(MeshBuilder.g(a), MeshBuilder.g(b), t)
        val bl = lerp(MeshBuilder.b(a), MeshBuilder.b(b), t)
        return (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (bl * 255).toInt()
    }

    // ---------------------------------------------------------------- Carretera
    fun road(world: World): MeshBuilder {
        val road = world.road
        val mb = MeshBuilder(road.count * 30)
        val hw = road.halfWidth
        val asphalt = 0xFF3A3B3F.toInt()
        val asphalt2 = 0xFF35363A.toInt()
        val white = 0xFFE8E8E8.toInt()
        val yellow = 0xFFE2B93B.toInt()
        val gravel = 0xFF8E8370.toInt()
        for (i in 0 until road.count) {
            val j = road.index(i + 1)
            fun strip(off0: Float, off1: Float, lift: Float, color: Int) {
                val rx0 = Dir.rightX(road.txs[i], road.tzs[i]); val rz0 = Dir.rightZ(road.txs[i], road.tzs[i])
                val rx1 = Dir.rightX(road.txs[j], road.tzs[j]); val rz1 = Dir.rightZ(road.txs[j], road.tzs[j])
                val y0 = road.ys[i] + lift; val y1 = road.ys[j] + lift
                mb.quadUp(
                    road.xs[i] + rx0 * off0, y0, road.zs[i] + rz0 * off0,
                    road.xs[i] + rx0 * off1, y0, road.zs[i] + rz0 * off1,
                    road.xs[j] + rx1 * off1, y1, road.zs[j] + rz1 * off1,
                    road.xs[j] + rx1 * off0, y1, road.zs[j] + rz1 * off0,
                    color,
                )
            }
            strip(-hw - 1.6f, -hw, 0.0f, gravel)
            strip(hw, hw + 1.6f, 0.0f, gravel)
            strip(-hw, hw, 0.03f, if ((i / 4) % 2 == 0) asphalt else asphalt2)
            strip(-hw + 0.25f, -hw + 0.45f, 0.06f, white)
            strip(hw - 0.45f, hw - 0.25f, 0.06f, white)
            if ((i / 3) % 2 == 0) strip(-0.1f, 0.1f, 0.06f, yellow)
        }
        // Postes de señalización en los bordes
        for (i in 0 until road.count step 25) {
            val rx = Dir.rightX(road.txs[i], road.tzs[i]); val rz = Dir.rightZ(road.txs[i], road.tzs[i])
            for (s in intArrayOf(-1, 1)) {
                val px = road.xs[i] + rx * (hw + 1.2f) * s
                val pz = road.zs[i] + rz * (hw + 1.2f) * s
                mb.box(px, road.ys[i] + 0.5f, pz, 0.07f, 0.5f, 0.07f, 0xFFF0F0F0.toInt())
                mb.box(px, road.ys[i] + 0.85f, pz, 0.08f, 0.1f, 0.08f, if (s > 0) 0xFFE04030.toInt() else 0xFFF0F0F0.toInt())
            }
        }
        return mb
    }

    // ----------------------------------------------------- Árboles y edificios
    fun scenery(world: World): MeshBuilder {
        val mb = MeshBuilder(world.trees.size * 60 + 2000)
        for ((k, t) in world.trees.withIndex()) {
            val s = t.scale
            mb.box(t.x, t.y + 1.0f * s, t.z, 0.22f * s, 1.0f * s, 0.22f * s, 0xFF6B4A2E.toInt(), yaw = k * 0.7f)
            if (t.kind == 0) {
                val dark = mix(0xFF2F6B35.toInt(), 0xFF3F7F3A.toInt(), hash01(k, 3))
                mb.cone(t.x, t.y + 1.4f * s, t.z, 2.4f * s, 4.2f * s, 6, dark, k * 0.3f)
                mb.cone(t.x, t.y + 3.6f * s, t.z, 1.8f * s, 3.4f * s, 6, MeshBuilder.shade(dark, 1.1f), k * 0.5f)
            } else {
                val leaf = mix(0xFF5C9A3A.toInt(), 0xFF7FAE3E.toInt(), hash01(k, 7))
                mb.blob(t.x, t.y + 3.6f * s, t.z, 2.6f * s, leaf)
            }
        }
        for (b in world.buildings) {
            mb.box(b.x, b.y + b.hy, b.z, b.hx, b.hy, b.hz, b.color, b.yaw, topColor = b.roofColor)
            // Tejado a dos aguas como caja achatada encima
            mb.box(b.x, b.y + b.hy * 2f + 0.3f, b.z, b.hx * 1.04f, 0.3f, b.hz * 1.06f, b.roofColor, b.yaw)
            if (b.doorSide != 0f) {
                // Portones en la cara que mira a la explanada (eje X local)
                val fx = sin(b.yaw); val fz = cos(b.yaw)
                val lx = -Dir.rightX(fx, fz); val lz = -Dir.rightZ(fx, fz)
                for (d in intArrayOf(-1, 1)) {
                    val dx = b.x + lx * b.hx * b.doorSide + fx * d * b.hz * 0.45f
                    val dz = b.z + lz * b.hx * b.doorSide + fz * d * b.hz * 0.45f
                    mb.box(dx, b.y + 2.4f, dz, 0.12f, 2.4f, 2.4f, 0xFF505055.toInt(), b.yaw)
                }
            }
        }
        // Explanadas de los depósitos
        for (d in world.depots) {
            mb.box(d.x, d.y - 0.12f, d.z, 18f, 0.15f, 18f, 0xFFA7A39A.toInt(), d.heading)
            // Marca de la zona de carga
            val n = 20
            for (i in 0 until n) {
                val a0 = i * 2f * Math.PI.toFloat() / n
                val a1 = (i + 1) * 2f * Math.PI.toFloat() / n
                val r0 = d.radius - 0.4f; val r1 = d.radius
                if (i % 2 == 0) mb.quadUp(
                    d.x + cos(a0) * r0, d.y + 0.06f, d.z + sin(a0) * r0,
                    d.x + cos(a0) * r1, d.y + 0.06f, d.z + sin(a0) * r1,
                    d.x + cos(a1) * r1, d.y + 0.06f, d.z + sin(a1) * r1,
                    d.x + cos(a1) * r0, d.y + 0.06f, d.z + sin(a1) * r0,
                    0xFFF0F0F0.toInt(),
                )
            }
            val fx = sin(d.heading); val fz = cos(d.heading)
            // Acceso asfaltado entre la carretera y la explanada
            val ax = d.x - Dir.rightX(fx, fz) * d.side * 18.5f
            val az = d.z - Dir.rightZ(fx, fz) * d.side * 18.5f
            mb.box(ax, d.y - 0.135f, az, 3.2f, 0.15f, 12f, 0xFF6E6E70.toInt(), d.heading)
            // Cartel con el color del depósito
            val sx = d.x - fx * 17f; val sz = d.z - fz * 17f
            mb.box(sx, d.y + 2.5f, sz, 0.15f, 2.5f, 0.15f, 0xFF707070.toInt())
            mb.box(sx, d.y + 5.5f, sz, 0.15f, 1.0f, 2.6f, d.color, d.heading)
            // Palés y contenedores decorativos
            for (p in 0 until 4) {
                val ox = Dir.rightX(fx, fz) * d.side * 16.5f
                val oz = Dir.rightZ(fx, fz) * d.side * 16.5f
                val px = d.x + ox + fx * (p * 3.2f - 9f)
                val pz = d.z + oz + fz * (p * 3.2f - 9f)
                mb.box(px, d.y + 0.6f, pz, 1.2f, 0.6f, 1.2f, if (p % 2 == 0) 0xFFB08850.toInt() else 0xFF4F7FAF.toInt(), d.heading)
            }
        }
        return mb
    }

    // ---------------------------------------------------------- Vehículos
    /** Tractora en coordenadas locales: origen en el eje trasero, +Z adelante, +X izquierda. */
    fun tractor(cabColor: Int = 0xFFC8322B.toInt()): MeshBuilder {
        val mb = MeshBuilder(800)
        val dark = 0xFF2A2A2E.toInt()
        val chrome = 0xFFC9CDD2.toInt()
        val glass = 0xFF26384A.toInt()
        mb.box(0f, 0.95f, 1.45f, 0.55f, 0.16f, 3.65f, dark)                           // bastidor
        mb.box(0f, 2.25f, 4.05f, 1.25f, 1.1f, 1.0f, cabColor)                          // cabina
        mb.box(0f, 3.55f, 3.75f, 1.18f, 0.22f, 0.72f, MeshBuilder.shade(cabColor, 0.9f)) // deflector
        mb.box(0f, 1.25f, 4.6f, 1.22f, 0.35f, 0.5f, MeshBuilder.shade(cabColor, 0.85f)) // morro bajo
        mb.box(0f, 2.65f, 5.06f, 1.08f, 0.5f, 0.02f, glass)                            // parabrisas
        for (s in intArrayOf(-1, 1)) {
            mb.box(1.26f * s, 2.7f, 4.45f, 0.02f, 0.4f, 0.42f, glass)                  // ventanillas
            mb.box(1.36f * s, 2.6f, 5.0f, 0.06f, 0.28f, 0.05f, dark)                   // retrovisores
            mb.box(0.95f * s, 1.15f, 5.12f, 0.2f, 0.1f, 0.04f, 0xFFFFF4C8.toInt())     // faros
            mb.box(1.0f * s, 0.85f, 2.2f, 0.28f, 0.3f, 0.65f, chrome)                  // depósitos
            mb.box(1.08f * s, 2.85f, 2.85f, 0.09f, 1.05f, 0.09f, chrome)               // escapes
            mb.box(1.1f * s, 1.32f, 0f, 0.26f, 0.07f, 0.95f, dark)                     // guardabarros traseros
            mb.box(1.1f * s, 1.35f, 3.9f, 0.27f, 0.07f, 0.55f, MeshBuilder.shade(cabColor, 0.8f))
            mb.box(1.15f * s, 1.25f, 3.1f, 0.08f, 0.04f, 0.2f, dark)                   // escalón
        }
        mb.box(0f, 0.85f, 5.15f, 1.26f, 0.28f, 0.12f, chrome)                          // parachoques
        mb.box(0f, 1.7f, 5.08f, 0.8f, 0.38f, 0.02f, 0xFF3A3A3E.toInt())               // rejilla
        mb.box(0f, 1.18f, 0.3f, 0.9f, 0.06f, 0.8f, 0xFF111114.toInt())                // quinta rueda
        mb.box(0f, 3.45f, 4.95f, 0.9f, 0.06f, 0.08f, 0xFFFFC94A.toInt())              // luces de techo
        return mb
    }

    /** Ruedas de la tractora (x, z) y ruedas delanteras directrices. */
    val TRACTOR_WHEELS = floatArrayOf(1.05f, 3.9f, -1.05f, 3.9f, 1.05f, 0.65f, -1.05f, 0.65f, 1.05f, -0.65f, -1.05f, -0.65f)
    val TRAILER_WHEELS = floatArrayOf(1.05f, -7.4f, -1.05f, -7.4f, 1.05f, -8.7f, -1.05f, -8.7f, 1.05f, -10.0f, -1.05f, -10.0f)

    fun wheel(): MeshBuilder {
        val mb = MeshBuilder(200)
        mb.cylinderX(0f, 0f, 0f, 0.52f, 0.18f, 10, 0xFF1C1C1E.toInt(), 0xFF2A2A2C.toInt())
        mb.box(0f, 0f, 0f, 0.19f, 0.22f, 0.22f, 0xFFB8BCC2.toInt())
        mb.box(0f, 0f, 0f, 0.19f, 0.05f, 0.4f, 0xFF8E9298.toInt())
        return mb
    }

    /** Chasis del remolque: origen en el pivote (kingpin), +Z adelante. */
    fun trailerChassis(): MeshBuilder {
        val mb = MeshBuilder(400)
        val dark = 0xFF262629.toInt()
        mb.box(0f, 1.05f, -5.6f, 0.5f, 0.14f, 6.6f, dark)
        mb.box(0f, 1.18f, -11.9f, 1.25f, 0.12f, 0.12f, 0xFFB03030.toInt())    // paragolpes
        for (s in intArrayOf(-1, 1)) {
            mb.box(0.75f * s, 0.6f, -1.6f, 0.07f, 0.45f, 0.07f, 0xFF707070.toInt()) // patas
            mb.box(1.15f * s, 1.25f, -8.7f, 0.22f, 0.06f, 2.0f, dark)              // guardabarros
            mb.box(0.9f * s, 1.25f, -12.36f, 0.18f, 0.1f, 0.02f, 0xFFE03020.toInt()) // pilotos
        }
        return mb
    }

    /** Caja del remolque (se tiñe según la carga). */
    fun trailerBox(): MeshBuilder {
        val mb = MeshBuilder(200)
        val white = 0xFFF2F2F2.toInt()
        mb.box(0f, 2.65f, -5.6f, 1.27f, 1.45f, 6.8f, white, topColor = 0xFFDADADA.toInt())
        for (i in 0 until 7) {
            val z = -11.6f + i * 2.0f
            for (s in intArrayOf(-1, 1)) mb.box(1.285f * s, 2.65f, z, 0.02f, 1.45f, 0.06f, 0xFFCFCFCF.toInt())
        }
        mb.box(0f, 2.65f, -12.41f, 1.2f, 1.38f, 0.02f, 0xFFD5D5D5.toInt())
        return mb
    }

    fun carBody(): MeshBuilder {
        val mb = MeshBuilder(200)
        val white = 0xFFFFFFFF.toInt()
        mb.box(0f, 0.68f, 0f, 0.9f, 0.36f, 2.1f, white)
        mb.box(0f, 1.3f, -0.25f, 0.82f, 0.28f, 1.1f, white)
        return mb
    }

    fun carDetails(): MeshBuilder {
        val mb = MeshBuilder(300)
        val glass = 0xFF22303C.toInt()
        val tire = 0xFF151515.toInt()
        mb.box(0f, 1.3f, 0.86f, 0.74f, 0.24f, 0.02f, glass)
        mb.box(0f, 1.3f, -1.36f, 0.74f, 0.22f, 0.02f, glass)
        for (s in intArrayOf(-1, 1)) {
            mb.box(0.83f * s, 1.3f, -0.25f, 0.02f, 0.22f, 0.95f, glass)
            mb.box(0.85f * s, 0.35f, 1.35f, 0.14f, 0.33f, 0.33f, tire)
            mb.box(0.85f * s, 0.35f, -1.35f, 0.14f, 0.33f, 0.33f, tire)
            mb.box(0.6f * s, 0.75f, 2.11f, 0.2f, 0.08f, 0.02f, 0xFFFFF4D0.toInt())
            mb.box(0.65f * s, 0.8f, -2.11f, 0.18f, 0.07f, 0.02f, 0xFFD02020.toInt())
        }
        return mb
    }

    /** Baliza del objetivo: anillo y flecha. */
    fun beacon(): MeshBuilder {
        val mb = MeshBuilder(400)
        val yellow = 0xFFFFD02A.toInt()
        mb.box(0f, 9f, 0f, 0.35f, 6f, 0.35f, yellow)
        // flecha hacia abajo
        mb.tri(-1.6f, 4.2f, 0f, 1.6f, 4.2f, 0f, 0f, 1.6f, 0f, yellow, 0f, 3f, 0.5f)
        mb.tri(-1.6f, 4.2f, 0f, 1.6f, 4.2f, 0f, 0f, 1.6f, 0f, yellow, 0f, 3f, -0.5f)
        mb.tri(0f, 4.2f, -1.6f, 0f, 4.2f, 1.6f, 0f, 1.6f, 0f, MeshBuilder.shade(yellow, 0.85f), 0.5f, 3f, 0f)
        mb.tri(0f, 4.2f, -1.6f, 0f, 4.2f, 1.6f, 0f, 1.6f, 0f, MeshBuilder.shade(yellow, 0.85f), -0.5f, 3f, 0f)
        return mb
    }

    fun ring(radius: Float): MeshBuilder {
        val mb = MeshBuilder(200)
        val n = 32
        for (i in 0 until n) {
            val a0 = i * 2f * Math.PI.toFloat() / n
            val a1 = (i + 1) * 2f * Math.PI.toFloat() / n
            val r0 = radius - 0.8f
            mb.quadUp(
                cos(a0) * r0, 0f, sin(a0) * r0, cos(a0) * radius, 0f, sin(a0) * radius,
                cos(a1) * radius, 0f, sin(a1) * radius, cos(a1) * r0, 0f, sin(a1) * r0,
                0xFFFFD02A.toInt(),
            )
        }
        return mb
    }

    /** Interior de la cabina para la vista de conductor. */
    fun cockpit(): MeshBuilder {
        val mb = MeshBuilder(300)
        val dash = 0xFF2C2D31.toInt()
        val trim = 0xFF4A4B50.toInt()
        mb.box(0f, 2.12f, 4.9f, 1.22f, 0.22f, 0.25f, dash, topColor = 0xFF232427.toInt())
        mb.box(0.62f, 2.37f, 4.8f, 0.35f, 0.08f, 0.12f, 0xFF15161A.toInt())  // cuadro
        for (s in intArrayOf(-1, 1)) {
            mb.box(1.18f * s, 2.75f, 5.0f, 0.07f, 0.6f, 0.07f, trim)          // pilares
            mb.box(1.45f * s, 2.6f, 5.0f, 0.05f, 0.3f, 0.12f, 0xFF1A1A1A.toInt()) // retrovisores
            mb.box(1.25f * s, 2.0f, 4.3f, 0.04f, 0.3f, 0.7f, trim)            // puertas
        }
        mb.box(0f, 3.28f, 4.95f, 1.25f, 0.08f, 0.15f, trim)                   // techo
        mb.box(0f, 1.6f, 5.6f, 1.2f, 0.05f, 0.6f, 0xFFC8322B.toInt())         // capó
        return mb
    }

    fun steeringWheel(): MeshBuilder {
        val mb = MeshBuilder(300)
        val c = 0xFF1B1B1E.toInt()
        val n = 12
        for (i in 0 until n) {
            val a = i * 2f * Math.PI.toFloat() / n
            mb.box(cos(a) * 0.24f, 0f, sin(a) * 0.24f, 0.04f, 0.03f, 0.065f, c, yaw = -a)
        }
        mb.box(0f, 0f, 0f, 0.24f, 0.025f, 0.03f, c)
        mb.box(0f, 0f, -0.12f, 0.03f, 0.025f, 0.12f, c)
        mb.box(0f, 0.01f, 0f, 0.06f, 0.03f, 0.06f, 0xFFB0B0B0.toInt())
        return mb
    }
}
