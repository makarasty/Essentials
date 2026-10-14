package essential.core.service.vote

/*
 * Hardcoded tuning for the vote service. Not in config.yaml; ships with the build, and is left out of it
 * together with the service (-PexcludeModules=vote). Starting a vote is tuned in essential/core/Tuning.kt.
 */

internal object VoteTuning {
    /** Seconds a vote stays open; the task counts this down once a second. */
    const val COUNTDOWN_SECONDS = 60

    /** How often a running vote reminds players to vote, in seconds of the countdown. */
    const val REMINDER_INTERVAL_SECONDS = 10

    /** How long a passed kick vote keeps its target out, in milliseconds: three hours. */
    const val KICK_DURATION_MS = 3L * 60 * 60 * 1000

    /** Wait before the starter of a passed game-over, draw or skip vote may start another, in minutes. */
    const val STARTER_COOLDOWN_MINUTES = 3

    /** Wait before the starter of a passed `vote random` may start another vote, in minutes. */
    const val RANDOM_STARTER_COOLDOWN_MINUTES = 7

    /** Server-wide wait for the next vote after a passed `vote random`, in minutes. */
    const val RANDOM_NEXT_VOTE_MINUTES = 5

    /** Delay between a `vote random` passing and its outcome being rolled, in seconds. */
    const val RANDOM_OUTCOME_DELAY_SECONDS = 3f

    /** Runs of the `vote random` fire decay; the core is resupplied halfway through. */
    const val FIRE_DECAY_TICKS = 600

    /** Interval between two runs of the fire decay, in seconds. */
    const val FIRE_DECAY_INTERVAL_SECONDS = 10f

    /**
     * Roughly how many fire effects the `vote random` fire outcome broadcasts over the whole map. One
     * broadcast per tile was 40,000 packets to every player in a single frame on a 200x200 map; a stride
     * keeps it near a thousand.
     */
    const val FIRE_EFFECT_TARGET_COUNT = 1000.0
}
