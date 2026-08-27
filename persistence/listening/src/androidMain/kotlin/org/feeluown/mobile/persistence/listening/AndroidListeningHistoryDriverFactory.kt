package org.feeluown.mobile.persistence.listening

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.feeluown.mobile.persistence.listening.db.ListeningHistoryDatabase

class AndroidListeningHistoryDriverFactory(
    context: Context,
) : ListeningHistoryDriverFactory {
    private val appContext = context.applicationContext

    override fun createDriver(): SqlDriver = AndroidSqliteDriver(
        schema = ListeningHistoryDatabase.Schema,
        context = appContext,
        name = LISTENING_HISTORY_DATABASE_NAME,
    )
}

private const val LISTENING_HISTORY_DATABASE_NAME = "listening_history.db"
