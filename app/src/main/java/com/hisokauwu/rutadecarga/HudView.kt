package com.hisokauwu.rutadecarga

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.hisokauwu.rutadecarga.core.Game
import com.hisokauwu.rutadecarga.core.MissionState
import com.hisokauwu.rutadecarga.core.PI_F
import com.hisokauwu.rutadecarga.core.Truck
import com.hisokauwu.rutadecarga.core.clamp
import com.hisokauwu.rutadecarga.core.length2
import com.hisokauwu.rutadecarga.core.wrapAngle
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Interfaz sobre el 3D: volante táctil, pedales, velocímetro, minimapa e información de la misión.
 */
class HudView(context: Context, private val game: Game) : View(context) {

    /** Si está activo, la dirección viene del acelerómetro. */
    var tiltEnabled = false
    @Volatile var tiltSteer = 0f
    var onTiltToggled: ((Boolean) -> Unit)? = null

    private val dp = resources.displayMetrics.density

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f * dp; typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f * dp }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f * dp; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f * dp; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 10, 14, 20) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    // Disposición
    private var wheelCx = 0f; private var wheelCy = 0f; private var wheelR = 0f
    private val gasRect = RectF(); private val brakeRect = RectF()
    private val camRect = RectF(); private val tiltRect = RectF()
    private val mapRect = RectF()
    private var mapPath = Path()
    private var mapScale = 1f

    // Estado del volante táctil
    private var wheelPointer = -1
    private var wheelAngle = 0f          // rad, positivo = derecha
    private var lastTouchAngle = 0f
    private val maxWheel = 1.35f * PI_F
    private val gasPointers = HashSet<Int>()
    private val brakePointers = HashSet<Int>()
    private var lastFrame = 0L

    init {
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = 18f * dp
        wheelR = min(h * 0.2f, 95f * dp)
        wheelCx = pad + wheelR * 1.15f
        wheelCy = h - pad - wheelR * 1.05f
        val pw = 64f * dp; val ph = min(h * 0.36f, 150f * dp)
        gasRect.set(w - pad - pw, h - pad - ph, w - pad, h - pad)
        brakeRect.set(gasRect.left - 16f * dp - pw * 1.15f, h - pad - ph * 0.72f, gasRect.left - 16f * dp, h - pad)
        val bw = 92f * dp; val bh = 36f * dp
        camRect.set(w / 2f - bw - 6f * dp, pad * 0.6f, w / 2f - 6f * dp, pad * 0.6f + bh)
        tiltRect.set(w / 2f + 6f * dp, pad * 0.6f, w / 2f + bw + 6f * dp, pad * 0.6f + bh)
        val ms = min(h * 0.38f, 170f * dp)
        mapRect.set(w - pad - ms, pad * 0.6f, w - pad, pad * 0.6f + ms)
        buildMapPath()
    }

    // Minimapa: +Z hacia arriba y +X hacia la izquierda (vista cenital coherente con el 3D)
    private fun mapX(x: Float) = mapRect.centerX() - x * mapScale
    private fun mapY(z: Float) = mapRect.centerY() - z * mapScale

    private fun buildMapPath() {
        val road = game.world.road
        mapScale = mapRect.width() * 0.5f / (game.world.mapRadius * 0.92f)
        val p = Path()
        p.moveTo(mapX(road.xs[0]), mapY(road.zs[0]))
        for (i in 4 until road.count step 4) p.lineTo(mapX(road.xs[i]), mapY(road.zs[i]))
        p.close()
        mapPath = p
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        val idx = e.actionIndex
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = e.getPointerId(idx)
                val x = e.getX(idx); val y = e.getY(idx)
                when {
                    camRect.contains(x, y) -> game.controls.cameraMode = (game.controls.cameraMode + 1) % 3
                    tiltRect.contains(x, y) -> { tiltEnabled = !tiltEnabled; onTiltToggled?.invoke(tiltEnabled) }
                    inflate(gasRect).contains(x, y) -> gasPointers.add(id)
                    inflate(brakeRect).contains(x, y) -> brakePointers.add(id)
                    !tiltEnabled && length2(x - wheelCx, y - wheelCy) < wheelR * 1.5f && wheelPointer == -1 -> {
                        wheelPointer = id
                        lastTouchAngle = atan2(y - wheelCy, x - wheelCx)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val x = e.getX(i); val y = e.getY(i)
                    if (id == wheelPointer) {
                        val a = atan2(y - wheelCy, x - wheelCx)
                        // Cerca del centro el ángulo es inestable: se ignora
                        if (length2(x - wheelCx, y - wheelCy) > wheelR * 0.25f) {
                            wheelAngle = clamp(wheelAngle + wrapAngle(a - lastTouchAngle), -maxWheel, maxWheel)
                        }
                        lastTouchAngle = a
                    } else {
                        // Permite deslizar el dedo entre pedales
                        val onGas = inflate(gasRect).contains(x, y)
                        val onBrake = inflate(brakeRect).contains(x, y)
                        if (id in gasPointers && !onGas && onBrake) { gasPointers.remove(id); brakePointers.add(id) }
                        if (id in brakePointers && !onBrake && onGas) { brakePointers.remove(id); gasPointers.add(id) }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> release(e.getPointerId(idx))
            MotionEvent.ACTION_CANCEL -> { wheelPointer = -1; gasPointers.clear(); brakePointers.clear() }
        }
        pushControls()
        return true
    }

    private val tmpRect = RectF()
    private fun inflate(r: RectF): RectF { tmpRect.set(r.left - 10 * dp, r.top - 20 * dp, r.right + 10 * dp, r.bottom + 10 * dp); return tmpRect }

    private fun release(id: Int) {
        if (id == wheelPointer) wheelPointer = -1
        gasPointers.remove(id); brakePointers.remove(id)
    }

    private fun pushControls() {
        val c = game.controls
        c.throttle = if (gasPointers.isNotEmpty()) 1f else 0f
        c.brake = if (brakePointers.isNotEmpty()) 1f else 0f
        c.steer = if (tiltEnabled) tiltSteer else clamp(wheelAngle / maxWheel, -1f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceAtMost(0.1f)
        lastFrame = now
        // Autocentrado del volante al soltarlo
        if (wheelPointer == -1) wheelAngle *= exp(-5f * dt)
        if (tiltEnabled) wheelAngle = tiltSteer * maxWheel
        pushControls()

        val t = game.truck
        drawMissionPanel(canvas, t)
        drawMinimap(canvas, t)
        drawButtons(canvas)
        drawWheel(canvas)
        drawPedals(canvas)
        drawSpeedo(canvas, t)
        drawMessage(canvas)
        postInvalidateOnAnimation()
    }

    private fun drawMissionPanel(c: Canvas, t: Truck) {
        val m = game.missions
        val pad = 12f * dp
        val w = 300f * dp
        val lh = 19f * dp
        c.drawRoundRect(pad, pad, pad + w, pad + lh * 5.4f, 10f * dp, 10f * dp, panel)
        var y = pad + lh
        val x = pad + 10f * dp
        text.color = Color.rgb(140, 230, 120)
        c.drawText("$ ${m.money}   ·   Entregas: ${m.deliveries}", x, y, text)
        text.color = Color.WHITE
        y += lh
        val target = m.target
        val dist = length2(target.x - t.x, target.z - t.z)
        val line = when (m.state) {
            MissionState.TO_PICKUP -> "Recoge ${m.cargo.name} en ${m.from.name}"
            MissionState.LOADING -> "Cargando… ${(m.timer / 3f * 100).roundToInt()}%"
            MissionState.DELIVERING -> "Entrega ${m.cargo.name} en ${m.to.name}"
            MissionState.UNLOADING -> "Descargando… ${(m.timer / 3f * 100).roundToInt()}%"
        }
        c.drawText(line, x, y, text)
        y += lh
        val distTxt = if (dist > 1000f) "%.1f km".format(dist / 1000f) else "${dist.roundToInt()} m"
        small.color = Color.rgb(220, 220, 220)
        val extra = if (m.state == MissionState.DELIVERING) {
            val left = (m.parTime - m.timer).roundToInt()
            if (left > 0) " · bonus: ${left / 60}:${"%02d".format(left % 60)}" else " · sin bonus"
        } else " · pago ≈ $${m.estimatedPay()}"
        c.drawText("Destino a $distTxt$extra", x, y, small)
        y += lh
        val load = if (t.cargoMass > 0f) "${(t.cargoMass / 1000f).roundToInt()} t" else "vacío"
        c.drawText("Carga: $load   ·   Peso total ${(t.totalMass / 1000f).roundToInt()} t", x, y, small)
        y += lh * 0.55f
        // Daños
        val bw = w - 20f * dp
        fill.color = Color.argb(160, 60, 60, 60)
        c.drawRect(x, y, x + bw, y + 7f * dp, fill)
        val d = t.damage / 100f
        fill.color = Color.rgb((80 + 175 * d).toInt(), (200 * (1 - d)).toInt(), 60)
        c.drawRect(x, y, x + bw * d, y + 7f * dp, fill)
        small.color = Color.WHITE
        c.drawText("Daños ${t.damage.roundToInt()}%" + if (!t.onRoad) "   ·   ¡Fuera de la carretera!" else "", x, y + 20f * dp, small)

        // Flecha de navegación
        val ang = atan2(target.x - t.x, target.z - t.z)
        val rel = wrapAngle(ang - t.heading)   // positivo = a la izquierda
        val ax = pad + w + 34f * dp; val ay = pad + 34f * dp
        c.drawCircle(ax, ay, 26f * dp, panel)
        c.save()
        c.rotate(-rel * 57.29578f, ax, ay)
        val arrow = Path().apply {
            moveTo(ax, ay - 18f * dp); lineTo(ax + 11f * dp, ay + 12f * dp); lineTo(ax, ay + 6f * dp); lineTo(ax - 11f * dp, ay + 12f * dp); close()
        }
        fill.color = Color.rgb(255, 208, 42)
        c.drawPath(arrow, fill)
        c.restore()
    }

    private fun drawMinimap(c: Canvas, t: Truck) {
        c.drawRoundRect(mapRect, 12f * dp, 12f * dp, panel)
        c.save()
        c.clipRect(mapRect)
        stroke.color = Color.rgb(90, 90, 96); stroke.strokeWidth = 5f * dp
        c.drawPath(mapPath, stroke)
        stroke.color = Color.rgb(200, 200, 200); stroke.strokeWidth = 1.5f * dp
        c.drawPath(mapPath, stroke)
        val target = game.missions.target
        for (d in game.world.depots) {
            val isT = d === target
            fill.color = if (isT) Color.rgb(255, 208, 42) else (d.color or 0xFF000000.toInt())
            c.drawCircle(mapX(d.x), mapY(d.z), (if (isT) 6f else 4f) * dp, fill)
            small.color = if (isT) Color.rgb(255, 230, 120) else Color.rgb(230, 230, 230)
            c.drawText(d.name, mapX(d.x) + 6f * dp, mapY(d.z) - 4f * dp, small)
        }
        for (car in game.traffic.cars) {
            fill.color = Color.rgb(180, 180, 255)
            c.drawCircle(mapX(car.x), mapY(car.z), 1.8f * dp, fill)
        }
        // Jugador
        val px = mapX(t.x); val py = mapY(t.z)
        c.save()
        // En pantalla, el avance (sin h, cos h) es (-sin h, -cos h) → ángulo canvas = -h
        c.rotate(-t.heading * 57.29578f, px, py)
        val tri = Path().apply { moveTo(px, py - 8f * dp); lineTo(px + 5f * dp, py + 5f * dp); lineTo(px - 5f * dp, py + 5f * dp); close() }
        fill.color = Color.rgb(255, 80, 60)
        c.drawPath(tri, fill)
        c.restore()
        c.restore()
    }

    private fun drawButtons(c: Canvas) {
        val names = arrayOf("Cám: Detrás", "Cám: Cabina", "Cám: Lejos")
        button(c, camRect, names[game.controls.cameraMode], false)
        button(c, tiltRect, if (tiltEnabled) "Inclinar: SÍ" else "Inclinar: NO", tiltEnabled)
    }

    private fun button(c: Canvas, r: RectF, label: String, on: Boolean) {
        fill.color = if (on) Color.argb(190, 40, 120, 200) else Color.argb(150, 10, 14, 20)
        c.drawRoundRect(r, 10f * dp, 10f * dp, fill)
        c.drawText(label, r.centerX(), r.centerY() + center.textSize * 0.35f, center)
    }

    private fun drawWheel(c: Canvas) {
        val cx = wheelCx; val cy = wheelCy; val r = wheelR
        val alpha = if (tiltEnabled) 110 else 200
        stroke.strokeWidth = r * 0.16f
        stroke.color = Color.argb(alpha, 30, 30, 34)
        c.drawCircle(cx, cy, r, stroke)
        stroke.strokeWidth = r * 0.08f
        stroke.color = Color.argb(alpha, 90, 90, 96)
        c.drawCircle(cx, cy, r, stroke)
        c.save()
        c.rotate(wheelAngle * 57.29578f, cx, cy)
        stroke.strokeWidth = r * 0.12f
        stroke.color = Color.argb(alpha, 40, 40, 44)
        c.drawLine(cx - r, cy, cx + r, cy, stroke)
        c.drawLine(cx, cy, cx, cy + r, stroke)
        fill.color = Color.argb(alpha, 200, 40, 40)
        c.drawCircle(cx, cy - r, r * 0.1f, fill)   // marca de centro
        fill.color = Color.argb(alpha, 60, 60, 66)
        c.drawCircle(cx, cy, r * 0.28f, fill)
        c.restore()
        if (tiltEnabled) c.drawText("Inclina el móvil", cx, cy + r + 18f * dp, center)
    }

    private fun drawPedals(c: Canvas) {
        pedal(c, brakeRect, "FRENO", gasOrBrakeActive(brakePointers), Color.rgb(200, 60, 50))
        pedal(c, gasRect, "GAS", gasOrBrakeActive(gasPointers), Color.rgb(60, 170, 80))
        if (game.truck.gear < 0) {
            center.color = Color.rgb(255, 200, 80)
            c.drawText("MARCHA ATRÁS: el freno acelera hacia atrás", (brakeRect.left + gasRect.right) / 2f, brakeRect.top - 30f * dp, center)
            center.color = Color.WHITE
        }
    }

    private fun gasOrBrakeActive(s: Set<Int>) = s.isNotEmpty()

    private fun pedal(c: Canvas, r: RectF, label: String, pressed: Boolean, color: Int) {
        fill.color = if (pressed) color else Color.argb(150, Color.red(color) / 2, Color.green(color) / 2, Color.blue(color) / 2)
        c.drawRoundRect(r, 12f * dp, 12f * dp, fill)
        stroke.strokeWidth = 2f * dp
        stroke.color = Color.argb(120, 255, 255, 255)
        var y = r.top + 14f * dp
        while (y < r.bottom - 30f * dp) { c.drawLine(r.left + 12f * dp, y, r.right - 12f * dp, y, stroke); y += 12f * dp }
        c.drawText(label, r.centerX(), r.bottom - 12f * dp, center)
    }

    private fun drawSpeedo(c: Canvas, t: Truck) {
        val cx = width / 2f
        val r = min(height * 0.15f, 70f * dp)
        val cy = height - r - 16f * dp
        c.drawCircle(cx, cy, r * 1.12f, panel)
        // Arco de velocidad 0..120
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        stroke.strokeWidth = r * 0.1f
        stroke.color = Color.argb(120, 255, 255, 255)
        c.drawArc(oval, 135f, 270f, false, stroke)
        val kmh = kotlin.math.abs(t.speedKmh)
        stroke.color = if (kmh > 88f) Color.rgb(255, 120, 60) else Color.rgb(90, 200, 255)
        c.drawArc(oval, 135f, 270f * clamp(kmh / 120f, 0f, 1f), false, stroke)
        // RPM
        val inner = RectF(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.78f, cy + r * 0.78f)
        stroke.strokeWidth = r * 0.06f
        val rpmF = clamp(t.rpm / Truck.MAX_RPM, 0f, 1f)
        stroke.color = if (t.rpm > 1900f) Color.rgb(255, 90, 70) else Color.rgb(255, 210, 90)
        c.drawArc(inner, 135f, 270f * rpmF, false, stroke)
        big.textSize = r * 0.55f
        c.drawText("${kmh.roundToInt()}", cx, cy + r * 0.12f, big)
        c.drawText("km/h", cx, cy + r * 0.42f, center)
        center.color = if (t.inShift) Color.rgb(255, 210, 90) else Color.WHITE
        c.drawText(t.gearLabel() + "  ·  ${(t.rpm / 100f).roundToInt() * 100} rpm", cx, cy + r * 0.78f, center)
        center.color = Color.WHITE
        // Indicador de tracción/freno sobre el velocímetro
        small.color = Color.rgb(200, 200, 200)
        c.drawText("Odómetro ${"%.1f".format(t.odometer / 1000f)} km", cx - r, cy - r * 1.22f, small)
    }

    private fun drawMessage(c: Canvas) {
        val m = game.missions
        if (m.messageTime <= 0f || m.message.isEmpty()) return
        val a = (clamp(m.messageTime, 0f, 1f) * 255).toInt()
        text.textAlign = Paint.Align.CENTER
        val w = text.measureText(m.message) + 30f * dp
        val y = height * 0.3f
        fill.color = Color.argb(a * 170 / 255, 10, 14, 20)
        c.drawRoundRect(width / 2f - w / 2, y - 26f * dp, width / 2f + w / 2, y + 12f * dp, 10f * dp, 10f * dp, fill)
        text.color = Color.argb(a, 255, 230, 140)
        c.drawText(m.message, width / 2f, y, text)
        text.color = Color.WHITE
        text.textAlign = Paint.Align.LEFT
    }
}
