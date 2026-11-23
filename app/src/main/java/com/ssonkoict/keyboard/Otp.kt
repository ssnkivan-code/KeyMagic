package com.ssonkoict.keyboard

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/** The latest one-time code seen in a notification or copied text. */
object OtpStore {
    data class Otp(val code: String, val source: String, val timeMs: Long)

    private const val VALID_MS = 10 * 60 * 1000L
    private const val HIDDEN_HINT_MS = 2 * 60 * 1000L

    @Volatile private var latest: Otp? = null
    @Volatile private var hiddenAt = 0L
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun offer(code: String, source: String) {
        if (code.isEmpty()) return
        latest = Otp(code, source, System.currentTimeMillis())
        notifyListeners()
    }

    /** Android 15+ replaced a notification's text with "Sensitive notification content hidden". */
    fun markHidden() {
        hiddenAt = System.currentTimeMillis()
        notifyListeners()
    }

    fun current(): Otp? = latest?.takeIf { System.currentTimeMillis() - it.timeMs < VALID_MS }

    fun recentlyHidden(): Boolean = System.currentTimeMillis() - hiddenAt < HIDDEN_HINT_MS && current() == null

    /** Hide the chip once used. */
    fun consume() {
        latest = null
        hiddenAt = 0L
        notifyListeners()
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun notifyListeners() = main.post { listeners.forEach { it() } }
}

/**
 * Reads new notifications (SMS, WhatsApp, email, banking apps…) and picks out
 * one-time codes so the keyboard can offer them. Needs "Notification access".
 *
 * Android 15+ hides OTP notifications from apps you installed yourself unless you
 * allow it once over adb (see README / setup screen).
 */
class OtpNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (!Prefs(this).otp) return
        val text = notificationText(sbn.notification.extras ?: return)
        if (text.isBlank()) return
        if (text.contains("Sensitive notification content hidden", ignoreCase = true)) {
            OtpStore.markHidden()
            return
        }
        val code = NativeCore.findOtp(text)
        if (code.isNotEmpty()) OtpStore.offer(code, appName(sbn.packageName))
    }

    private fun notificationText(extras: Bundle): String {
        val parts = ArrayList<String>()
        for (key in listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT)) {
            extras.getCharSequence(key)?.let { parts += it.toString() }
        }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { parts += it.toString() }
        // Chat-style notifications (WhatsApp, Messages) keep each message in a bundle.
        @Suppress("DEPRECATION")
        extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.forEach { m ->
            (m as? Bundle)?.getCharSequence("text")?.let { parts += it.toString() }
        }
        return parts.joinToString("\n")
    }

    private fun appName(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }
}
