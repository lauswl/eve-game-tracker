package eve.game.tracker.ui.components

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eve.game.tracker.R

/**
 * The noise a control makes when it is pressed.
 *
 * Not `View.playSoundEffect`, which is what this used to be. That call is gated
 * on the phone's *Touch sounds* switch — a setting three menus deep, off by
 * default on plenty of phones, and nothing to do with volume, Do Not Disturb or
 * the ringer. So the app was asking politely and being silently declined, which
 * is indistinguishable from having no sound at all.
 *
 * It has a switch in Setup, and that switch is not a setting for a fact — it is
 * a preference about a room. A table in a quiet flat at midnight wants it off
 * and the phone's own volume control is the wrong instrument for that, because
 * it is shared with everything else the phone does.
 *
 * The switch is a [SharedPreferences] flag rather than a database row: it is
 * about this phone, not about the games played on it, so DELETE ALL DATA does
 * not reach it.
 *
 * [USAGE_GAME][AudioAttributes.USAGE_GAME] deliberately, not
 * `USAGE_ASSISTANCE_SONIFICATION`. Sonification is routed to the system stream,
 * which silences with the ringer — so the click would vanish the moment someone
 * flipped their phone to silent to play cards, which is exactly when it is
 * being used. This follows the media volume instead.
 */
object ClickSound {

    private var pool: SoundPool? = null
    private var sample = 0
    private var prefs: SharedPreferences? = null

    @Volatile private var ready = false

    /** Snapshot state so the switch in Setup redraws when it is pressed. */
    var enabled by mutableStateOf(true)
        private set

    /** Named `enable`, not `setEnabled`: that is the property's own setter. */
    fun enable(on: Boolean) {
        enabled = on
        prefs?.edit()?.putBoolean(KEY, on)?.apply()
    }

    private const val KEY = "click_sound"

    /**
     * Loads the sample. Called once from the activity, off the press path —
     * `SoundPool.load` is asynchronous and the first press must not be the
     * thing that waits for the disk.
     */
    fun prime(context: Context) {
        if (pool != null) return
        prefs = context.applicationContext
            .getSharedPreferences("livery", Context.MODE_PRIVATE)
            .also { enabled = it.getBoolean(KEY, true) }
        val p = SoundPool.Builder()
            // Six: a fast scorekeeper can overlap a handful of presses, and a
            // click that gets dropped because the last one is still ringing is
            // a press that felt like it did not register.
            .setMaxStreams(6)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        p.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        sample = p.load(context.applicationContext, R.raw.click, 1)
        pool = p
    }

    /** Silent until the sample is in memory, rather than throwing or stuttering. */
    fun play() {
        if (!enabled || !ready) return
        pool?.play(sample, 1f, 1f, 1, 0, 1f)
    }
}
