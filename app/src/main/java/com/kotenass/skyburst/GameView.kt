package com.kotenass.skyburst

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    enum class Screen { TITLE, PLAYING, GAME_OVER }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("skyburst_prefs", Context.MODE_PRIVATE)

    @Volatile private var running = false
    private var thread: Thread? = null

    private var screen = Screen.TITLE
    private var canvasW = 0f
    private var canvasH = 0f

    private lateinit var player: PlayerShip
    private val bullets = mutableListOf<Bullet>()
    private val enemies = mutableListOf<Enemy>()
    private val meteors = mutableListOf<Meteor>()
    private val particles = mutableListOf<Particle>()
    private val stars = mutableListOf<Star>()

    private var score = 0
    private var kills = 0
    private var surviveMs = 0L
    private var highScore = 0
    private var gameTime = 0f
    private var spawnTimer = 0f
    private var meteorTimer = 0f
    private var fireCooldown = 0f
    private var touchZone = TouchZone.NONE
    private var holdingFire = false
    private var flashAlpha = 0f
    private var titlePulse = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val neonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val softPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private val cyan = Color.rgb(0, 245, 255)
    private val magenta = Color.rgb(255, 43, 214)
    private val lime = Color.rgb(184, 255, 60)
    private val orange = Color.rgb(255, 138, 61)
    private val violet = Color.rgb(120, 80, 255)

    private data class Star(var x: Float, var y: Float, var speed: Float, var size: Float, var bright: Int)

    private enum class TouchZone { NONE, LEFT, CENTER, RIGHT }

    init {
        holder.addCallback(this)
        isFocusable = true
        highScore = prefs.getInt(KEY_HIGH, 0)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // started via resume
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        canvasW = width.toFloat()
        canvasH = height.toFloat()
        if (!::player.isInitialized) {
            resetWorld(keepScreen = true)
        } else {
            player.y = canvasH - player.height * 1.8f
            player.x = player.x.coerceIn(player.width, canvasW - player.width)
        }
        if (stars.isEmpty()) initStars()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pause()
    }

    fun resume() {
        if (running) return
        running = true
        thread = Thread(this, "SkyburstLoop").also { it.start() }
    }

    fun pause() {
        running = false
        try {
            thread?.join(500)
        } catch (_: InterruptedException) {
        }
        thread = null
    }

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            var dt = (now - last) / 1_000_000_000f
            last = now
            if (dt > 0.05f) dt = 0.05f
            update(dt)
            drawFrame()
            try {
                Thread.sleep(8)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun initStars() {
        stars.clear()
        if (canvasW <= 0f) return
        repeat(60) {
            stars += Star(
                x = Random.nextFloat() * canvasW,
                y = Random.nextFloat() * canvasH,
                speed = 40f + Random.nextFloat() * 160f,
                size = 1f + Random.nextFloat() * 2.5f,
                bright = 80 + Random.nextInt(160)
            )
        }
    }

    private fun resetWorld(keepScreen: Boolean = false) {
        if (canvasW <= 0f || canvasH <= 0f) return
        val pw = canvasW * 0.09f
        val ph = pw * 1.15f
        player = PlayerShip(
            x = canvasW / 2f,
            y = canvasH - ph * 1.8f,
            width = pw,
            height = ph
        )
        bullets.clear()
        enemies.clear()
        meteors.clear()
        particles.clear()
        score = 0
        kills = 0
        surviveMs = 0L
        gameTime = 0f
        spawnTimer = 0.6f
        meteorTimer = 1.8f
        fireCooldown = 0f
        holdingFire = false
        touchZone = TouchZone.NONE
        flashAlpha = 0f
        if (!keepScreen) screen = Screen.PLAYING
        if (stars.isEmpty()) initStars()
    }

    private fun difficulty(): Float = 1f + gameTime / 28f

    private fun update(dt: Float) {
        titlePulse += dt
        flashAlpha = max(0f, flashAlpha - dt * 2.5f)

        for (s in stars) {
            s.y += s.speed * dt * if (screen == Screen.PLAYING) (0.7f + difficulty() * 0.15f) else 0.35f
            if (s.y > canvasH) {
                s.y = -2f
                s.x = Random.nextFloat() * canvasW
            }
        }

        when (screen) {
            Screen.TITLE -> { /* idle stars */ }
            Screen.GAME_OVER -> updateParticles(dt)
            Screen.PLAYING -> updatePlaying(dt)
        }
    }

    private fun updatePlaying(dt: Float) {
        gameTime += dt
        surviveMs = (gameTime * 1000).toLong()
        score = (surviveMs / 100).toInt() + kills * 25

        val speed = canvasW * 0.95f
        when (touchZone) {
            TouchZone.LEFT -> player.vx = -speed
            TouchZone.RIGHT -> player.vx = speed
            else -> player.vx = 0f
        }
        player.x += player.vx * dt
        player.x = player.x.coerceIn(player.width * 0.6f, canvasW - player.width * 0.6f)

        fireCooldown -= dt
        if (holdingFire || touchZone == TouchZone.CENTER) {
            if (fireCooldown <= 0f) {
                fire()
                fireCooldown = max(0.12f, 0.22f - difficulty() * 0.015f)
            }
        }

        val diff = difficulty()
        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawnEnemyWave(diff)
            spawnTimer = max(0.35f, 1.35f - diff * 0.12f)
        }
        meteorTimer -= dt
        if (meteorTimer <= 0f) {
            spawnMeteor(diff)
            meteorTimer = max(0.7f, 2.4f - diff * 0.18f)
        }

        bullets.forEach { b ->
            b.y += b.vy * dt
            if (b.y < -20f) b.alive = false
        }
        enemies.forEach { e ->
            e.phase += dt * 4f
            e.y += e.vy * dt
            e.x += e.vx * dt + sin(e.phase) * 18f * dt
            if (e.y > canvasH + 40f) e.alive = false
        }
        meteors.forEach { m ->
            m.y += m.vy * dt
            m.x += m.vx * dt
            m.spin += m.spinSpeed * dt
            if (m.y > canvasH + 60f || m.x < -80f || m.x > canvasW + 80f) m.alive = false
        }

        // bullet vs enemy
        for (b in bullets) {
            if (!b.alive) continue
            for (e in enemies) {
                if (!e.alive) continue
                val dx = b.x - e.x
                val dy = b.y - e.y
                if (dx * dx + dy * dy < (e.size * 0.55f + b.radius) * (e.size * 0.55f + b.radius)) {
                    b.alive = false
                    e.hp -= 1
                    spawnHit(e.x, e.y, cyan)
                    if (e.hp <= 0) {
                        e.alive = false
                        kills++
                        spawnBurst(e.x, e.y, when (e.kind) {
                            EnemyKind.TRIANGLE -> magenta
                            EnemyKind.DIAMOND -> lime
                            EnemyKind.HEX -> violet
                        })
                    }
                    break
                }
            }
            // bullets pass through meteors (indestructible)
        }

        // collisions with player
        for (e in enemies) {
            if (!e.alive) continue
            if (overlapShip(e.x, e.y, e.size * 0.45f)) {
                e.alive = false
                die()
                return
            }
        }
        for (m in meteors) {
            if (!m.alive) continue
            if (overlapShip(m.x, m.y, m.radius * 0.75f)) {
                die()
                return
            }
        }

        bullets.removeAll { !it.alive }
        enemies.removeAll { !it.alive }
        meteors.removeAll { !it.alive }
        updateParticles(dt)
    }

    private fun overlapShip(x: Float, y: Float, r: Float): Boolean {
        val dx = abs(x - player.x)
        val dy = abs(y - player.y)
        return dx < player.width * 0.4f + r && dy < player.height * 0.4f + r
    }

    private fun abs(v: Float) = if (v < 0) -v else v

    private fun fire() {
        bullets += Bullet(
            x = player.x,
            y = player.top() - 4f,
            radius = canvasW * 0.012f,
            vy = -canvasH * 1.15f
        )
        // small muzzle flash particles
        repeat(3) {
            particles += Particle(
                x = player.x,
                y = player.top(),
                vx = (Random.nextFloat() - 0.5f) * 80f,
                vy = -80f - Random.nextFloat() * 60f,
                life = 0.2f,
                maxLife = 0.2f,
                color = cyan,
                size = 3f
            )
        }
    }

    private fun spawnEnemyWave(diff: Float) {
        val count = 1 + min(4, (diff * 0.7f).toInt()) + if (Random.nextFloat() < 0.35f) 1 else 0
        val baseY = -30f - Random.nextFloat() * 40f
        repeat(count) { i ->
            val kind = when (Random.nextInt(3)) {
                0 -> EnemyKind.TRIANGLE
                1 -> EnemyKind.DIAMOND
                else -> EnemyKind.HEX
            }
            val size = canvasW * (0.07f + Random.nextFloat() * 0.03f)
            enemies += Enemy(
                x = canvasW * (0.12f + Random.nextFloat() * 0.76f),
                y = baseY - i * size * 1.2f,
                size = size,
                vy = canvasH * (0.12f + 0.04f * diff) * (0.85f + Random.nextFloat() * 0.3f),
                vx = (Random.nextFloat() - 0.5f) * canvasW * 0.08f,
                kind = kind,
                hp = if (kind == EnemyKind.HEX) 2 else 1,
                phase = Random.nextFloat() * 6f
            )
        }
    }

    private fun spawnMeteor(diff: Float) {
        val r = canvasW * (0.045f + Random.nextFloat() * 0.05f)
        meteors += Meteor(
            x = canvasW * Random.nextFloat(),
            y = -r * 2f,
            radius = r,
            vy = canvasH * (0.18f + 0.05f * diff) * (0.9f + Random.nextFloat() * 0.35f),
            vx = (Random.nextFloat() - 0.5f) * canvasW * 0.12f,
            spin = Random.nextFloat() * 6f,
            spinSpeed = (Random.nextFloat() - 0.5f) * 6f
        )
    }

    private fun spawnBurst(x: Float, y: Float, color: Int) {
        repeat(14) {
            val a = Random.nextFloat() * (Math.PI * 2).toFloat()
            val sp = 80f + Random.nextFloat() * 220f
            particles += Particle(
                x = x, y = y,
                vx = cos(a) * sp, vy = sin(a) * sp,
                life = 0.35f + Random.nextFloat() * 0.35f,
                maxLife = 0.7f,
                color = color,
                size = 2.5f + Random.nextFloat() * 4f
            )
        }
    }

    private fun spawnHit(x: Float, y: Float, color: Int) {
        repeat(5) {
            particles += Particle(
                x = x, y = y,
                vx = (Random.nextFloat() - 0.5f) * 120f,
                vy = (Random.nextFloat() - 0.5f) * 120f,
                life = 0.2f,
                maxLife = 0.2f,
                color = color,
                size = 2f
            )
        }
    }

    private fun updateParticles(dt: Float) {
        for (p in particles) {
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.life -= dt
            p.vx *= 0.98f
            p.vy *= 0.98f
        }
        particles.removeAll { it.life <= 0f }
    }

    private fun die() {
        spawnBurst(player.x, player.y, cyan)
        spawnBurst(player.x, player.y, magenta)
        flashAlpha = 0.85f
        score = (surviveMs / 100).toInt() + kills * 25
        if (score > highScore) {
            highScore = score
            prefs.edit().putInt(KEY_HIGH, highScore).apply()
        }
        screen = Screen.GAME_OVER
        holdingFire = false
        touchZone = TouchZone.NONE
    }

    private fun drawFrame() {
        val h = holder
        var canvas: Canvas? = null
        try {
            canvas = h.lockCanvas()
            if (canvas != null) {
                synchronized(h) {
                    render(canvas)
                }
            }
        } finally {
            if (canvas != null) {
                try {
                    h.unlockCanvasAndPost(canvas)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun render(c: Canvas) {
        if (canvasW <= 0f) {
            canvasW = c.width.toFloat()
            canvasH = c.height.toFloat()
        }
        drawBackground(c)
        when (screen) {
            Screen.TITLE -> drawTitle(c)
            Screen.PLAYING -> {
                drawEntities(c)
                drawHud(c)
            }
            Screen.GAME_OVER -> {
                drawEntities(c)
                drawGameOver(c)
            }
        }
        if (flashAlpha > 0f) {
            softPaint.color = Color.argb((flashAlpha * 180).toInt(), 255, 80, 180)
            c.drawRect(0f, 0f, canvasW, canvasH, softPaint)
        }
    }

    private fun drawBackground(c: Canvas) {
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, canvasH,
            intArrayOf(Color.rgb(4, 4, 18), Color.rgb(12, 8, 40), Color.rgb(6, 18, 36)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, canvasW, canvasH, bgPaint)
        bgPaint.shader = null

        // subtle grid
        neonPaint.style = Paint.Style.STROKE
        neonPaint.strokeWidth = 1f
        neonPaint.color = Color.argb(28, 0, 245, 255)
        val step = canvasW / 8f
        var x = 0f
        while (x < canvasW) {
            c.drawLine(x, 0f, x, canvasH, neonPaint)
            x += step
        }
        var y = (gameTime * 40f) % step
        while (y < canvasH) {
            c.drawLine(0f, y, canvasW, y, neonPaint)
            y += step
        }

        for (s in stars) {
            softPaint.color = Color.argb(s.bright, 180, 220, 255)
            c.drawCircle(s.x, s.y, s.size, softPaint)
        }
    }

    private fun drawEntities(c: Canvas) {
        for (p in particles) {
            val a = (255 * (p.life / p.maxLife)).toInt().coerceIn(0, 255)
            softPaint.color = Color.argb(a, Color.red(p.color), Color.green(p.color), Color.blue(p.color))
            c.drawCircle(p.x, p.y, p.size, softPaint)
        }
        for (m in meteors) drawMeteor(c, m)
        for (e in enemies) drawEnemy(c, e)
        for (b in bullets) {
            fillPaint.shader = RadialGradient(
                b.x, b.y, b.radius * 3f,
                intArrayOf(Color.WHITE, cyan, Color.TRANSPARENT),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawCircle(b.x, b.y, b.radius * 2.2f, fillPaint)
            fillPaint.shader = null
            fillPaint.color = Color.WHITE
            c.drawCircle(b.x, b.y, b.radius * 0.7f, fillPaint)
        }
        if (screen == Screen.PLAYING) drawPlayer(c)
    }

    private fun drawPlayer(c: Canvas) {
        val px = player.x
        val py = player.y
        val w = player.width
        val h = player.height

        // engine glow
        softPaint.shader = RadialGradient(
            px, py + h * 0.45f, w * 0.7f,
            intArrayOf(Color.argb(160, 0, 245, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        c.drawCircle(px, py + h * 0.5f, w * 0.55f, softPaint)
        softPaint.shader = null

        path.reset()
        path.moveTo(px, py - h * 0.55f)
        path.lineTo(px + w * 0.48f, py + h * 0.4f)
        path.lineTo(px + w * 0.18f, py + h * 0.22f)
        path.lineTo(px, py + h * 0.48f)
        path.lineTo(px - w * 0.18f, py + h * 0.22f)
        path.lineTo(px - w * 0.48f, py + h * 0.4f)
        path.close()

        fillPaint.shader = LinearGradient(
            px, py - h, px, py + h,
            intArrayOf(cyan, Color.rgb(40, 120, 255), magenta),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawPath(path, fillPaint)
        fillPaint.shader = null

        neonPaint.style = Paint.Style.STROKE
        neonPaint.strokeWidth = 3f
        neonPaint.color = Color.WHITE
        c.drawPath(path, neonPaint)

        // cockpit diamond
        path.reset()
        path.moveTo(px, py - h * 0.15f)
        path.lineTo(px + w * 0.12f, py + h * 0.05f)
        path.lineTo(px, py + h * 0.18f)
        path.lineTo(px - w * 0.12f, py + h * 0.05f)
        path.close()
        fillPaint.color = Color.argb(220, 184, 255, 60)
        c.drawPath(path, fillPaint)
    }

    private fun drawEnemy(c: Canvas, e: Enemy) {
        val glow = when (e.kind) {
            EnemyKind.TRIANGLE -> magenta
            EnemyKind.DIAMOND -> lime
            EnemyKind.HEX -> violet
        }
        softPaint.shader = RadialGradient(
            e.x, e.y, e.size,
            intArrayOf(Color.argb(90, Color.red(glow), Color.green(glow), Color.blue(glow)), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        c.drawCircle(e.x, e.y, e.size * 0.85f, softPaint)
        softPaint.shader = null

        path.reset()
        when (e.kind) {
            EnemyKind.TRIANGLE -> {
                path.moveTo(e.x, e.y + e.size * 0.55f)
                path.lineTo(e.x + e.size * 0.5f, e.y - e.size * 0.45f)
                path.lineTo(e.x - e.size * 0.5f, e.y - e.size * 0.45f)
                path.close()
            }
            EnemyKind.DIAMOND -> {
                path.moveTo(e.x, e.y - e.size * 0.55f)
                path.lineTo(e.x + e.size * 0.42f, e.y)
                path.lineTo(e.x, e.y + e.size * 0.55f)
                path.lineTo(e.x - e.size * 0.42f, e.y)
                path.close()
            }
            EnemyKind.HEX -> {
                for (i in 0 until 6) {
                    val a = (Math.PI / 3.0 * i - Math.PI / 6.0).toFloat()
                    val px = e.x + cos(a) * e.size * 0.5f
                    val py = e.y + sin(a) * e.size * 0.5f
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
            }
        }
        fillPaint.color = Color.argb(210, Color.red(glow), Color.green(glow), Color.blue(glow))
        c.drawPath(path, fillPaint)
        neonPaint.style = Paint.Style.STROKE
        neonPaint.strokeWidth = 2.5f
        neonPaint.color = Color.WHITE
        c.drawPath(path, neonPaint)
    }

    private fun drawMeteor(c: Canvas, m: Meteor) {
        softPaint.shader = RadialGradient(
            m.x, m.y, m.radius * 1.6f,
            intArrayOf(Color.argb(120, 255, 138, 61), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        c.drawCircle(m.x, m.y, m.radius * 1.4f, softPaint)
        softPaint.shader = null

        path.reset()
        val spikes = 7
        for (i in 0 until spikes) {
            val a = m.spin + (Math.PI * 2.0 * i / spikes).toFloat()
            val r = if (i % 2 == 0) m.radius else m.radius * 0.62f
            val px = m.x + cos(a) * r
            val py = m.y + sin(a) * r
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        fillPaint.shader = LinearGradient(
            m.x - m.radius, m.y, m.x + m.radius, m.y,
            intArrayOf(orange, Color.rgb(255, 60, 90), Color.rgb(80, 30, 20)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawPath(path, fillPaint)
        fillPaint.shader = null
        neonPaint.style = Paint.Style.STROKE
        neonPaint.strokeWidth = 2f
        neonPaint.color = Color.argb(200, 255, 220, 160)
        c.drawPath(path, neonPaint)
    }

    private fun drawHud(c: Canvas) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = canvasW * 0.048f
        textPaint.color = Color.rgb(232, 240, 255)
        c.drawText("Счёт: $score", canvasW * 0.04f, canvasH * 0.05f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = Color.argb(200, 0, 245, 255)
        c.drawText("Рекорд: $highScore", canvasW * 0.96f, canvasH * 0.05f, textPaint)

        // zone hints faint
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = canvasW * 0.028f
        textPaint.color = Color.argb(50, 255, 255, 255)
        c.drawText("◀", canvasW * 0.16f, canvasH * 0.97f, textPaint)
        c.drawText("ОГОНЬ", canvasW * 0.5f, canvasH * 0.97f, textPaint)
        c.drawText("▶", canvasW * 0.84f, canvasH * 0.97f, textPaint)
    }

    private fun drawTitle(c: Canvas) {
        val pulse = 0.85f + 0.15f * sin(titlePulse * 2.5f)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = canvasW * 0.13f
        textPaint.color = Color.argb((255 * pulse).toInt(), 0, 245, 255)
        c.drawText("SKYBURST", canvasW / 2f, canvasH * 0.28f, textPaint)

        neonPaint.style = Paint.Style.STROKE
        neonPaint.strokeWidth = 2f
        neonPaint.color = magenta
        c.drawLine(canvasW * 0.2f, canvasH * 0.31f, canvasW * 0.8f, canvasH * 0.31f, neonPaint)

        textPaint.textSize = canvasW * 0.042f
        textPaint.color = Color.argb(200, 184, 255, 60)
        c.drawText("Вертикальный аркадный шутер", canvasW / 2f, canvasH * 0.36f, textPaint)

        drawDemoShip(c, canvasW / 2f, canvasH * 0.48f, canvasW * 0.12f)

        drawButton(c, canvasW / 2f, canvasH * 0.66f, canvasW * 0.55f, canvasH * 0.08f, "Старт", cyan)
        textPaint.textSize = canvasW * 0.05f
        textPaint.color = Color.rgb(232, 240, 255)
        c.drawText("Рекорд: $highScore", canvasW / 2f, canvasH * 0.78f, textPaint)

        textPaint.textSize = canvasW * 0.032f
        textPaint.color = Color.argb(140, 200, 210, 255)
        c.drawText("Лево / Огонь / Право", canvasW / 2f, canvasH * 0.88f, textPaint)
    }

    private fun drawDemoShip(c: Canvas, px: Float, py: Float, w: Float) {
        val h = w * 1.15f
        path.reset()
        path.moveTo(px, py - h * 0.55f)
        path.lineTo(px + w * 0.48f, py + h * 0.4f)
        path.lineTo(px + w * 0.18f, py + h * 0.22f)
        path.lineTo(px, py + h * 0.48f)
        path.lineTo(px - w * 0.18f, py + h * 0.22f)
        path.lineTo(px - w * 0.48f, py + h * 0.4f)
        path.close()
        fillPaint.shader = LinearGradient(
            px, py - h, px, py + h,
            intArrayOf(cyan, magenta), null, Shader.TileMode.CLAMP
        )
        c.drawPath(path, fillPaint)
        fillPaint.shader = null
        neonPaint.style = Paint.Style.STROKE
        neonPaint.strokeWidth = 3f
        neonPaint.color = Color.WHITE
        c.drawPath(path, neonPaint)
    }

    private fun drawGameOver(c: Canvas) {
        softPaint.color = Color.argb(160, 0, 0, 20)
        c.drawRect(0f, 0f, canvasW, canvasH, softPaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = canvasW * 0.1f
        textPaint.color = magenta
        c.drawText("Конец игры", canvasW / 2f, canvasH * 0.32f, textPaint)

        textPaint.textSize = canvasW * 0.055f
        textPaint.color = Color.WHITE
        c.drawText("Счёт: $score", canvasW / 2f, canvasH * 0.42f, textPaint)
        textPaint.color = cyan
        c.drawText("Рекорд: $highScore", canvasW / 2f, canvasH * 0.49f, textPaint)
        textPaint.textSize = canvasW * 0.038f
        textPaint.color = Color.argb(180, 184, 255, 60)
        c.drawText("Убито: $kills", canvasW / 2f, canvasH * 0.56f, textPaint)

        drawButton(c, canvasW / 2f, canvasH * 0.68f, canvasW * 0.55f, canvasH * 0.08f, "Ещё раз", lime)
    }

    private fun drawButton(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, label: String, accent: Int) {
        val left = cx - w / 2f
        val top = cy - h / 2f
        val right = cx + w / 2f
        val bottom = cy + h / 2f
        fillPaint.color = Color.argb(50, Color.red(accent), Color.green(accent), Color.blue(accent))
        c.drawRoundRect(left, top, right, bottom, 18f, 18f, fillPaint)
        neonPaint.style = Paint.Style.STROKE
        neonPaint.strokeWidth = 3f
        neonPaint.color = accent
        c.drawRoundRect(left, top, right, bottom, 18f, 18f, neonPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = h * 0.45f
        textPaint.color = Color.WHITE
        c.drawText(label, cx, cy + h * 0.15f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (screen) {
            Screen.TITLE -> {
                if (event.action == MotionEvent.ACTION_UP) {
                    if (hitButton(x, y, canvasW / 2f, canvasH * 0.66f, canvasW * 0.55f, canvasH * 0.08f)) {
                        resetWorld()
                        screen = Screen.PLAYING
                    }
                }
            }
            Screen.GAME_OVER -> {
                if (event.action == MotionEvent.ACTION_UP) {
                    if (hitButton(x, y, canvasW / 2f, canvasH * 0.68f, canvasW * 0.55f, canvasH * 0.08f)) {
                        resetWorld()
                        screen = Screen.PLAYING
                    }
                }
            }
            Screen.PLAYING -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> {
                        touchZone = when {
                            x < canvasW / 3f -> TouchZone.LEFT
                            x > canvasW * 2f / 3f -> TouchZone.RIGHT
                            else -> TouchZone.CENTER
                        }
                        holdingFire = touchZone == TouchZone.CENTER
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                        touchZone = TouchZone.NONE
                        holdingFire = false
                    }
                }
            }
        }
        return true
    }

    private fun hitButton(x: Float, y: Float, cx: Float, cy: Float, w: Float, h: Float): Boolean {
        return x >= cx - w / 2f && x <= cx + w / 2f && y >= cy - h / 2f && y <= cy + h / 2f
    }

    companion object {
        private const val KEY_HIGH = "high_score"
    }
}
