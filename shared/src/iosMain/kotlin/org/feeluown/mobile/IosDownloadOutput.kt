package org.feeluown.mobile

interface IosDownloadOutput {
    fun download(
        taskId: String,
        url: String,
        headers: Map<String, String>,
        fileName: String,
        lyrics: String?,
        completionHandler: (String?, String?) -> Unit,
    )
    fun pause(taskId: String)
    fun deleteTemporary(taskId: String)
    fun delete(uri: String): Boolean
}
