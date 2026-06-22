package com.vtcode.pos.version.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Broadcast receiver for PackageInstaller session callbacks.
 * Handles install completion and restart app.
 */
class PackageInstallerBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ApkInstaller"
        private const val DEBUG = false
    }

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (DEBUG) {
            Log.d(TAG, "BroadcastReceiver onReceive called with action: ${intent.action}")
            Log.d(TAG, "Extras: ${intent.extras?.keySet()?.toList()}")
        }

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        if (DEBUG) Log.d(TAG, "Install status: $status")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // User needs to confirm install
                // Using deprecated getParcelableExtra for API compatibility (API 33+ has new method)
                val confirmIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmIntent)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                if (DEBUG) Log.i(TAG, "Installation successful!")
                // App will be restarted by system after install
                InstallEventBus.emit(InstallEvent.Success)
            }
            // STATUS_FAILURE_ABORTED covers the user cancelling the system install
            // dialog — surface it so the UI can clear its installing state instead
            // of silently hanging.
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                if (DEBUG) Log.e(TAG, "Installation failed: $message")
                InstallEventBus.emit(InstallEvent.Failure(message))
            }
        }
    }
}
