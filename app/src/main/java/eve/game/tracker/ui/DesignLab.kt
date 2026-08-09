package eve.game.tracker.ui

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eve.game.tracker.ui.components.TileScale

/**
 * Temporary switches for the layouts that are still being chosen between.
 *
 * They are set from the launch intent so a variant can be photographed on the
 * real device without a rebuild between shots:
 *
 * ```
 * adb shell am start -n eve.game.tracker/.MainActivity --ei tiles 3
 * ```
 *
 * Every switch comes out once its choice is made — a design decision that stays
 * configurable is a design decision that was never actually taken. Three have
 * already gone this way: the bottom bar (three variants, photographed, one
 * kept), the kerb, which lost outright and took its switch with it, and `stats`,
 * whose three layouts were all replaced by one card per game.
 */
object DesignLab {

    /**
     * How much of a game tile is type. `--ei tiles 0|1|2|3` walks the four
     * options drawn in `home-sizes.html`, from today's setting to the largest
     * the tile can physically hold.
     */
    var tiles by mutableStateOf(TileScale.TIGHT)
        private set

    /** `--ei demo 1` fills an empty database with a season of game nights. */
    var demo by mutableStateOf(false)
        private set

    fun readFrom(intent: Intent?) {
        intent?.getIntExtra("tiles", -1)?.takeIf { it >= 0 }?.let {
            tiles = TileScale.entries[it.coerceIn(0, TileScale.entries.lastIndex)]
        }
        if (intent?.getIntExtra("demo", 0) == 1) demo = true
    }
}
