package org.feeluown.mobile.persistence.listening

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import org.feeluown.mobile.persistence.listening.db.ListeningHistoryDatabase

class IosListeningHistoryDriverFactory : ListeningHistoryDriverFactory {
    override fun createDriver(): SqlDriver = NativeSqliteDriver(
        schema = ListeningHistoryDatabase.Schema,
        name = LISTENING_HISTORY_DATABASE_NAME,
    )
}

private const val LISTENING_HISTORY_DATABASE_NAME = "listening_history.db"
