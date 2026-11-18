package org.yaqmc.android.nativeui

import android.view.KeyEvent
import org.json.JSONObject

/** Key names match the shared SDK accelerator vocabulary. Android consumes only known bindings. */
internal fun pluginKeyEvent(event: KeyEvent): JSONObject? {
    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return null
    val key = when (event.keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A'.code + event.keyCode - KeyEvent.KEYCODE_A).toChar().toString()
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (event.keyCode - KeyEvent.KEYCODE_0).toString()
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${event.keyCode - KeyEvent.KEYCODE_F1 + 1}"
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
        KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
        KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
        KeyEvent.KEYCODE_MOVE_HOME -> "Home"
        KeyEvent.KEYCODE_MOVE_END -> "End"
        KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
        KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
        KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
        KeyEvent.KEYCODE_DEL -> "Backspace"
        else -> return null
    }
    return JSONObject().put("code", key).put("ctrlKey", event.isCtrlPressed).put("altKey", event.isAltPressed).put("shiftKey", event.isShiftPressed).put("metaKey", event.isMetaPressed)
}

internal fun pluginAccelerator(event: JSONObject): String = buildList {
    if (event.optBoolean("ctrlKey")) add("control")
    if (event.optBoolean("altKey")) add("alt")
    if (event.optBoolean("shiftKey")) add("shift")
    if (event.optBoolean("metaKey")) add("meta")
    add(event.getString("code"))
}.joinToString("+")
