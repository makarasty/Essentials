package essential.core.service.protect

/*
 * Hardcoded tuning for the protect service. Not in config.yaml; ships with the build, and is left out of
 * it together with the service (-PexcludeModules=protect).
 */

internal object ProtectTuning {
    /** Interval of the "please register" reminder to unregistered players under password auth, in seconds. */
    const val REGISTER_REMINDER_INTERVAL_SECONDS = 20f
}
