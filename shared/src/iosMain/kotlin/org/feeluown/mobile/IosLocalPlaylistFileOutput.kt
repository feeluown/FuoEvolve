package org.feeluown.mobile

interface IosLocalPlaylistFileOutput {
    fun importFile(completion: (String?, String?) -> Unit)
    fun exportFile(fileName: String, content: String)
    fun shareFile(fileName: String, content: String)
}
