package com.hisokauwu.rutadecarga.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

class Cargo(val name: String, val mass: Float, val payPerKm: Float, val fragility: Float, val color: Int)

enum class MissionState { TO_PICKUP, LOADING, DELIVERING, UNLOADING }

/** Gestiona encargos: recoger en un depósito y entregar en otro. */
class Missions(private val world: World, private val truck: Truck, seed: Long = System.nanoTime()) {
    companion object {
        val CARGOS = listOf(
            Cargo("Madera", 18000f, 900f, 0.5f, 0xFFB0814F.toInt()),
            Cargo("Contenedores", 22000f, 1050f, 0.7f, 0xFF3C78C8.toInt()),
            Cargo("Fruta fresca", 14000f, 1200f, 1.6f, 0xFF66B84A.toInt()),
            Cargo("Combustible", 24000f, 1400f, 1.2f, 0xFFE0E0E0.toInt()),
            Cargo("Electrónica", 9000f, 1500f, 2.5f, 0xFF404048.toInt()),
            Cargo("Acero", 26000f, 1150f, 0.3f, 0xFF8A8F96.toInt()),
            Cargo("Maquinaria", 20000f, 1300f, 1.0f, 0xFFE8A020.toInt()),
        )
        const val LOAD_TIME = 3f
        const val TOW_FEE = 600
    }

    private val rnd = Random(seed)

    @Volatile var state = MissionState.TO_PICKUP; private set
    @Volatile var money = 2000
    @Volatile var deliveries = 0
    var from: Depot = world.depots[0]; private set
    var to: Depot = world.depots[1]; private set
    var cargo: Cargo = CARGOS[0]; private set
    var timer = 0f; private set          // tiempo en ruta o progreso de carga
    var parTime = 0f; private set
    private var damageAtLoad = 0f

    @Volatile var message = ""; private set
    @Volatile var messageTime = 0f; private set

    val target: Depot get() = if (state == MissionState.TO_PICKUP || state == MissionState.LOADING) from else to

    fun say(text: String, seconds: Float = 4f) {
        message = text
        messageTime = seconds
    }

    fun newMission(pickup: Depot) {
        from = pickup
        val candidates = world.depots.filter {
            it !== pickup && world.road.roadDistance(it.roadIndex, pickup.roadIndex) > 400f
        }
        to = candidates[rnd.nextInt(candidates.size)]
        cargo = CARGOS[rnd.nextInt(CARGOS.size)]
        state = MissionState.TO_PICKUP
        timer = 0f
        val km = routeKm()
        parTime = km * 1000f / 13f + 30f
    }

    fun routeKm(): Float = world.road.roadDistance(from.roadIndex, to.roadIndex) / 1000f + 0.06f

    fun estimatedPay(): Int = (cargo.payPerKm * routeKm()).roundToInt() + 300

    fun update(dt: Float) {
        if (messageTime > 0f) messageTime -= dt
        val inZone = { d: Depot -> length2(truck.x - d.x, truck.z - d.z) < d.radius ||
            length2(truck.trailerAxleX - d.x, truck.trailerAxleZ - d.z) < d.radius * 0.6f }
        val stopped = abs(truck.speed) < 0.8f

        when (state) {
            MissionState.TO_PICKUP -> {
                if (inZone(from) && stopped) { state = MissionState.LOADING; timer = 0f; say("Cargando ${cargo.name}…", 3f) }
            }
            MissionState.LOADING -> {
                if (!inZone(from) || !stopped) { state = MissionState.TO_PICKUP; say("Carga cancelada: detén el camión en la zona") }
                else {
                    timer += dt
                    if (timer >= LOAD_TIME) {
                        truck.cargoMass = cargo.mass
                        damageAtLoad = truck.damage
                        state = MissionState.DELIVERING
                        timer = 0f
                        say("Carga lista: lleva ${cargo.name} a ${to.name}", 5f)
                    }
                }
            }
            MissionState.DELIVERING -> {
                timer += dt
                if (inZone(to) && stopped) { state = MissionState.UNLOADING; timer = 0f; say("Descargando…", 3f) }
            }
            MissionState.UNLOADING -> {
                if (!inZone(to) || !stopped) { state = MissionState.DELIVERING; say("Descarga cancelada") }
                else {
                    timer += dt
                    if (timer >= LOAD_TIME) completeDelivery()
                }
            }
        }

        if (truck.damage >= 100f) tow()
    }

    /** Tiempo empleado en la entrega actual (solo válido en DELIVERING/UNLOADING). */
    var routeTime = 0f; private set

    private fun completeDelivery() {
        val base = estimatedPay()
        val dmg = max(0f, truck.damage - damageAtLoad)
        val dmgPenalty = (base * clamp(dmg * cargo.fragility / 100f, 0f, 0.9f)).roundToInt()
        val elapsed = timer
        routeTime = elapsed
        val timeBonus = if (elapsed < parTime) (base * 0.25f).roundToInt() else 0
        val pay = base - dmgPenalty + timeBonus
        money += pay
        deliveries++
        truck.cargoMass = 0f
        val repair = (truck.damage * 12f).roundToInt()
        val repaired = repair > 0 && money >= repair
        if (repaired) { money -= repair; truck.damage = 0f }
        val sb = StringBuilder("¡Entregado! +$$pay")
        if (timeBonus > 0) sb.append(" (bonus a tiempo)")
        if (dmgPenalty > 0) sb.append(" · daños -$$dmgPenalty")
        if (repaired) sb.append(" · reparación -$$repair")
        say(sb.toString(), 6f)
        newMission(to)
    }

    private fun tow() {
        val d = world.depots.minByOrNull { length2(it.x - truck.x, it.z - truck.z) }!!
        money = max(0, money - TOW_FEE)
        truck.damage = 0f
        truck.cargoMass = 0f
        Spawn.atDepot(world, truck, d)
        say("Camión averiado: grúa hasta ${d.name} (-$$TOW_FEE). Carga perdida.", 6f)
        newMission(d)
    }
}

object Spawn {
    /** Coloca el camión en el carril derecho junto a un depósito, mirando en el sentido de la carretera. */
    fun atDepot(world: World, truck: Truck, d: Depot) {
        val road = world.road
        val idx = road.index(d.roadIndex - 12)
        val fx = road.txs[idx]; val fz = road.tzs[idx]
        val px = road.xs[idx] + Dir.rightX(fx, fz) * 1.9f
        val pz = road.zs[idx] + Dir.rightZ(fx, fz) * 1.9f
        truck.reset(px, pz, world.roadHeading(idx))
    }
}
