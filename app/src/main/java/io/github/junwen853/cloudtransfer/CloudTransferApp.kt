package io.github.junwen853.cloudtransfer

import android.app.Application
import io.github.junwen853.cloudtransfer.backup.BackupState
import io.github.junwen853.cloudtransfer.backup.BackupWorker
import io.github.junwen853.cloudtransfer.backup.loadStoredSummary
import io.github.junwen853.cloudtransfer.data.AppDatabase
import kotlin.concurrent.thread

class CloudTransferApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        BackupWorker.ensureChannels(this)
        BackupWorker.scheduleNightly(this, replace = false)
        thread(name = "restore-summary") {
            runCatching { BackupState.restore(loadStoredSummary(AppDatabase.get(this))) }
        }
    }
}
