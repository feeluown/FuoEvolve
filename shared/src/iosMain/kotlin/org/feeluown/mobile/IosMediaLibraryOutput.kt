package org.feeluown.mobile

interface IosMediaLibraryOutput {
    fun hasPermission(): Boolean
    fun requestPermission(completionHandler: (Boolean) -> Unit)
    fun tracksJson(): String
}
