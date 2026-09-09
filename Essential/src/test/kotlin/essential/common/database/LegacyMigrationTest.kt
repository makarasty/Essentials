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
 * This asserts which script is chosen, not that the chosen script runs clean. v4.sql is still
 * MariaDB-flavoured - DROP COLUMN IF EXISTS, a literal DEFAULT on a TEXT column, and an
 * AUTO_INCREMENT column added without a key - so a v3 database on MySQL 8.0 still needs a
 * v4_mysql.sql that nobody has written. v5.sql, the step the shared databases actually take, is
 * clean on both.
 */
class LegacyMigrationTest {
    /** The script the upgrade would actually run: the first candidate that exists as a resource. */
    // A dialect's own toString() reads the current transaction, so assertion messages name the class.
    private val DatabaseDialect?.label: String get() = this?.let { it::class.simpleName } ?: "no dialect"

    private fun picked(version: UByte, dialect: DatabaseDialect?): Pair<String, String> {
        val loader = LegacyMigrationTest::class.java.classLoader
        val name = legacySqlCandidates(version, dialect).firstOrNull { loader.getResource("sql/$it") != null }
        assertNotNull(name, "no upgrade script resolved for ${dialect.label} at v$version")
        return name to loader.getResource("sql/$name")!!.readText()
    }

    @Test
    fun mysqlNeverFallsBackToTheH2Script() {
        for (version in listOf<UByte>(4u, 5u)) {
            for (dialect in listOf(MysqlDialect(), MariaDBDialect())) {
                val (name, script) = picked(version, dialect)
                assertFalse(name.contains("_h2"), "${dialect.label} picked the H2 script $name")
                assertFalse(name.contains("_postgres"), "${dialect.label} picked the PostgreSQL script $name")
                // The two pieces of H2-only syntax that used to reach MySQL through the _h2 fallback.
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
    }

    @Test
    fun theGenericScriptIsReachable() {
        // v4.sql and v5.sql are the MySQL-flavoured scripts; with an _h2 fallback ahead of them they
        // were dead files no dialect could select.
        assertEquals("v5.sql", picked(5u, MysqlDialect()).first)
        assertEquals("v4.sql", picked(4u, MysqlDialect()).first)
        assertTrue(picked(5u, MysqlDialect()).second.contains("DROP INDEX"))
    }

    @Test
    fun everyOtherDialectStillGetsItsOwnScript() {
        assertEquals("v5_h2.sql", picked(5u, H2Dialect()).first)
        assertEquals("v4_h2.sql", picked(4u, H2Dialect()).first)
        assertEquals("v5_postgres.sql", picked(5u, PostgreSQLDialect()).first)
        assertEquals("v4_postgres.sql", picked(4u, PostgreSQLDialect()).first)
    }

    @Test
    fun theDialectScriptIsPreferredOverTheGenericOne() {
        assertEquals(listOf("v5_h2.sql", "v5.sql"), legacySqlCandidates(5u, H2Dialect()))
        assertEquals(listOf("v5_mysql.sql", "v5.sql"), legacySqlCandidates(5u, MysqlDialect()))
        assertEquals(listOf("v5_mariadb.sql", "v5.sql"), legacySqlCandidates(5u, MariaDBDialect()))
        assertEquals(listOf("v5.sql"), legacySqlCandidates(5u, null))
    }
}
