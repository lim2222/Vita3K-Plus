package org.vita3k.emulator.overlay

object OverlayButtonRegistry {
    // 顺序即 bit 位索引,新增按钮永远加在最后,不要中途插入/删除,否则旧存档位错乱
    val ids: List<Int> = listOf(
        InputOverlay.ButtonType.BUTTON_CROSS,      // bit 0
        InputOverlay.ButtonType.BUTTON_CIRCLE,     // bit 1
        InputOverlay.ButtonType.BUTTON_SQUARE,     // bit 2
        InputOverlay.ButtonType.BUTTON_TRIANGLE,   // bit 3
        InputOverlay.ButtonType.BUTTON_SELECT,     // bit 4
        InputOverlay.ButtonType.BUTTON_START,      // bit 5
        InputOverlay.ButtonType.BUTTON_PS,         // bit 6
        InputOverlay.ButtonType.DPAD_UP,           // bit 7 (整个方向键当一个开关)
        InputOverlay.ButtonType.STICK_LEFT,        // bit 8
        InputOverlay.ButtonType.STICK_RIGHT,       // bit 9
        InputOverlay.ButtonType.TRIGGER_L,         // bit 10
        InputOverlay.ButtonType.TRIGGER_R,         // bit 11
        InputOverlay.ButtonType.TRIGGER_L2,        // bit 12
        InputOverlay.ButtonType.TRIGGER_R2,        // bit 13
        InputOverlay.ButtonType.TRIGGER_L3,        // bit 14
        InputOverlay.ButtonType.TRIGGER_R3,        // bit 15
        InputOverlay.ButtonType.BUTTON_TOUCH_SWITCH, // bit 16
        InputOverlay.ButtonType.BUTTON_TOUCH_HIDE,   // bit 17
        InputOverlay.ButtonType.TATACON              // bit 18 — 之后加鼓时用
    )

    fun bitFor(legacyId: Int): Long {
        val idx = ids.indexOf(legacyId)
        return if (idx < 0) 0L else (1L shl idx)
    }

    // 默认全开(不含鼓,鼓是新功能默认关闭,避免打扰现有用户)
    fun defaultMask(): Long =
        ids.fold(0L) { acc, id -> if (id == InputOverlay.ButtonType.TATACON) acc else acc or bitFor(id) }
}