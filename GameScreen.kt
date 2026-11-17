package com.example.thelastlantern

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

private val Road = Color(0xFF292F38)
private val Grass = Color(0xFF143D2D)
private val Yellow = Color(0xFFFFD447)
private val Mint = Color(0xFF27D6AE)

// Target simulation step: 16 ms, matching the original delay(16) update rate.
private const val TARGET_FRAME_NANOS = 16_000_000L
private const val MAX_FRAME_DELTA_NANOS = 64_000_000L

// Enemy car dimensions
private const val ENEMY_WIDTH = 80f
private const val ENEMY_HEIGHT = 115f

// PLAYER CAR - SLIGHTLY BIGGER
// Original PNG proportions are preserved.
private const val PLAYER_MAX_WIDTH = 85f
private const val PLAYER_MAX_HEIGHT = 137f

private const val PLAYER_Y = 555f
private const val UNLOCK_STEP = 15

private data class EnemyCar(
    val lane: Int,
    var y: Float,
    val image: ImageBitmap
)

private data class PixelParticle(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val size: Float,
    val color: Color,
    val life: Float
)

@Composable
fun GameScreen() {

    val context = LocalContext.current

    val preferences = remember(context) {
        context.getSharedPreferences(
            "pixel_traffic_progress",
            Context.MODE_PRIVATE
        )
    }

    // ------------------------------------------------
    // LOAD PLAYER PNG IMAGES
    // ------------------------------------------------

    val playerImages = remember(context) {

        val fallback = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.player_car
        ).asImageBitmap()

        (1..5).map { index ->

            val resourceId = context.resources.getIdentifier(
                "player_car_$index",
                "drawable",
                context.packageName
            )

            if (resourceId != 0) {

                BitmapFactory.decodeResource(
                    context.resources,
                    resourceId
                )?.asImageBitmap() ?: fallback

            } else {

                fallback
            }
        }
    }

    // ------------------------------------------------
    // LOAD ENEMY PNG IMAGES
    // ------------------------------------------------

    val enemyCarImages = remember(context, playerImages) {

        (1..15).map { index ->

            val resourceId = context.resources.getIdentifier(
                "enemy_car_$index",
                "drawable",
                context.packageName
            )

            if (resourceId != 0) {

                BitmapFactory.decodeResource(
                    context.resources,
                    resourceId
                )?.asImageBitmap() ?: playerImages[0]

            } else {

                playerImages[0]
            }
        }
    }

    // ------------------------------------------------
    // GAME STATES
    // ------------------------------------------------

    var playerLane by remember {
        mutableIntStateOf(1)
    }

    var score by remember {
        mutableIntStateOf(0)
    }

    var bestScore by remember {
        mutableIntStateOf(
            preferences.getInt("best_score", 0)
        )
    }

    var hasStarted by remember {
        mutableStateOf(false)
    }

    var showMenu by remember {
        mutableStateOf(true)
    }

    var showGarage by remember {
        mutableStateOf(false)
    }

    var showSplash by remember {
        mutableStateOf(true)
    }

    var selectedCarIndex by remember {
        mutableIntStateOf(
            preferences.getInt("selected_car", 0).coerceIn(0, 4)
        )
    }

    var roadOffset by remember {
        mutableFloatStateOf(0f)
    }

    var gameSpeed by remember {
        mutableFloatStateOf(7f)
    }

    var gameOver by remember {
        mutableStateOf(false)
    }

    val enemies = remember {
        mutableStateListOf<EnemyCar>()
    }

    // ------------------------------------------------
    // GARAGE
    // ------------------------------------------------

    val unlockedCarCount = (
            1 + bestScore / UNLOCK_STEP
            ).coerceIn(1, 5)

    val activeCarIndex = selectedCarIndex.coerceIn(
        0,
        unlockedCarCount - 1
    )

    val playerCarImage = playerImages[activeCarIndex]

    // ------------------------------------------------
    // PLAYER IMAGE DIMENSIONS
    // Preserve the original PNG aspect ratio.
    // ------------------------------------------------

    val playerImageScale = minOf(
        PLAYER_MAX_WIDTH / playerCarImage.width.toFloat(),
        PLAYER_MAX_HEIGHT / playerCarImage.height.toFloat()
    )

    val playerWidth = playerCarImage.width * playerImageScale
    val playerHeight = playerCarImage.height * playerImageScale

    // ------------------------------------------------
    // EXPLOSION
    // ------------------------------------------------

    var explosionActive by remember {
        mutableStateOf(false)
    }

    var explosionFrame by remember {
        mutableIntStateOf(0)
    }

    var crashOverlayVisible by remember {
        mutableStateOf(false)
    }

    // Particle data is only created once per crash. The animation now
    // advances one integer frame instead of rewriting all 55 particles
    // in the Compose SnapshotStateList every 16 ms.
    val explosionParticles = remember {
        mutableStateListOf<PixelParticle>()
    }

    // ------------------------------------------------
    // SPLASH
    // ------------------------------------------------

    LaunchedEffect(Unit) {
        delay(1800)
        showSplash = false
    }

    // ------------------------------------------------
    // PHONE VIBRATION
    // ------------------------------------------------

    @Suppress("DEPRECATION")
    fun vibrateOnCrash() {

        try {

            val vibrator = context.getSystemService(
                Context.VIBRATOR_SERVICE
            ) as? Vibrator

            if (vibrator?.hasVibrator() == true) {

                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        170,
                        100
                    )
                )
            }

        } catch (_: Exception) {
            // Ignore vibration errors.
        }
    }

    // ------------------------------------------------
    // PIXEL EXPLOSION CREATION
    // ------------------------------------------------

    fun createExplosion(x: Float, y: Float) {

        explosionParticles.clear()
        explosionFrame = 0

        val colors = listOf(
            Color(0xFFFFD447),
            Color(0xFFFF6B35),
            Color(0xFFFF3030),
            Color(0xFFFFA500),
            Color.White,
            Color(0xFF27D6AE),
            Color(0xFFB47CFF)
        )

        repeat(55) {

            val angle = Random.nextFloat() * (Math.PI.toFloat() * 2f)
            val speed = Random.nextFloat() * 8f + 2f

            explosionParticles.add(
                PixelParticle(
                    x = x + Random.nextFloat() * 20f - 10f,
                    y = y + Random.nextFloat() * 30f - 15f,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed,
                    size = Random.nextFloat() * 7f + 3f,
                    color = colors.random(),
                    life = 1f
                )
            )
        }
    }

    // ------------------------------------------------
    // RESTART
    // ------------------------------------------------

    fun restart() {

        playerLane = 1
        score = 0
        roadOffset = 0f
        gameSpeed = 7f

        enemies.clear()
        explosionParticles.clear()
        explosionFrame = 0

        explosionActive = false
        crashOverlayVisible = false
        gameOver = false
        hasStarted = true
        showMenu = false
        showGarage = false
    }

    // ------------------------------------------------
    // GAME LOOP
    // Frame-clock driven, with a fixed 16 ms simulation step.
    // Internal counters are ordinary local variables, not Compose State.
    // ------------------------------------------------

    LaunchedEffect(hasStarted, gameOver) {

        var spawnTimer = 0
        var nextEnemyImageIndex = 0
        var lastSpawnLane = -1

        var lastFrameNanos = 0L
        var accumulatedNanos = 0L

        while (hasStarted && !gameOver) {

            val frameNanos = withFrameNanos { it }

            val elapsedNanos = if (lastFrameNanos == 0L) {
                // First update starts on the first available frame,
                // matching the original first delay(16) update closely.
                TARGET_FRAME_NANOS
            } else {
                (frameNanos - lastFrameNanos).coerceAtLeast(0L)
            }

            lastFrameNanos = frameNanos

            // Avoid a large catch-up burst after a long frame stall.
            accumulatedNanos += elapsedNanos.coerceAtMost(
                MAX_FRAME_DELTA_NANOS
            )

            // Run simulation at the original 16 ms tick rate regardless
            // of whether the display refreshes at 60, 90, or 120 Hz.
            while (
                accumulatedNanos >= TARGET_FRAME_NANOS &&
                hasStarted &&
                !gameOver
            ) {

                accumulatedNanos -= TARGET_FRAME_NANOS

                roadOffset = (roadOffset + gameSpeed) % 100f

                spawnTimer++

                // --------------------------------------------
                // BALANCED TRAFFIC SPAWNING SYSTEM
                // --------------------------------------------

                val targetSpawnGap = 440f

                val spawnInterval = (
                        targetSpawnGap / gameSpeed
                        ).toInt().coerceIn(22, 63)

                if (spawnTimer >= spawnInterval) {

                    spawnTimer = 0

                    val doubleSpawnChance = (
                            0.25f + score / 500f
                            ).coerceAtMost(0.40f)

                    val spawnCount = if (
                        Random.nextFloat() < doubleSpawnChance
                    ) 2 else 1

                    val selectedLanes = if (spawnCount == 2) {

                        (0..2).shuffled().take(2)

                    } else {

                        listOf(
                            (0..2).filter {
                                it != lastSpawnLane
                            }.random()
                        )
                    }

                    selectedLanes.forEachIndexed { index, selectedLane ->

                        lastSpawnLane = selectedLane

                        val selectedImage = enemyCarImages[
                            nextEnemyImageIndex
                        ]

                        nextEnemyImageIndex = (
                                nextEnemyImageIndex + 1
                                ) % enemyCarImages.size

                        val verticalOffset = index * 190f

                        enemies.add(
                            EnemyCar(
                                lane = selectedLane,
                                y = -ENEMY_HEIGHT - 20f - verticalOffset,
                                image = selectedImage
                            )
                        )
                    }
                }

                // MOVE ENEMIES

                val iterator = enemies.iterator()

                while (iterator.hasNext()) {

                    val enemy = iterator.next()
                    enemy.y += gameSpeed

                    if (enemy.y > 730f) {

                        iterator.remove()
                        score++

                        bestScore = maxOf(bestScore, score)

                        preferences.edit()
                            .putInt("best_score", bestScore)
                            .apply()

                        gameSpeed = (
                                7f + score * 0.035f
                                ).coerceAtMost(18f)
                    }
                }

                // PLAYER POSITION

                val laneWidth = 340f / 3f

                val playerCenter =
                    30f + playerLane * laneWidth + laneWidth / 2f

                val playerLeft = playerCenter - playerWidth / 2f
                val playerRight = playerCenter + playerWidth / 2f

                val playerTop = PLAYER_Y
                val playerBottom = PLAYER_Y + playerHeight

                // COLLISION DETECTION

                val collision = enemies.any { enemy ->

                    val enemyCenter =
                        30f + enemy.lane * laneWidth + laneWidth / 2f

                    val enemyLeft = enemyCenter - ENEMY_WIDTH / 2f
                    val enemyRight = enemyCenter + ENEMY_WIDTH / 2f
                    val enemyTop = enemy.y
                    val enemyBottom = enemy.y + ENEMY_HEIGHT

                    playerLeft + 8f < enemyRight - 8f &&
                            playerRight - 8f > enemyLeft + 8f &&
                            playerTop + 10f < enemyBottom - 10f &&
                            playerBottom - 10f > enemyTop + 10f
                }

                // CRASH

                if (collision) {

                    createExplosion(
                        x = playerCenter,
                        y = PLAYER_Y + playerHeight / 2f
                    )

                    explosionActive = true
                    vibrateOnCrash()
                    gameOver = true

                    bestScore = maxOf(bestScore, score)

                    preferences.edit()
                        .putInt("best_score", bestScore)
                        .apply()
                }
            }
        }
    }

    // ------------------------------------------------
    // EXPLOSION ANIMATION
    // Only one Compose state value changes per animation tick.
    // Particle positions are calculated analytically in the Canvas,
    // preserving the original damping, gravity and lifetime progression.
    // ------------------------------------------------

    LaunchedEffect(explosionActive) {

        if (explosionActive) {

            repeat(45) { frame ->

                delay(16)
                explosionFrame = frame + 1
            }

            explosionParticles.clear()
            explosionFrame = 0
            explosionActive = false
        }
    }

    // ------------------------------------------------
    // GAME OVER DELAY
    // ------------------------------------------------

    LaunchedEffect(gameOver) {

        if (gameOver) {

            delay(850)
            crashOverlayVisible = true

        } else {

            crashOverlayVisible = false
        }
    }

    // ------------------------------------------------
    // MAIN GAME SCREEN
    // ------------------------------------------------

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Grass)
            .pointerInput(hasStarted, gameOver) {

                var totalDrag = 0f
                var moved = false

                detectHorizontalDragGestures(

                    onHorizontalDrag = { change, dragAmount ->

                        if (hasStarted && !gameOver && !moved) {

                            totalDrag += dragAmount

                            if (abs(totalDrag) > 55f) {

                                if (totalDrag < 0f) {

                                    playerLane = (
                                            playerLane - 1
                                            ).coerceAtLeast(0)

                                } else {

                                    playerLane = (
                                            playerLane + 1
                                            ).coerceAtMost(2)
                                }

                                moved = true
                            }
                        }

                        change.consume()
                    },

                    onDragEnd = {
                        totalDrag = 0f
                        moved = false
                    },

                    onDragCancel = {
                        totalDrag = 0f
                        moved = false
                    }
                )
            }
    ) {

        // ------------------------------------------------
        // GAME CANVAS
        // ------------------------------------------------

        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {

            val sx = size.width / 400f
            val sy = size.height / 700f

            fun rect(
                color: Color,
                x: Float,
                y: Float,
                w: Float,
                h: Float
            ) {

                drawRect(
                    color = color,
                    topLeft = Offset(x * sx, y * sy),
                    size = Size(w * sx, h * sy)
                )
            }

            fun circle(
                color: Color,
                x: Float,
                y: Float,
                radius: Float
            ) {

                drawCircle(
                    color = color,
                    radius = radius * sx,
                    center = Offset(x * sx, y * sy)
                )
            }

            // BACKGROUND

            rect(Grass, 0f, 0f, 400f, 700f)

            // GRASS

            for (i in 0..17) {

                val y = (i * 52f + roadOffset * 1.3f) % 760f - 40f

                rect(Color(0xFF20563C), 5f, y, 18f, 25f)
                rect(Color(0xFF20563C), 377f, y + 20f, 18f, 25f)
                rect(Color(0xFF34784B), 9f, y - 5f, 7f, 7f)
                rect(Color(0xFF34784B), 384f, y + 15f, 7f, 7f)
                rect(Color(0xFF102E24), 3f, y + 26f, 22f, 3f)
                rect(Color(0xFF102E24), 375f, y + 46f, 22f, 3f)
            }

            // ROAD

            rect(Color(0xFF111722), 25f, 0f, 350f, 700f)
            rect(Road, 30f, 0f, 340f, 700f)

            // CURBS

            for (i in 0..15) {

                val y = (i * 50f + roadOffset) % 760f - 50f

                rect(Color.White, 26f, y, 5f, 25f)
                rect(Color(0xFFE74C3C), 26f, y + 25f, 5f, 25f)
                rect(Color.White, 369f, y, 5f, 25f)
                rect(Color(0xFFE74C3C), 369f, y + 25f, 5f, 25f)
            }

            // ROAD TEXTURE

            for (i in 0..35) {

                val y = (i * 37f + roadOffset * 1.5f) % 720f

                rect(
                    Color.White.copy(alpha = 0.035f),
                    45f + (i % 4) * 75f,
                    y,
                    9f,
                    2f
                )
            }

            // LANE MARKINGS

            for (lane in 1..2) {

                val x = 30f + lane * (340f / 3f)

                for (i in 0..10) {

                    val y = i * 100f + roadOffset - 100f

                    rect(Color(0xFF101722), x - 4f, y, 8f, 50f)
                    rect(Color(0xFFE1E5EA), x - 2f, y, 4f, 48f)
                }
            }

            // STREET LAMPS

            for (i in 0..5) {

                val y = (i * 150f + roadOffset * 1.8f) % 850f - 100f

                rect(Color(0xFF17212A), 14f, y, 4f, 48f)
                rect(Color(0xFF17212A), 9f, y, 14f, 5f)

                circle(
                    Color(0xFFFFD98A).copy(alpha = 0.25f),
                    16f,
                    y + 8f,
                    14f
                )

                rect(Yellow, 12f, y + 5f, 9f, 5f)
                rect(Color(0xFF17212A), 382f, y + 50f, 4f, 48f)
                rect(Color(0xFF17212A), 377f, y + 50f, 14f, 5f)

                circle(
                    Color(0xFFFFD98A).copy(alpha = 0.25f),
                    384f,
                    y + 58f,
                    14f
                )

                rect(Yellow, 380f, y + 55f, 9f, 5f)
            }

            // ------------------------------------------------
            // ENEMY PNG VEHICLES
            // ------------------------------------------------

            val laneWidth = 340f / 3f

            enemies.forEach { enemy ->

                val center =
                    30f + enemy.lane * laneWidth + laneWidth / 2f

                drawImage(
                    image = enemy.image,
                    dstOffset = IntOffset(
                        ((center - ENEMY_WIDTH / 2f) * sx).toInt(),
                        (enemy.y * sy).toInt()
                    ),
                    dstSize = IntSize(
                        (ENEMY_WIDTH * sx).toInt(),
                        (ENEMY_HEIGHT * sy).toInt()
                    )
                )
            }

            // ------------------------------------------------
            // PLAYER PNG VEHICLE
            // ------------------------------------------------

            if (!explosionActive) {

                val playerCenter =
                    30f + playerLane * laneWidth + laneWidth / 2f

                drawImage(
                    image = playerCarImage,
                    dstOffset = IntOffset(
                        ((playerCenter - playerWidth / 2f) * sx).toInt(),
                        (PLAYER_Y * sy).toInt()
                    ),
                    dstSize = IntSize(
                        (playerWidth * sx).toInt(),
                        (playerHeight * sy).toInt()
                    )
                )
            }

            // ------------------------------------------------
            // PIXEL EXPLOSION
            // ------------------------------------------------

            if (explosionActive) {

                val frame = explosionFrame.toFloat()
                val damping = 0.96f.pow(frame)
                val displacementFactor = (1f - damping) / (1f - 0.96f)
                val gravityDisplacement =
                    (0.12f / (1f - 0.96f)) * (frame - displacementFactor)

                explosionParticles.forEach { particle ->

                    val particleLife = (
                            particle.life - 0.022f * frame
                            ).coerceAtLeast(0f)

                    if (particleLife > 0f) {

                        val particleX =
                            particle.x + particle.vx * displacementFactor

                        val particleY =
                            particle.y +
                                    particle.vy * displacementFactor +
                                    gravityDisplacement

                        drawRect(
                            color = particle.color.copy(
                                alpha = particleLife.coerceIn(0f, 1f)
                            ),
                            topLeft = Offset(
                                particleX * sx,
                                particleY * sy
                            ),
                            size = Size(
                                particle.size * sx,
                                particle.size * sy
                            )
                        )
                    }
                }
            }
        }

        // ------------------------------------------------
        // HUD
        // ------------------------------------------------

        if (hasStarted && !showMenu && !showSplash) {

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 27.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Text(
                    "PIXEL RUSH",
                    color = Yellow,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {

                    HudCard("SCORE", "$score", Yellow)
                    HudCard("BEST", "$bestScore", Mint)

                    HudCard(
                        "SPEED",
                        "${gameSpeed.toInt()}",
                        Color(0xFFFF8A65)
                    )
                }
            }
        }

        // ------------------------------------------------
        // GAME OVER
        // ------------------------------------------------

        if (
            gameOver &&
            crashOverlayVisible &&
            !showMenu &&
            !showSplash
        ) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC07111F)),
                contentAlignment = Alignment.Center
            ) {

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Text(
                        "CRASHED!",
                        color = Color(0xFFFF5A5A),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 3.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "SCORE: $score",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "BEST: $bestScore",
                        color = Yellow,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = { restart() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Mint
                        )
                    ) {

                        Text(
                            "TRY AGAIN",
                            color = Color(0xFF06131F),
                            fontWeight = FontWeight.Black
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {

                            hasStarted = false
                            gameOver = false
                            explosionActive = false
                            explosionParticles.clear()
                            explosionFrame = 0
                            crashOverlayVisible = false
                            showMenu = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {

                        Text(
                            "MAIN MENU",
                            color = Color.White
                        )
                    }
                }
            }
        }

        // ------------------------------------------------
        // SPLASH SCREEN
        // ------------------------------------------------

        if (showSplash) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF101722)),
                contentAlignment = Alignment.Center
            ) {

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Image(
                        painter = painterResource(
                            id = R.drawable.logo
                        ),
                        contentDescription = "Game logo",
                        modifier = Modifier.size(176.dp),
                        contentScale = ContentScale.Fit
                    )

                    Spacer(Modifier.height(24.dp))

                    Text(
                        "CREATED BY AMIR ERFAN ESLAMIKIA",
                        color = Yellow,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                }
            }

        } else if (showMenu) {

            // ------------------------------------------------
            // MAIN MENU
            // ------------------------------------------------

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF07111F),
                                Color(0xFF142B49),
                                Color(0xFF241A46),
                                Color(0xFF07111F)
                            )
                        )
                    )
            ) {

                Canvas(Modifier.fillMaxSize()) {

                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(
                                Color(0xFF19DCC7).copy(alpha = 0.22f),
                                Color.Transparent
                            ),
                            center = Offset(
                                size.width * 0.12f,
                                size.height * 0.18f
                            ),
                            radius = size.width * 0.62f
                        ),
                        radius = size.width * 0.62f,
                        center = Offset(
                            size.width * 0.12f,
                            size.height * 0.18f
                        )
                    )

                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(
                                Color(0xFF8D5BFF).copy(alpha = 0.20f),
                                Color.Transparent
                            ),
                            center = Offset(
                                size.width * 0.92f,
                                size.height * 0.72f
                            ),
                            radius = size.width * 0.72f
                        ),
                        radius = size.width * 0.72f,
                        center = Offset(
                            size.width * 0.92f,
                            size.height * 0.72f
                        )
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            horizontal = 22.dp,
                            vertical = 28.dp
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    Text(
                        if (showGarage) "THE GARAGE" else "PIXEL RUSH",
                        color = Color.White,
                        fontSize = if (showGarage) 29.sp else 38.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 4.sp
                    )

                    Box(
                        modifier = Modifier
                            .width(112.dp)
                            .height(4.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color(0xFF19E6CF),
                                        Color(0xFF8B5CFF),
                                        Color(0xFFFFD447)
                                    )
                                ),
                                RoundedCornerShape(50)
                            )
                    )

                    Text(
                        if (showGarage)
                            "BUILD YOUR DREAM LINEUP"
                        else
                            "NEON STREETS. INFINITE SPEED.",
                        color = Color(0xFF9CB8D4),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )

                    if (!showGarage) {

                        // PERSONAL RECORD

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xCC15283D)
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                Color(0xFF43DCCB).copy(alpha = 0.55f)
                            )
                        ) {

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {

                                Column {

                                    Text(
                                        "PERSONAL RECORD",
                                        color = Color(0xFF9CB8D4),
                                        fontSize = 10.sp,
                                        letterSpacing = 1.8.sp
                                    )

                                    Text(
                                        "$bestScore",
                                        color = Yellow,
                                        fontSize = 34.sp,
                                        fontWeight = FontWeight.Black
                                    )

                                    Text(
                                        "BEST SCORE",
                                        color = Color(0xFF6F8AA7),
                                        fontSize = 9.sp,
                                        letterSpacing = 1.5.sp
                                    )
                                }

                                Column(
                                    horizontalAlignment = Alignment.End
                                ) {

                                    Text(
                                        "YOUR RIDE",
                                        color = Color(0xFF9CB8D4),
                                        fontSize = 10.sp,
                                        letterSpacing = 1.5.sp
                                    )

                                    Image(
                                        bitmap = playerCarImage,
                                        contentDescription = "Selected car",
                                        modifier = Modifier.size(
                                            width = 48.dp,
                                            height = 66.dp
                                        ),
                                        contentScale = ContentScale.Fit
                                    )

                                    Text(
                                        "${unlockedCarCount}/5 CARS UNLOCKED",
                                        color = Mint,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // SELECTED PLAYER CAR

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xAA0C1A2B)
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                Mint.copy(alpha = 0.5f)
                            )
                        ) {

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {

                                Text(
                                    "CURRENTLY SELECTED",
                                    color = Color(0xFF8FAAC4),
                                    fontSize = 9.sp,
                                    letterSpacing = 2.sp
                                )

                                Spacer(Modifier.height(6.dp))

                                Image(
                                    bitmap = playerCarImage,
                                    contentDescription = "Selected player car",
                                    modifier = Modifier.size(
                                        width = 76.dp,
                                        height = 112.dp
                                    ),
                                    contentScale = ContentScale.Fit
                                )

                                Text(
                                    "PLAYER CAR ${activeCarIndex + 1}",
                                    color = Mint,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Black
                                )

                                Text(
                                    "READY TO RACE",
                                    color = Color(0xFF8FAAC4),
                                    fontSize = 9.sp,
                                    letterSpacing = 1.sp
                                )
                            }
                        }

                        // START

                        Button(
                            onClick = { restart() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(62.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF19DCC7)
                            ),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 10.dp,
                                pressedElevation = 2.dp
                            )
                        ) {

                            Text(
                                "▶   START RACING",
                                color = Color(0xFF06131F),
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                        }

                        // GARAGE BUTTON

                        Button(
                            onClick = { showGarage = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF6C55D9)
                            ),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 8.dp
                            )
                        ) {

                            Text(
                                "✦   CAR COLLECTION  •  $unlockedCarCount/5",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        // EXIT

                        OutlinedButton(
                            onClick = {
                                (context as? Activity)?.finish()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                Color.White.copy(alpha = 0.25f)
                            )
                        ) {

                            Text(
                                "EXIT GAME",
                                color = Color(0xFFB7C7D8),
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            )
                        }

                        Text(
                            "DRIVE TO BUILD YOUR SCORE  •  UNLOCK CARS",
                            color = Color(0xFF7189A4),
                            fontSize = 9.sp,
                            letterSpacing = 1.sp
                        )

                    } else {

                        // ------------------------------------------------
                        // GARAGE
                        // FIVE REAL PLAYER PNG IMAGES
                        // ------------------------------------------------

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xCC15283D)
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                Color(0xFF43DCCB).copy(alpha = 0.45f)
                            )
                        ) {

                            Column(
                                Modifier.padding(14.dp)
                            ) {

                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {

                                    Text(
                                        "BEST SCORE",
                                        color = Color(0xFF9CB8D4),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Text(
                                        "$bestScore  •  $unlockedCarCount/5 UNLOCKED",
                                        color = Mint,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    "Unlock five different cars by increasing your best score.",
                                    color = Color(0xFFB8C9DA),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // FIVE PNG GARAGE CARDS

                        (0..4).chunked(2).forEach { rowCars ->

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {

                                rowCars.forEach { index ->

                                    val unlocked = index < unlockedCarCount
                                    val selected = index == activeCarIndex
                                    val requiredScore = index * UNLOCK_STEP

                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(170.dp)
                                            .clickable(enabled = unlocked) {

                                                selectedCarIndex = index

                                                preferences.edit()
                                                    .putInt("selected_car", index)
                                                    .apply()

                                                showGarage = false
                                            },
                                        shape = RoundedCornerShape(17.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = when {
                                                selected -> Color(0xFF174B50)
                                                unlocked -> Color(0xCC182D43)
                                                else -> Color(0xAA101B2A)
                                            }
                                        ),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            when {
                                                selected -> Mint
                                                unlocked -> Color(0xFF526B85)
                                                else -> Color(0xFF526B85).copy(alpha = 0.25f)
                                            }
                                        )
                                    ) {

                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {

                                            Image(
                                                bitmap = playerImages[index],
                                                contentDescription = "Player car ${index + 1}",
                                                modifier = Modifier.size(
                                                    width = 65.dp,
                                                    height = 90.dp
                                                ),
                                                contentScale = ContentScale.Fit,
                                                alpha = if (unlocked) 1f else 0.3f
                                            )

                                            Spacer(Modifier.height(5.dp))

                                            Text(
                                                "PLAYER CAR ${index + 1}",
                                                color = if (unlocked) {
                                                    Color.White
                                                } else {
                                                    Color(0xFF718096)
                                                },
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )

                                            Text(
                                                when {
                                                    selected -> "EQUIPPED"
                                                    unlocked -> "TAP TO SELECT"
                                                    else -> "BEST: $requiredScore"
                                                },
                                                color = if (selected) {
                                                    Mint
                                                } else {
                                                    Color(0xFF91A8C0)
                                                },
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                if (rowCars.size == 1) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }

                        // BACK TO MENU

                        OutlinedButton(
                            onClick = {
                                showGarage = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                Color(0xFF19DCC7).copy(alpha = 0.7f)
                            )
                        ) {

                            Text(
                                "←   BACK TO PIXEL RUSH",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------
// HUD CARD
// ------------------------------------------------

@Composable
private fun HudCard(
    title: String,
    value: String,
    color: Color
) {

    Surface(
        color = Color(0xE9101721),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.border(
            1.dp,
            color.copy(alpha = 0.5f),
            RoundedCornerShape(12.dp)
        )
    ) {

        Column(
            modifier = Modifier.padding(
                horizontal = 15.dp,
                vertical = 8.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Text(
                title,
                color = Color.LightGray,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp
            )

            Text(
                value,
                color = color,
                fontSize = 23.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}
