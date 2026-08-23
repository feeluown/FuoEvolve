package org.feeluown.mobile

/** Application-layer presentation accessor for structured provider result failures. */
val ProviderSearchResults.errorMessage: String?
    get() = failure?.userMessage

/** Application-layer presentation accessor for structured provider result failures. */
val ProviderContentSection.errorMessage: String?
    get() = failure?.userMessage
