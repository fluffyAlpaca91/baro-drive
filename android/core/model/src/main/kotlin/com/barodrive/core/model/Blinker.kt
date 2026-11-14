package com.barodrive.core.model

import kotlinx.serialization.Serializable

/**
 * 방향지시등 (설계 6.1.2 `blinker`, Pleos `TurnSignalValue`). JSON 값은 프로토타입과 같은 'OFF'·'LEFT'·'RIGHT'.
 * 깜빡임과 점등은 나누지 않는다 — 켰는지와 어느 쪽인지만 본다.
 */
@Serializable
enum class Blinker {
    OFF,
    LEFT,
    RIGHT,
    ;

    /** 켠 쪽('L'·'R'과 같은 [TurnDir]). 꺼져 있으면 null(core.js `blinkDir`). */
    val dir: TurnDir?
        get() = when (this) {
            OFF -> null
            LEFT -> TurnDir.LEFT
            RIGHT -> TurnDir.RIGHT
        }
}
