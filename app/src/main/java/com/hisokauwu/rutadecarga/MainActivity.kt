package com.hisokauwu.rutadecarga

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.hisokauwu.rutadecarga.core.Game
import com.hisokauwu.rutadecarga.core.clamp
import com.hisokauwu.rutadecarga.render.GameRenderer
import kotlin.math.abs

class MainActivity : Activity(), SensorEventListener {
    private lateinit var game: Game
    private lateinit var glView: GLSurfaceView
    private lateinit var hud: HudView
    private lateinit var sound: EngineSound
    private var sensors: SensorManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        game = Game()
        val prefs = getSharedPreferences("partida", Context.MODE_PRIVATE)
        game.missions.money = prefs.getInt("dinero", game.missions.money)
        game.missions.deliveries = prefs.getInt("entregas", 0)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            preserveEGLContextOnPause = true
            setRenderer(GameRenderer(game))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        hud = HudView(this, game)
        hud.onTiltToggled = { enabled -> if (enabled) registerTilt() else unregisterTilt() }

        val root = FrameLayout(this)
        root.addView(glView)
        root.addView(hud)
        setContentView(root)

        sensors = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sound = EngineSound(game.truck)
        hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        sound.start()
        if (hud.tiltEnabled) registerTilt()
        hideSystemUi()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        sound.stop()
        unregisterTilt()
        getSharedPreferences("partida", Context.MODE_PRIVATE).edit()
            .putInt("dinero", game.missions.money)
            .putInt("entregas", game.missions.deliveries)
            .apply()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun registerTilt() {
        val s = sensors ?: return
        val acc = s.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        s.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun unregisterTilt() {
        sensors?.unregisterListener(this)
        hud.tiltSteer = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        // En horizontal, girar el móvil como un volante cambia la gravedad en el eje Y del dispositivo
        val raw = when (rotation) {
            Surface.ROTATION_270 -> -event.values[1]
            Surface.ROTATION_90 -> event.values[1]
            else -> event.values[0]
        }
        var s = raw / 4.5f
        if (abs(s) < 0.04f) s = 0f
        hud.tiltSteer = clamp(s, -1f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }
}
