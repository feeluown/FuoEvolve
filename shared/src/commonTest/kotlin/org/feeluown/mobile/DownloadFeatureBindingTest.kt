package org.feeluown.mobile

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadFeatureBindingTest {
    @Test
    fun downloadStatesRemainSnapshotObservable() = runTest {
        val states = ObservableDownloadStates<String>(emptyMap())
        val observed = mutableListOf<Map<String, String>>()

        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            snapshotFlow { states.value }
                .take(2)
                .toList(observed)
        }
        runCurrent()

        states.update(mapOf("track" to "downloading"))
        runCurrent()

        assertEquals(
            listOf(
                emptyMap(),
                mapOf("track" to "downloading"),
            ),
            observed,
        )
        collector.cancel()
    }
}
