package com.ssonkoict.keyboard

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Key-press vibration (adjustable strength) and typing sounds (adjustable volume). */
class Feedback(context: Context, private val prefs: Prefs) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun onKeyDown(kind: Int) {
        if (prefs.vibrate) vibrate(prefs.vibrateStrength)
        if (prefs.sound) click(kind, prefs.soundVolume)
    }

    /**
     * One crisp, hard "knock" per key press. strength 1–100.
     *
     * Uses the phone's tuned haptic click (the same effect system buttons use)
     * instead of running the motor for a few milliseconds, which feels like a
     * soft buzz. Any previous knock is cut off first so fast typing stays a
     * series of separate knocks, never a blur.
     */
    fun vibrate(strength: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val s = strength.coerceIn(1, 100)
        v.cancel()
        when {
            // Best: haptic primitive "click", scaled by strength (Android 11+, most Samsungs).
            Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) -> {
                val scale = (0.35f + 0.65f * s / 100f).coerceIn(0f, 1f)
                v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                        .compose(),
                )
            }
            // Android 10+: predefined click / heavy click.
            Build.VERSION.SDK_INT >= 29 -> {
                val id = if (s >= 50) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK
                v.vibrate(VibrationEffect.createPredefined(id))
            }
            // Older phones: one short pulse, near full power.
            Build.VERSION.SDK_INT >= 26 -> {
                val amp = if (v.hasAmplitudeControl()) (110 + s * 145 / 100).coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
                v.vibrate(VibrationEffect.createOneShot(KNOCK_MS, amp))
            }
            else -> {
                @Suppress("DEPRECATION")
                v.vibrate(KNOCK_MS)
            }
        }
    }

    private companion object {
        const val KNOCK_MS = 18L
    }

    fun click(kind: Int, volume: Int) {
        val fx = when (kind) {
            KeyCore.KIND_BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
            KeyCore.KIND_SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            KeyCore.KIND_ENTER -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        audio.playSoundEffect(fx, volume.coerceIn(0, 100) / 100f)
    }
}
