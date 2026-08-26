package org.feeluown.mobile

private var appListeningHistorySink: ListeningHistorySink = NoOpListeningHistorySink

fun installAppListeningHistorySink(sink: ListeningHistorySink) {
    appListeningHistorySink = sink
}

fun resetAppListeningHistorySink() {
    appListeningHistorySink = NoOpListeningHistorySink
}

internal fun currentAppListeningHistorySink(): ListeningHistorySink = appListeningHistorySink
