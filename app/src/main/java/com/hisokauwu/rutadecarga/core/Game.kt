package com.hisokauwu.rutadecarga.core

/** Entradas del jugador. Se escriben desde el hilo de UI y se leen en el hilo de render. */
class Controls {
    @Volatile var throttle = 0f
    @Volatile var brake = 0f
    @Volatile var steer = 0f
    @Volatile var cameraMode = 0   // 0 = persecución, 1 = cabina, 2 = lejana
}

/** Estado completo del juego y bucle de simulación a paso fijo. */
class Game(seed: Long = System.nanoTime()) {
    val world = World()
    val truck = Truck(world)
    val traffic = Traffic(world)
    val missions = Missions(world, truck, seed)
    val controls = Controls()

    @Volatile var time = 0f; private set
    private var accumulator = 0f
    private val step = 1f / 120f

    init {
        Spawn.atDepot(world, truck, world.depots[0])
        missions.newMission(world.depots[0])
        missions.say("Bienvenido. Ve al depósito ${missions.from.name} (marcador amarillo) y detente para cargar.", 7f)
    }

    fun update(frameDt: Float) {
        accumulator += clamp(frameDt, 0f, 0.1f)
        while (accumulator >= step) {
            truck.update(step, controls.throttle, controls.brake, controls.steer)
            traffic.update(step, truck)
            missions.update(step)
            time += step
            accumulator -= step
        }
    }
}
