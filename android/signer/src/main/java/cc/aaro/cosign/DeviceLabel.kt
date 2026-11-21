package cc.aaro.cosign

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * This device as people call it, for the name it joins your account with and the one Marble shows: the name set in
 * the system's settings ("Aaron's tablet"), else its maker and model ("Onn 11 Tablet Pro"), never a bare model
 * number ("100071485") the way joining used to.
 */
object DeviceLabel {
    fun readable(context: Context): String = pick(
        try { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) } catch (_: Exception) { null },
        Build.MANUFACTURER, Build.MODEL,
    )

    /** [set]: the settings name; [maker], [model]: Build's. Kept apart from Android for tests. */
    fun pick(set: String?, maker: String?, model: String?): String {
        val mo = model?.trim().orEmpty()
        if (!set.isNullOrBlank() && set.trim() != mo) return set.trim().take(40)
        val ma = maker?.trim().orEmpty().replaceFirstChar { it.uppercase() }
        return (if (ma.isEmpty() || mo.startsWith(ma, ignoreCase = true)) mo else "$ma $mo").trim().ifEmpty { "This device" }.take(40)
    }

    /** The name devices joined with before: the bare model ("Phone" when there was none). A name like this was never chosen. */
    fun isOldDefault(name: String?, model: String? = Build.MODEL): Boolean {
        val n = name?.trim() ?: return false
        return n == (model ?: "").trim().ifBlank { "Phone" }.take(40)
    }
}
