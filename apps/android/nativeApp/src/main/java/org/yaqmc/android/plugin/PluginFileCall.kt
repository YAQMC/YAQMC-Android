package org.yaqmc.android.plugin

import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** The upstream SAF implementation only needs a once-only reply, not a Capacitor bridge. */
internal class PluginFileCall(private val reply: (Result<Any?>) -> Unit) {
    val callbackId: String = UUID.randomUUID().toString()
    private val complete = AtomicBoolean(false)
    fun resolve(envelope: JSONObject) { if (complete.compareAndSet(false, true)) reply(Result.success(envelope.opt("value").takeUnless { it === JSONObject.NULL })) }
    fun reject(code: String) { if (complete.compareAndSet(false, true)) reply(Result.failure(IllegalStateException(code))) }
}
