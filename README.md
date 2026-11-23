# Ruta de Carga 3D

Simulador de camiones en 3D para Android, escrito en **Kotlin nativo + OpenGL ES 2.0**, sin motores externos.

## Qué incluye

- **Mundo 3D procedural**: terreno low-poly con colinas, una carretera de ~3,5 km en circuito con pendientes reales, bosques, montañas en el borde y 6 depósitos (Puerto, Aserradero, Granja, Fábrica, Mina, Ciudad).
- **Tractora + semirremolque articulado**: el remolque sigue al pivote de verdad (hay que abrir las curvas y maniobrar marcha atrás), con límite de tijera.
- **Física de conducción**: motor con curva de par, caja automática de 10 marchas, limitador a 90 km/h, peso de la carga, pendientes, resistencia aerodinámica y de rodadura, frenado, marcha atrás y peor agarre fuera del asfalto.
- **Colisiones** con árboles, edificios y tráfico, con sistema de **daños**.
- **Tráfico**: coches que circulan por su carril y frenan ante obstáculos.
- **Misiones**: recoger una carga en un depósito y entregarla en otro. Se paga por km, con bonus por llegar a tiempo y penalización por daños según la fragilidad de la carga. Reparación automática al entregar y grúa si el camión queda destrozado.
- **Controles táctiles**: volante que se gira con el dedo (o inclinando el móvil), pedales de gas y freno, 3 cámaras (detrás, cabina con volante animado, lejana).
- **HUD**: velocímetro, rpm, marcha, minimapa, flecha de navegación, daños y dinero (se guarda entre partidas).
- **Sonido de motor diésel** sintetizado en tiempo real según rpm y acelerador.

## Cómo jugar

| Acción | Control |
|---|---|
| Acelerar | Pedal **GAS** (derecha) |
| Frenar | Pedal **FRENO** |
| Marcha atrás | Detente y mantén **FRENO**; en marcha atrás el freno acelera hacia atrás y el gas frena |
| Girar | Arrastra el **volante** (izquierda) en círculo, o activa **Inclinar** |
| Cámara | Botón **Cám** arriba |

Sigue la flecha amarilla y el minimapa hasta el depósito marcado, entra en el círculo amarillo y **detente** para cargar o descargar.

## Compilar

### Con Android Studio
1. Abre la carpeta del proyecto en Android Studio (Ladybug o más reciente).
2. Conecta tu móvil con la depuración USB activada y pulsa **Run**.

### Desde la línea de comandos
```bash
./gradlew assembleDebug        # genera app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # pruebas de la simulación
```

### Sin instalar nada
Cada `push` ejecuta GitHub Actions (`.github/workflows/android.yml`), que pasa las pruebas y compila el APK. Descárgalo desde la pestaña **Actions → Compilar APK → Artifacts**, cópialo al móvil e instálalo (hay que permitir "instalar apps de origen desconocido").

## Estructura

```
app/src/main/java/com/hisokauwu/rutadecarga/
├── MainActivity.kt        Actividad, sensores, guardado de partida
├── HudView.kt             Interfaz táctil (volante, pedales, minimapa…)
├── EngineSound.kt         Síntesis del sonido del motor
├── core/                  Lógica pura en Kotlin (sin Android, con pruebas)
│   ├── World.kt           Terreno, depósitos, árboles y edificios
│   ├── Road.kt            Carretera (spline Catmull-Rom) y búsquedas espaciales
│   ├── Truck.kt           Física del camión y remolque, colisiones
│   ├── Traffic.kt         Coches de IA
│   ├── Missions.kt        Encargos, pagos, daños, grúa
│   └── Game.kt            Bucle de simulación a paso fijo (120 Hz)
└── render/
    ├── MeshBuilder.kt     Construcción de geometría low-poly
    ├── Models.kt          Modelos procedurales (camión, terreno, carretera…)
    ├── GlMesh.kt          VBOs y shader con luz y niebla
    └── GameRenderer.kt    Render OpenGL ES y cámaras
```

## Ideas para seguir

- Gasolineras y consumo de combustible
- Ciclo día/noche con faros
- Más tipos de remolque (cisterna, plataforma con carga visible)
- Tienda para mejorar el camión con el dinero ganado
- Lluvia y carreteras mojadas
