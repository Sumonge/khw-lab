package com.khwlab.hub

import android.app.Application
import com.khwlab.hub.core.plugin.CardRegistry
import com.khwlab.hub.feature.camera.CameraCardPlugin
import com.khwlab.hub.feature.lock.LockCardPlugin

class App : Application() {
    val registry: CardRegistry by lazy {
        CardRegistry()
            .register(CameraCardPlugin())
            .register(LockCardPlugin())
    }
}
