package org.feeluown.mobile.persistence.listening

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import java.nio.file.Path
import org.feeluown.mobile.persistence.listening.db.ListeningHistoryDatabase

class DesktopListeningHistoryDriverFactory(
    private val databasePath: Path,
) : ListeningHistoryDriverFactory {
    override fun createDriver(): SqlDriver {
        val parent = requireNotNull(databasePath.parent) { "Listening history database must have a parent directory" }
        Files.createDirectories(parent)
        val needsCreate = !Files.isRegularFile(databasePath) || Files.size(databasePath) == 0L
        return JdbcSqliteDriver("jdbc:sqlite:${databasePath.toAbsolutePath()}").also { driver ->
            if (needsCreate) ListeningHistoryDatabase.Schema.create(driver)
        }
    }
}
