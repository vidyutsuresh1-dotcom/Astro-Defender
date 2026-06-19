package com.example.gametrial

import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Streaming
import java.io.File
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

interface AstroConnectApi {
    @GET("color")
    suspend fun getLaserColor(): ColorResponse

    @GET("skins")
    suspend fun getSkins(): SkinsResponse

    @Streaming
    @GET("themePack/image")
    suspend fun getBackgroundImage(): Response<ResponseBody>

    @Streaming
    @GET("themePack/audio")
    suspend fun getBackgroundAudio(): Response<ResponseBody>
}

data class ColorResponse(val color: String)
data class SkinsResponse(val spaceship: String, val asteroid: String, val ufo: String)

object RetrofitInstance {
    private const val BASE_URL = "https://api.astroconnect.local/"
    val api: AstroConnectApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AstroConnectApi::class.java)
    }
}

enum class GameState { LOADING, MENU, PLAYING, GAMEOVER }

data class PlayerShip(var x: Float = 0f, var y: Float = 0f, var rotationAngle: Float = 0f, val radius: Float = 30f)
data class Bullet(val id: String = UUID.randomUUID().toString(), var x: Float, var y: Float, val vx: Float, val vy: Float, val radius: Float = 4f)
data class Asteroid(
    val id: String = UUID.randomUUID().toString(),
    var x: Float,
    var y: Float,
    val vx: Float,
    val vy: Float,
    val isLarge: Boolean,
    val radius: Float = if (isLarge) 60f else 30f,
    val vertices: List<Offset> = generateIrregularPolygon(radius)
)

fun generateIrregularPolygon(radius: Float): List<Offset> {
    val points = mutableListOf<Offset>()
    val numPoints = (6..9).random()
    val angleStep = (Math.PI * 2) / numPoints
    for (i in 0 until numPoints) {
        val variance = kotlin.random.Random.nextDouble(0.7, 1.3).toFloat()
        val r = radius * variance
        val angle = i * angleStep
        points.add(Offset((cos(angle) * r).toFloat(), (sin(angle) * r).toFloat()))
    }
    return points
}

class GameViewModel {
    var state by mutableStateOf(GameState.LOADING)
    var tick by mutableIntStateOf(0)

    var player by mutableStateOf(PlayerShip())
    private var playerVx = 0f
    private var playerVy = 0f

    val bullets = mutableStateListOf<Bullet>()
    val asteroids = mutableStateListOf<Asteroid>()

    var score by mutableStateOf(0)
    var timeElapsed by mutableStateOf(0)
    var wave by mutableStateOf(1)

    var laserColor by mutableStateOf(Color.White)
    var backgroundBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
    var skinsData by mutableStateOf<SkinsResponse?>(null)

    private var arenaWidth = 1080f
    private var arenaHeight = 1920f

    fun initArenaDimensions(width: Float, height: Float) {
        if (width > 0 && height > 0) {
            arenaWidth = width
            arenaHeight = height
            if (player.x == 0f) player = PlayerShip(x = width / 2, y = height / 2)
        }
    }

    fun startGame() {
        score = 0
        timeElapsed = 0
        wave = 1
        playerVx = 0f
        playerVy = 0f
        bullets.clear()
        asteroids.clear()
        player = PlayerShip(x = arenaWidth / 2, y = arenaHeight / 2)
        repeat(8) { spawnAsteroid() }
        state = GameState.PLAYING
    }

    fun handleJoystickInput(normX: Float, normY: Float) {
        if (normX != 0f || normY != 0f) {
            val angleInRadians = atan2(normY, normX)
            player.rotationAngle = Math.toDegrees(angleInRadians.toDouble()).toFloat()
            val maxSpeed = 12f
            playerVx = normX * maxSpeed
            playerVy = normY * maxSpeed
        } else {
            playerVx = 0f
            playerVy = 0f
        }
    }

    fun fireBullet() {
        val angleRad = Math.toRadians(player.rotationAngle.toDouble())
        val speed = 25f
        bullets.add(
            Bullet(
                x = player.x + cos(angleRad).toFloat() * player.radius,
                y = player.y + sin(angleRad).toFloat() * player.radius,
                vx = cos(angleRad).toFloat() * speed,
                vy = sin(angleRad).toFloat() * speed
            )
        )
    }

    fun spawnAsteroid() {
        val side = (0..3).random()
        var sx = 0f; var sy = 0f
        when (side) {
            0 -> { sx = (0..arenaWidth.toInt()).random().toFloat(); sy = -50f }
            1 -> { sx = arenaWidth + 50f; sy = (0..arenaHeight.toInt()).random().toFloat() }
            2 -> { sx = (0..arenaWidth.toInt()).random().toFloat(); sy = arenaHeight + 50f }
            3 -> { sx = -50f; sy = (0..arenaHeight.toInt()).random().toFloat() }
        }
        val targetX = (arenaWidth / 2) + (-200..200).random()
        val targetY = (arenaHeight / 2) + (-200..200).random()
        val angle = atan2(targetY - sy, targetX - sx)
        val speed = (1..3).random().toFloat() + (wave * 0.2f)
        asteroids.add(Asteroid(x = sx, y = sy, vx = cos(angle) * speed, vy = sin(angle) * speed, isLarge = true))
    }

    fun updateEngineState() {
        if (state != GameState.PLAYING) return
        tick++
        player.x += playerVx
        player.y += playerVy

        if (player.x < player.radius) player.x = player.radius
        if (player.x > arenaWidth - player.radius) player.x = arenaWidth - player.radius
        if (player.y < player.radius) player.y = player.radius
        if (player.y > arenaHeight - player.radius) player.y = arenaHeight - player.radius

        bullets.forEach { it.x += it.vx; it.y += it.vy }
        bullets.removeAll { it.x < 0 || it.x > arenaWidth || it.y < 0 || it.y > arenaHeight }

        asteroids.forEach {
            it.x += it.vx; it.y += it.vy
            if (it.x < -100f) it.x = arenaWidth + 100f
            if (it.x > arenaWidth + 100f) it.x = -100f
            if (it.y < -100f) it.y = arenaHeight + 100f
            if (it.y > arenaHeight + 100f) it.y = -100f

            if (sqrt((it.x - player.x).pow(2) + (it.y - player.y).pow(2)) < (it.radius + player.radius * 0.8f)) {
                state = GameState.GAMEOVER
            }
        }

        val toRemoveB = mutableListOf<Bullet>()
        val toRemoveA = mutableListOf<Asteroid>()
        val toAddA = mutableListOf<Asteroid>()

        for (b in bullets) {
            for (ast in asteroids) {
                if (sqrt((b.x - ast.x).pow(2) + (b.y - ast.y).pow(2)) < (ast.radius)) {
                    toRemoveB.add(b); toRemoveA.add(ast)
                    if (ast.isLarge) {
                        val baseAngle = atan2(ast.vy, ast.vx)
                        val speed = sqrt(ast.vx.pow(2) + ast.vy.pow(2)) * 1.35f
                        toAddA.add(Asteroid(x = ast.x, y = ast.y, vx = cos(baseAngle + 0.5f) * speed, vy = sin(baseAngle + 0.5f) * speed, isLarge = false))
                        toAddA.add(Asteroid(x = ast.x, y = ast.y, vx = cos(baseAngle - 0.5f) * speed, vy = sin(baseAngle - 0.5f) * speed, isLarge = false))
                    }
                    score += if (ast.isLarge) 150 else 300
                    break
                }
            }
        }
        bullets.removeAll(toRemoveB); asteroids.removeAll(toRemoveA); asteroids.addAll(toAddA)

        if (asteroids.isEmpty()) {
            wave++
            repeat(8 + wave * 2) { spawnAsteroid() }
        }
    }

    fun formatTime(): String {
        val mins = timeElapsed / 60
        val secs = timeElapsed % 60
        return String.format("00:%02d:%02d", mins, secs)
    }
}

class MainActivity : ComponentActivity() {
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val gameEngine = remember { GameViewModel() }
                val context = LocalContext.current

                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        try {
                            val colorRes = RetrofitInstance.api.getLaserColor()
                            gameEngine.laserColor = Color(android.graphics.Color.parseColor(colorRes.color))
                            gameEngine.skinsData = RetrofitInstance.api.getSkins()
                            val imgRes = RetrofitInstance.api.getBackgroundImage()
                            if (imgRes.isSuccessful) {
                                gameEngine.backgroundBitmap = android.graphics.BitmapFactory.decodeStream(imgRes.body()?.byteStream())
                            }
                            val audioRes = RetrofitInstance.api.getBackgroundAudio()
                            if (audioRes.isSuccessful) {
                                val audioFile = File(context.cacheDir, "bgm.mp3")
                                audioFile.outputStream().use { fileOut ->
                                    audioRes.body()?.byteStream()?.copyTo(fileOut)
                                }
                                mediaPlayer = MediaPlayer().apply {
                                    setDataSource(audioFile.absolutePath)
                                    isLooping = true
                                    prepare()
                                    start()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AstroAPI", "API Fetch Failed. Continuing offline.", e)
                            gameEngine.laserColor = Color.Cyan
                        } finally {
                            gameEngine.state = GameState.MENU
                        }
                    }
                }

                LaunchedEffect(gameEngine.state) {
                    if (gameEngine.state == GameState.PLAYING) {
                        while (isActive && gameEngine.state == GameState.PLAYING) {
                            withFrameNanos { gameEngine.updateEngineState() }
                        }
                    }
                }

                LaunchedEffect(gameEngine.state) {
                    if (gameEngine.state == GameState.PLAYING) {
                        while (isActive && gameEngine.state == GameState.PLAYING) {
                            delay(1000)
                            gameEngine.timeElapsed++
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
                    Crossfade(targetState = gameEngine.state, label = "ScreenTransition") { screen ->
                        when (screen) {
                            GameState.LOADING -> LoadingScreen()
                            GameState.MENU -> MainMenuScreen(onPlayClicked = { gameEngine.startGame() })
                            GameState.PLAYING -> ActiveGameScreen(gameEngine)
                            GameState.GAMEOVER -> GameOverScreen(gameEngine)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

@Composable
fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D0D)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = Color.White)
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "FETCHING THEME PACK...",
            color = Color.Gray,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 4.sp
        )
    }
}

@Composable
fun MainMenuScreen(onPlayClicked: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "ASTRO\nDEFENDER",
            color = Color.White,
            fontSize = 42.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 8.sp,
            textAlign = TextAlign.Center,
            lineHeight = 52.sp
        )
        Spacer(modifier = Modifier.height(64.dp))
        Box(modifier = Modifier.size(100.dp).border(1.dp, Color.DarkGray), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(40.dp)) {
                val path = Path().apply {
                    moveTo(size.width / 2, 0f)
                    lineTo(size.width, size.height)
                    lineTo(size.width / 2, size.height * 0.8f)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path, color = Color.White, style = Stroke(width = 3f))
            }
        }
        Spacer(modifier = Modifier.height(64.dp))
        Box(modifier = Modifier.fillMaxWidth().height(70.dp).background(Color.White).clickable { onPlayClicked() }, contentAlignment = Alignment.Center) {
            Text("PLAY ", color = Color.Black, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 6.sp)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(modifier = Modifier.weight(1f).height(50.dp).border(1.dp, Color.DarkGray), contentAlignment = Alignment.Center) {
                Text("SETTINGS", color = Color.LightGray, fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
            }
            Box(modifier = Modifier.weight(1f).height(50.dp).border(1.dp, Color.DarkGray), contentAlignment = Alignment.Center) {
                Text("HELP", color = Color.LightGray, fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
            }
        }
    }
}

@Composable
fun ActiveGameScreen(engine: GameViewModel) {
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            engine.initArenaDimensions(size.width.toFloat(), size.height.toFloat())
        }) {
            val currentTick = engine.tick
            engine.backgroundBitmap?.let { bmp ->
                drawImage(
                    image = bmp.asImageBitmap(),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    alpha = 0.5f
                )
            }
            engine.bullets.forEach { drawCircle(color = engine.laserColor, radius = it.radius, center = Offset(it.x, it.y)) }
            engine.asteroids.forEach { ast ->
                translate(left = ast.x, top = ast.y) {
                    val path = Path().apply {
                        ast.vertices.forEachIndexed { index, point ->
                            if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                        }
                        close()
                    }
                    drawPath(path = path, color = Color.White, style = Stroke(width = 2f))
                }
            }
            rotate(engine.player.rotationAngle, Offset(engine.player.x, engine.player.y)) {
                translate(left = engine.player.x - engine.player.radius, top = engine.player.y - engine.player.radius) {
                    val path = Path().apply {
                        moveTo(engine.player.radius * 2, engine.player.radius)
                        lineTo(0f, engine.player.radius * 1.8f)
                        lineTo(engine.player.radius * 0.4f, engine.player.radius)
                        lineTo(0f, engine.player.radius * 0.2f)
                        close()
                    }
                    drawPath(path = path, color = Color.White, style = Stroke(width = 3f))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("SCORE", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                Text(String.format("%06d", engine.score), color = Color.White, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(engine.formatTime(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("WAVE 0${engine.wave}", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("ASTEROIDS", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                Text(engine.asteroids.size.toString(), color = Color.White, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Row(modifier = Modifier.fillMaxSize().padding(bottom = 64.dp, start = 32.dp, end = 32.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            VirtualJoystick { normX, normY -> engine.handleJoystickInput(normX, normY) }
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .border(1.dp, Color.White, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = { engine.fireBullet() })
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("FIRE", color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
            }
        }
    }
}

@Composable
fun GameOverScreen(engine: GameViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("GAME OVER", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 6.sp)
        Spacer(modifier = Modifier.height(24.dp))
        Text("FINAL SCORE", color = Color.Gray, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        Text(String.format("%06d", engine.score), color = Color.White, fontSize = 32.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(64.dp))
        Box(modifier = Modifier.fillMaxWidth().height(70.dp).border(1.dp, Color.White).clickable { engine.state = GameState.MENU }, contentAlignment = Alignment.Center) {
            Text("MAIN MENU", color = Color.White, fontSize = 20.sp, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
        }
    }
}

@Composable
fun VirtualJoystick(onMove: (Float, Float) -> Unit) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    val maxRadiusPx = with(LocalDensity.current) { 50.dp.toPx() }
    Box(
        modifier = Modifier
            .size(100.dp)
            .border(1.dp, Color.DarkGray, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { offset = Offset.Zero; onMove(0f, 0f) }
                ) { change, drag ->
                    change.consume()
                    val newOffset = offset + drag
                    val dist = sqrt(newOffset.x.pow(2) + newOffset.y.pow(2))
                    offset = if (dist <= maxRadiusPx) newOffset else Offset((newOffset.x / dist) * maxRadiusPx, (newOffset.y / dist) * maxRadiusPx)
                    onMove(offset.x / maxRadiusPx, offset.y / maxRadiusPx)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.offset { IntOffset(offset.x.toInt(), offset.y.toInt()) }.size(30.dp).border(2.dp, Color.White, CircleShape))
    }
}