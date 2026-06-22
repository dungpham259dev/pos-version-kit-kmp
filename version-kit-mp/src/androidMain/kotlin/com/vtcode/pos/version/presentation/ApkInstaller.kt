package com.vtcode.pos.version.presentation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handles APK installation using PackageInstaller API.
 * Supports silent install on Android 7.0+ (API 24).
 *
 * Internal — consumers should call [com.vtcode.pos.version.presentation.VersionChecker.installUpdate].
 */
internal class ApkInstaller(private val context: Context) {

    companion object {
        private const val INSTALL_SESSION_CODE = 1001
        private const val TAG = "ApkInstaller"
        private const val DEBUG = false
    }

    /** Result of install attempt (internal — not part of public API). */
    internal sealed class InstallResult {
        object Success : InstallResult()
        object PermissionRequired : InstallResult()
        data class Failure(val error: String) : InstallResult()
    }

    /**
     * Install APK file using PackageInstaller.
     * For API 24+, uses PackageInstaller for silent install.
     * Falls back to ACTION_VIEW intent if PackageInstaller fails.
     *
     * @param apkFile The APK file to install
     * @param onComplete Callback when installation is committed
     * @return InstallResult indicating success, failure, or permission required
     */
    fun installApk(apkFile: File, onComplete: () -> Unit): InstallResult {
        if (DEBUG) Log.d(TAG, "Starting APK install: ${apkFile.absolutePath}")

        // Check permission but DON'T auto-open settings
        // Let host app handle UI and permission request flow
        if (!canInstallPackages()) {
            if (DEBUG) Log.w(TAG, "Install permission not granted, returning PermissionRequired")
            return InstallResult.PermissionRequired
        }

        return try {
            installWithPackageInstaller(apkFile, onComplete)
            InstallResult.Success
        } catch (e: Exception) {
            if (DEBUG) Log.e(TAG, "PackageInstaller failed, falling back to intent", e)
            try {
                installWithIntent(apkFile)
                InstallResult.Success
            } catch (e2: Exception) {
                Log.e(TAG, "Install failed", e2)
                InstallResult.Failure(e2.message ?: "Install failed")
            }
        }
    }

    private fun installWithPackageInstaller(apkFile: File, onComplete: () -> Unit) {
        if (DEBUG) Log.d(TAG, "Installing with PackageInstaller: ${apkFile.absolutePath}")

        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = packageInstaller.createSession(params)

        if (DEBUG) Log.d(TAG, "Created install session: $sessionId")

        val session = packageInstaller.openSession(sessionId)
        var committed = false
        try {
            session.openWrite("package", 0, apkFile.length()).use { outputStream ->
                apkFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
                session.fsync(outputStream)
            }

            if (DEBUG) Log.d(TAG, "Copied APK to session")

            val intent = Intent(context, PackageInstallerBroadcastReceiver::class.java).apply {
                action = "${context.packageName}.INSTALL_COMPLETE"
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                INSTALL_SESSION_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (DEBUG) Log.d(TAG, "Committing session...")
            session.commit(pendingIntent.intentSender)
            committed = true

            Handler(Looper.getMainLooper()).post(onComplete)
        } finally {
            if (!committed) {
                try {
                    session.abandon()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to abandon install session", e)
                }
            }
            try {
                session.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close install session", e)
            }
        }
    }

    private fun installWithIntent(apkFile: File) {
        if (DEBUG) Log.d(TAG, "Installing with Intent: ${apkFile.absolutePath}")

        val authority = "${context.packageName}.versionkit.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        context.startActivity(intent)
    }

    /**
     * Check if app has REQUEST_INSTALL_PACKAGES permission.
     */
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            context.checkCallingOrSelfPermission(android.Manifest.permission.REQUEST_INSTALL_PACKAGES) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Request REQUEST_INSTALL_PACKAGES permission by opening system settings.
     */
    fun requestInstallPermission(): Boolean {
        return try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings", e)
            false
        }
    }
}
