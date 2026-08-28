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
internal class PackageInstallerBroadcastReceiver : BroadcastReceiver() {

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
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                } else {
                    // No confirmation intent supplied — the install can never proceed,
                    // so surface it instead of leaving the UI waiting forever.
                    if (DEBUG) Log.e(TAG, "STATUS_PENDING_USER_ACTION without EXTRA_INTENT")
                    InstallEventBus.emit(
                        InstallEvent.Failure("Không mở được hộp thoại cài đặt")
                    )
                }
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

            // an unrecognised status would otherwise leave the UI waiting forever
            else -> {
                Log.w(TAG, "Unhandled install status: $status")
                InstallEventBus.emit(
                    InstallEvent.Failure(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                )
            }
        }
    }
}
