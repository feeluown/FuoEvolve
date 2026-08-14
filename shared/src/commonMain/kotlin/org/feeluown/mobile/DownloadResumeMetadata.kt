package org.feeluown.mobile

data class DownloadResumeMetadata(
    val resourceKey: String,
    val etag: String? = null,
    val lastModified: String? = null,
)
