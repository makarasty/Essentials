package essential.common.database

import org.jetbrains.exposed.v1.core.vendors.DatabaseDialect
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.core.vendors.MariaDBDialect
import org.jetbrains.exposed.v1.core.vendors.MysqlDialect
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The legacy upgrade picks its script by dialect. Picking the H2 script on any other engine is how a
 * MySQL server ran H2-only syntax during an upgrade, so the pick is asserted here rather than left to
 * a live database.
 *
 * This asserts which script is chosen, not that the chosen script runs clean. The MySQL V4 script is
 * still MariaDB-flavoured - DROP COLUMN IF EXISTS, a literal DEFAULT on a TEXT column, and an
 * AUTO_INCREMENT column added without a key - so a v3 database on MySQL 8.0 still needs a MySQL-only
 * V4 that nobody has written. V5, the step the shared databases actually take, is clean on both.
 */
class LegacyMigrationTest {
    // A dialect's own toString() reads the current transaction, so assertion messages name the class.
    private val DatabaseDialect?.label: String get() = this?.let { it::class.simpleName } ?: "no dialect"

    /** The script the upgrade would actually run, which has to exist as a resource. */
    private fun picked(version: UByte, dialect: DatabaseDialect?): Pair<String, String> {
        val name = legacyScriptPath(version, dialect)
        val resource = LegacyMigrationTest::class.java.classLoader.getResource(name)
        assertNotNull(resource, "no upgrade script at $name for ${dialect.label} at v$version")
        return name to resource.readText()
    }

    @Test
    fun mysqlNeverGetsTheH2Script() {
        for (version in listOf<UByte>(4u, 5u)) {
            for (dialect in listOf(MysqlDialect(), MariaDBDialect(), null)) {
                val (name, script) = picked(version, dialect)
                assertTrue(name.contains("/mysql/"), "${dialect.label} picked $name")
                // The two pieces of H2-only syntax that used to reach MySQL through an _h2 fallback.
                assertFalse(
                    script.contains("CURRENT_TIMESTAMP(9)"),
                    "$name uses a fractional-seconds precision MySQL 8.0 rejects"
                )
                assertFalse(
                    script.contains("ADD COLUMN IF NOT EXISTS", ignoreCase = true),
                    "$name uses ADD COLUMN IF NOT EXISTS, which MySQL 8.0 does not accept"
                )
            }
        }
        assertTrue(picked(5u, MysqlDialect()).second.contains("DROP INDEX"))
    }

    @Test
    fun everyOtherDialectStillGetsItsOwnScript() {
        assertEquals("db/migration/h2/V5__legacy_migrate_h2.sql", picked(5u, H2Dialect()).first)
        assertEquals("db/migration/h2/V4__legacy_migrate_h2.sql", picked(4u, H2Dialect()).first)
        assertEquals("db/migration/postgres/V5__legacy_migrate_postgres.sql", picked(5u, PostgreSQLDialect()).first)
        assertEquals("db/migration/postgres/V4__legacy_migrate_postgres.sql", picked(4u, PostgreSQLDialect()).first)
    }

    @Test
    fun noLegacyScriptContainsASemicolonOutsideAStatementEnd() {
        // applyLegacyScript splits on every semicolon, so one inside a comment feeds half a comment to
        // the engine as a statement.
        for (profile in listOf(H2Dialect(), PostgreSQLDialect(), MysqlDialect())) {
            for (version in listOf<UByte>(4u, 5u)) {
                val (name, script) = picked(version, profile)
                val comments = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).findAll(script)
                assertTrue(comments.none { it.value.contains(';') }, "$name has a semicolon inside a comment")
            }
        }
    }
}
