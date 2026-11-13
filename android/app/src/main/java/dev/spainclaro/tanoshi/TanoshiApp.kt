package dev.spainclaro.tanoshi

import android.app.Application
import dev.spainclaro.tanoshi.settings.AppSettings

class TanoshiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
    }
}
