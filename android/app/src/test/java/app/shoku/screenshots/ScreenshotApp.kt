package app.shoku.screenshots

import android.util.Log
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.shoku.ShokuApplication
import app.shoku.data.net.Network

/**
 * The real app, started for screenshots. Refuses to start unless the network switch is on, and gives
 * WorkManager a test instance whose delayed and periodic work never runs on its own (sync isn't
 * scheduled at all: with the switch on, Backend.configured is false).
 */
class ScreenshotApp : ShokuApplication() {
    override fun onCreate() {
        check(Network.blocked) { "Screenshot tests need -Dshoku.network=off (set in app/build.gradle.kts)" }
        check(!sync.configured) { "Sync must be off in screenshot tests" }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            this,
            Configuration.Builder().setMinimumLoggingLevel(Log.WARN).setExecutor(SynchronousExecutor()).build(),
        )
        // Demo data is seeded as it is; skip the one-time regrouping of foods into families.
        getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("foodFamilies_v1", true).commit()
        super.onCreate()
    }
}
