package com.kotenass.skyburst

data class PlayerShip(
    var x: Float,
    var y: Float,
    var width: Float,
    var height: Float,
    var vx: Float = 0f
) {
    fun left() = x - width / 2f
    fun right() = x + width / 2f
    fun top() = y - height / 2f
    fun bottom() = y + height / 2f
}

data class Bullet(
    var x: Float,
    var y: Float,
    var radius: Float,
    var vy: Float,
    var alive: Boolean = true
)

enum class EnemyKind { TRIANGLE, DIAMOND, HEX }

data class Enemy(
    var x: Float,
    var y: Float,
    var size: Float,
    var vy: Float,
    var vx: Float,
    var kind: EnemyKind,
    var hp: Int = 1,
    var alive: Boolean = true,
    var phase: Float = 0f
)

data class Meteor(
    var x: Float,
    var y: Float,
    var radius: Float,
    var vy: Float,
    var vx: Float,
    var spin: Float = 0f,
    var spinSpeed: Float = 2f,
    var alive: Boolean = true
)

data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float,
    var maxLife: Float,
    var color: Int,
    var size: Float
)
