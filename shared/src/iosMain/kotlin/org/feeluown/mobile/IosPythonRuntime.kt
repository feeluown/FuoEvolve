package org.feeluown.mobile

interface IosPythonRuntime {
    fun createBridge(enabledProvidersJson: String): String

    fun call(method: String, arguments: List<String>): String
}

internal fun IosPythonRuntime.checked(result: String): String {
    if (result.startsWith(ERROR_PREFIX)) {
        throw IllegalStateException(result.removePrefix(ERROR_PREFIX))
    }
    return result
}

internal const val ERROR_PREFIX = "__FUO_PYTHON_ERROR__:"
