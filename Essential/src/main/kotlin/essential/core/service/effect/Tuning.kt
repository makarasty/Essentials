package essential.core.service.effect

import mindustry.Vars

/*
 * Hardcoded tuning for level effects. Not in config.yaml; ships with the build, and is left out of it
 * together with the service (-PexcludeModules=effect). The task interval is in essential/core/Tuning.kt.
 */

internal object EffectTuning {
    /**
     * Extra distance around a viewer's last reported camera within which effects are still sent, in
     * world units: eight tiles of camera travel between a client's view report and an effect reaching it.
     */
    const val VIEW_MARGIN = 8f * Vars.tilesize
}
