package org.feeluown.mobile.persistence.listening

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.feeluown.mobile.ListeningHistoryRecord
import org.feeluown.mobile.ListeningHistorySink
import org.feeluown.mobile.persistence.listening.db.ListeningHistoryDatabase

interface ListeningHistoryDriverFactory {
    fun createDriver(): SqlDriver
}

class SqlDelightListeningHistoryStore(
    driverFactory: ListeningHistoryDriverFactory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ListeningHistorySink {
    private val database = ListeningHistoryDatabase(driverFactory.createDriver())
    private val mutex = Mutex()

    override suspend fun upsert(record: ListeningHistoryRecord) {
        withContext(dispatcher) {
            mutex.withLock {
                database.transaction {
                    val queries = database.listeningHistoryQueries
                    record.resources
                        .map { it.resource }
                        .distinctBy { it.resourceKey }
                        .forEach { resource ->
                            queries.upsertResource(
                                resourceKey = resource.resourceKey,
                                resourceType = resource.type.name,
                                sourceId = resource.sourceId,
                                sourceResourceId = resource.sourceResourceId,
                                title = resource.title,
                                subtitle = resource.subtitle,
                                coverUrl = resource.coverUrl,
                                metadataJson = resource.metadataJson,
                                updatedAt = record.updatedAtMillis,
                            )
                        }
                    queries.upsertEvent(
                        sessionKey = record.sessionKey,
                        primaryResourceKey = record.primaryResourceKey,
                        startedAt = record.startedAtMillis,
                        endedAt = record.endedAtMillis,
                        playedMs = record.playedMs,
                        durationMs = record.durationMs,
                        qualified = if (record.qualified) 1L else 0L,
                        startReason = record.startReason.name,
                        completionReason = record.completionReason?.name,
                        contextSessionKey = record.contextSessionKey,
                        updatedAt = record.updatedAtMillis,
                    )
                    queries.deleteEventRelations(record.sessionKey)
                    record.resources
                        .distinctBy { it.relation to it.resource.resourceKey }
                        .forEach { relation ->
                            queries.insertEventRelation(
                                sessionKey = record.sessionKey,
                                resourceKey = relation.resource.resourceKey,
                                relationType = relation.relation.name,
                            )
                        }
                }
            }
        }
    }

    suspend fun eventCount(): Long = withContext(dispatcher) {
        mutex.withLock { database.listeningHistoryQueries.countEvents().executeAsOne() }
    }

    suspend fun clear() {
        withContext(dispatcher) {
            mutex.withLock {
                database.transaction {
                    database.listeningHistoryQueries.clearEventRelations()
                    database.listeningHistoryQueries.clearEvents()
                    database.listeningHistoryQueries.clearResources()
                }
            }
        }
    }
}
