package org.feeluown.mobile

interface IosDownloadOutput {
    fun download(
        url: String,
        headers: Map<String, String>,
        fileName: String,
        lyrics: String?,
        completionHandler: (String?, String?) -> Unit,
    )
    fun delete(uri: String): Boolean
}
