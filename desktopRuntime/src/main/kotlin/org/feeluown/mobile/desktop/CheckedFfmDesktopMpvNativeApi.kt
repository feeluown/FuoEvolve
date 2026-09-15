package org.feeluown.mobile.desktop

import org.feeluown.mobile.DesktopMpvNativeApi

fun createCheckedDesktopFfmMpvNativeApi(): DesktopMpvNativeApi {
    val delegate = createDesktopFfmMpvNativeApi()
    return object : DesktopMpvNativeApi by delegate {
        override fun renderD3D11(renderContext: Long, renderTarget: Long): Boolean {
            if (!delegate.renderD3D11(renderContext, renderTarget)) {
                throw IllegalStateException("Windows D3D11 libmpv render failed")
            }
            return true
        }
    }
}
