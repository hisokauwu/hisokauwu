package com.hisokauwu.rutadecarga.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.hisokauwu.rutadecarga.core.Game
import com.hisokauwu.rutadecarga.core.MissionState
import com.hisokauwu.rutadecarga.core.Truck
import com.hisokauwu.rutadecarga.core.wrapAngle
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

class GameRenderer(private val game: Game) : GLSurfaceView.Renderer {
    private lateinit var shader: Shader
    private lateinit var terrain: GlMesh
    private lateinit var road: GlMesh
    private lateinit var scenery: GlMesh
    private lateinit var tractor: GlMesh
    private lateinit var wheel: GlMesh
    private lateinit var trailerChassis: GlMesh
    private lateinit var trailerBox: GlMesh
    private lateinit var carBody: GlMesh
    private lateinit var carDetails: GlMesh
    private lateinit var beacon: GlMesh
    private lateinit var ring: GlMesh
    private lateinit var cockpit: GlMesh
    private lateinit var steeringWheel: GlMesh

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val truckM = FloatArray(16)
    private val trailerM = FloatArray(16)
    private val tmpM = FloatArray(16)
    private val vecIn = FloatArray(4)
    private val vecOut = FloatArray(4)

    private var lastNanos = 0L
    private var camInit = false
    private var camX = 0f; private var camY = 0f; private var camZ = 0f
    private var camYaw = 0f
    private val rnd = Random(3)

    @Volatile var fps = 0f; private set
    @Volatile var ready = false; private set

    private val sky = floatArrayOf(0.62f, 0.78f, 0.93f)
    private val light = floatArrayOf(0.45f, 0.8f, 0.35f).also {
        val l = kotlin.math.sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2])
        for (i in 0..2) it[i] /= l
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glClearColor(sky[0], sky[1], sky[2], 1f)
        shader = Shader()
        val w = game.world
        terrain = GlMesh(Models.terrain(w))
        road = GlMesh(Models.road(w))
        scenery = GlMesh(Models.scenery(w))
        tractor = GlMesh(Models.tractor())
        wheel = GlMesh(Models.wheel())
        trailerChassis = GlMesh(Models.trailerChassis())
        trailerBox = GlMesh(Models.trailerBox())
        carBody = GlMesh(Models.carBody())
        carDetails = GlMesh(Models.carDetails())
        beacon = GlMesh(Models.beacon())
        ring = GlMesh(Models.ring(game.world.depots[0].radius))
        cockpit = GlMesh(Models.cockpit())
        steeringWheel = GlMesh(Models.steeringWheel())
        lastNanos = 0L
        camInit = false
        ready = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        Matrix.perspectiveM(proj, 0, 58f, width.toFloat() / max(1, height), 0.4f, 950f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else (now - lastNanos) / 1e9f
        lastNanos = now
        if (dt > 0f) fps = fps * 0.95f + (1f / dt) * 0.05f

        game.update(dt)
        val t = game.truck

        buildTruckMatrices(t)
        updateCamera(dt, t)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(shader.program)
        GLES20.glUniform3f(shader.uLightDir, light[0], light[1], light[2])
        GLES20.glUniform3f(shader.uFogColor, sky[0], sky[1], sky[2])
        GLES20.glUniform2f(shader.uFog, 160f, 700f)
        GLES20.glUniform3f(shader.uCamPos, camX, camY, camZ)

        // Mundo estático
        draw(terrain, identity)
        draw(road, identity)
        draw(scenery, identity)

        // Camión
        if (game.controls.cameraMode == 1) {
            draw(cockpit, truckM)
            Matrix.translateM(tmpM, 0, truckM, 0, 0.62f, 2.42f, 4.62f)
            Matrix.rotateM(tmpM, 0, -55f, 1f, 0f, 0f)
            Matrix.rotateM(tmpM, 0, deg(t.steer) * 14f, 0f, 1f, 0f)
            draw(steeringWheel, tmpM)
        } else draw(tractor, truckM)
        val w = Models.TRACTOR_WHEELS
        for (i in 0 until w.size / 2) {
            Matrix.translateM(tmpM, 0, truckM, 0, w[i * 2], Truck.WHEEL_RADIUS, w[i * 2 + 1])
            if (i < 2) Matrix.rotateM(tmpM, 0, deg(-t.steer), 0f, 1f, 0f)
            Matrix.rotateM(tmpM, 0, deg(t.wheelSpin), 1f, 0f, 0f)
            draw(wheel, tmpM)
        }
        draw(trailerChassis, trailerM)
        if (t.cargoMass > 0f) {
            val c = game.missions.cargo.color
            draw(trailerBox, trailerM, MeshBuilder.r(c) * 0.5f + 0.5f, MeshBuilder.g(c) * 0.5f + 0.5f, MeshBuilder.b(c) * 0.5f + 0.5f)
        } else {
            draw(trailerBox, trailerM, 0.86f, 0.86f, 0.88f)
        }
        val tw = Models.TRAILER_WHEELS
        for (i in 0 until tw.size / 2) {
            Matrix.translateM(tmpM, 0, trailerM, 0, tw[i * 2], Truck.WHEEL_RADIUS, tw[i * 2 + 1])
            Matrix.rotateM(tmpM, 0, deg(t.wheelSpin), 1f, 0f, 0f)
            draw(wheel, tmpM)
        }

        // Tráfico
        for (c in game.traffic.cars) {
            Matrix.setIdentityM(tmpM, 0)
            Matrix.translateM(tmpM, 0, c.x, c.y + 0.03f, c.z)
            Matrix.rotateM(tmpM, 0, deg(c.heading), 0f, 1f, 0f)
            draw(carBody, tmpM, MeshBuilder.r(c.color), MeshBuilder.g(c.color), MeshBuilder.b(c.color))
            draw(carDetails, tmpM)
        }

        // Baliza del objetivo
        val target = game.missions.target
        val time = game.time
        Matrix.setIdentityM(tmpM, 0)
        Matrix.translateM(tmpM, 0, target.x, target.y + 0.5f + sin(time * 2.5f) * 0.6f, target.z)
        Matrix.rotateM(tmpM, 0, time * 70f, 0f, 1f, 0f)
        draw(beacon, tmpM)
        Matrix.setIdentityM(tmpM, 0)
        Matrix.translateM(tmpM, 0, target.x, target.y + 0.09f, target.z)
        val loading = game.missions.state == MissionState.LOADING || game.missions.state == MissionState.UNLOADING
        val pulse = if (loading) 0.55f + 0.45f * game.missions.timer / 3f else 1f
        Matrix.scaleM(tmpM, 0, pulse, 1f, pulse)
        draw(ring, tmpM)
    }

    private fun draw(mesh: GlMesh, model: FloatArray, r: Float = 1f, g: Float = 1f, b: Float = 1f) {
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(shader.uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(shader.uModel, 1, false, model, 0)
        GLES20.glUniform3f(shader.uColorMul, r, g, b)
        mesh.draw(shader)
    }

    private fun buildTruckMatrices(t: Truck) {
        Matrix.setIdentityM(truckM, 0)
        Matrix.translateM(truckM, 0, t.x, t.y, t.z)
        Matrix.rotateM(truckM, 0, deg(t.heading), 0f, 1f, 0f)
        Matrix.rotateM(truckM, 0, deg(-t.pitch), 1f, 0f, 0f)
        Matrix.rotateM(truckM, 0, deg(t.roll), 0f, 0f, 1f)

        Matrix.setIdentityM(trailerM, 0)
        Matrix.translateM(trailerM, 0, t.kingpinX, t.trailerY, t.kingpinZ)
        Matrix.rotateM(trailerM, 0, deg(t.trailerHeading), 0f, 1f, 0f)
        Matrix.rotateM(trailerM, 0, deg(-t.trailerPitch), 1f, 0f, 0f)
        Matrix.rotateM(trailerM, 0, deg(t.trailerRoll), 0f, 0f, 1f)
    }

    private fun localToWorld(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        vecIn[0] = x; vecIn[1] = y; vecIn[2] = z; vecIn[3] = 1f
        Matrix.multiplyMV(vecOut, 0, m, 0, vecIn, 0)
        return vecOut
    }

    private fun updateCamera(dt: Float, t: Truck) {
        val mode = game.controls.cameraMode
        val k = 1f - exp(-3.2f * dt)
        if (!camInit) camYaw = t.heading
        // En marcha atrás la cámara no gira bruscamente: sigue al remolque
        val follow = if (t.speed < -0.5f) t.trailerHeading else t.heading
        camYaw += wrapAngle(follow - camYaw) * if (camInit) k else 1f
        val fx = sin(camYaw); val fz = cos(camYaw)

        var lookX: Float; var lookY: Float; var lookZ: Float
        var ex: Float; var ey: Float; var ez: Float
        when (mode) {
            1 -> {
                val e = localToWorld(truckM, 0.62f, 2.72f, 4.3f)
                ex = e[0]; ey = e[1]; ez = e[2]
                val l = localToWorld(truckM, 0.62f, 2.0f, 30f)
                lookX = l[0]; lookY = l[1]; lookZ = l[2]
                camX = ex; camY = ey; camZ = ez
            }
            else -> {
                val dist = if (mode == 0) 19f else 34f
                val height = if (mode == 0) 7.5f else 15f
                val side = if (mode == 0) 0f else 0.55f
                val yaw = camYaw + side
                ex = t.x - sin(yaw) * dist
                ez = t.z - cos(yaw) * dist
                ey = t.y + height
                val ground = game.world.heightAt(ex, ez) + 1.5f
                if (ey < ground) ey = ground
                val pk = if (camInit) 1f - exp(-7f * dt) else 1f
                camX += (ex - camX) * pk; camY += (ey - camY) * pk; camZ += (ez - camZ) * pk
                lookX = t.x + fx * 5f; lookY = t.y + 2.6f; lookZ = t.z + fz * 5f
            }
        }
        camInit = true
        var sx = 0f; var sy = 0f
        if (t.impactTimer > 0f) {
            val a = t.impactTimer * 0.5f * minOf(t.lastImpact, 10f) * 0.05f
            sx = (rnd.nextFloat() - 0.5f) * a; sy = (rnd.nextFloat() - 0.5f) * a
        }
        if (!t.onRoad && kotlin.math.abs(t.speed) > 2f && mode == 1) sy += (rnd.nextFloat() - 0.5f) * 0.04f
        Matrix.setLookAtM(view, 0, camX + sx, camY + sy, camZ, lookX, lookY, lookZ, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
    }

    private fun deg(rad: Float) = rad * 57.29578f
}
