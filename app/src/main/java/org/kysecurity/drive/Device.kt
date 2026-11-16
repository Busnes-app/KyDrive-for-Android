package org.kysecurity.drive

import android.content.Context
import android.os.Build

/**
 * The name this phone gets in the web app's device list: the name the user gave the phone,
 * else its model. The server takes at most 100 UTF-8 bytes, no slashes and no control or
 * format characters.
 */
fun deviceName(context: Context): String {
    val given = android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
    return cleanName(given ?: Build.MODEL)
}

internal fun cleanName(raw: String): String {
    val out = StringBuilder()
    var bytes = 0
    for (cp in raw.trim().codePoints().toArray()) {
        val type = Character.getType(cp)
        if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) continue
        val c = if (cp == '/'.code || cp == '\\'.code) ' '.code else cp
        val size = String(Character.toChars(c)).toByteArray().size
        if (bytes + size > 100) break
        out.appendCodePoint(c)
        bytes += size
    }
    return out.toString().trim().ifEmpty { "Android phone" }
}
