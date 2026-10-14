package essential.core.service.achievements

/*
 * Hardcoded tuning for the achievements service. Not in config.yaml; ships with the build, and is left out
 * of it together with the service (-PexcludeModules=achievements). Each achievement's own goal stays in
 * Achievement.kt and AchievementEvents.kt, next to the bundle text that states it.
 */

/** Actions-per-minute tracking (APMTracker). */
internal object ApmTuning {
    /** Window the APM is measured over, in milliseconds (5 minutes). */
    const val WINDOW_MS = 5 * 60 * 1000

    /** Most action timestamps stored per player. */
    const val MAX_ACTION_TIMESTAMPS = 1000

    /** Taps closer together than this count as one action, in milliseconds. */
    const val DEBOUNCE_TAP_MS = 100L

    /** How long a player must hold an APM level before its achievement is awarded, in milliseconds. */
    const val SUSTAIN_MS = 60 * 1000
}

/** Kill tracking in AchievementEvents. */
internal object KillTuning {
    /** Kills further apart than this start a new combo for the Quill and Zenith killer achievements, in milliseconds. */
    const val COMBO_WINDOW_MS = 10000
}
