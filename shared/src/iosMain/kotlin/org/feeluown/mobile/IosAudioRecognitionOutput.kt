package org.feeluown.mobile

interface IosAudioRecognitionOutput {
    fun hasPermission(): Boolean

    fun requestPermission(completionHandler: (Boolean) -> Unit)

    fun recognize(
        eventHandler: (String, String, String) -> Unit,
        completionHandler: (String?, String?) -> Unit,
    )

    fun cancel()
}
