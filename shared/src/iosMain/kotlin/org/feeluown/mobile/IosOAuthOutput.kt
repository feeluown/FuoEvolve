package org.feeluown.mobile

interface IosOAuthOutput {
    fun authorize(scopesJson: String, completionHandler: (String?) -> Unit)

    fun clear()
}
