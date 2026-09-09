package essential.core

import PluginTest.Companion.loadGame
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The level effect task is scheduled from ServerLoadEvent and cancels itself when it sees the
 * feature switched off, so turning it back on used to need a server restart.
 */
class LevelEffectLifecycleTest {
    companion object {
        private var done = false
    }

    private lateinit var originalConf: CoreConfig

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
        originalConf = Main.conf
        // Every optional module off, or reloadEnabledConfigurations reaches invokeCompanion for a
        // service this harness cannot construct and the Log.err it prints fails the test.
        Main.conf = originalConf.copy(
            module = Module(
                achievement = false,
                bridge = false,
                chat = false,
                contribution = false,
                discord = false,
                protect = false,
                web = false,
            ),
            feature = originalConf.feature.copy(
                level = originalConf.feature.level.copy(
                    effect = originalConf.feature.level.effect.copy(enabled = true)
                )
            ),
        )
    }

    @AfterTest
    fun teardown() {
        ModuleRuntime.levelEffects?.cancel()
        Main.conf = originalConf
    }

    @Test
    fun reloadingTheConfigPutsTheEffectsBack() {
        ModuleRuntime.levelEffects?.cancel()

        // What EffectSystem.run() does to itself the moment it sees the feature switched off.
        // Before this fix, nothing but ServerLoadEvent ever scheduled it again.
        ModuleRuntime.reloadEnabledConfigurations()

        val task = ModuleRuntime.levelEffects
        assertNotNull(task, "a config reload with the feature on has to schedule the effects")
        assertTrue(task.isScheduled)
    }

    @Test
    fun schedulingTwiceKeepsOneTask() {
        ModuleRuntime.levelEffects?.cancel()

        ModuleRuntime.scheduleLevelEffects()
        val first = ModuleRuntime.levelEffects
        assertNotNull(first, "the effect service is on the test classpath, so it has to load")
        assertTrue(first.isScheduled)

        ModuleRuntime.scheduleLevelEffects()
        assertSame(
            first,
            ModuleRuntime.levelEffects,
            "a second call while the task is live would leave the first one running with nothing holding it"
        )
    }

    @Test
    fun aCancelledTaskCanBeScheduledAgain() {
        ModuleRuntime.scheduleLevelEffects()
        val first = ModuleRuntime.levelEffects
        assertNotNull(first)

        first.cancel()

        ModuleRuntime.scheduleLevelEffects()
        val second = ModuleRuntime.levelEffects
        assertNotNull(second)
        assertNotSame(first, second, "re-enabling the feature has to put a live task back on the timer")
        assertTrue(second.isScheduled)
    }
}
