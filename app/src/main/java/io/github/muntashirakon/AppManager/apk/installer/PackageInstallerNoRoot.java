/*
 * Copyright (C) 2020 Muntashir Al-Islam
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.muntashirakon.AppManager.apk.installer;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import androidx.annotation.NonNull;
import io.github.muntashirakon.AppManager.apk.ApkFile;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.utils.AppPref;
import io.github.muntashirakon.AppManager.utils.IOUtils;

public final class PackageInstallerNoRoot extends AMPackageInstaller {
    public static final String TAG = "PINR";

    @SuppressLint("StaticFieldLeak")
    private static PackageInstallerNoRoot INSTANCE;

    public static PackageInstallerNoRoot getInstance() {
        if (INSTANCE == null) INSTANCE = new PackageInstallerNoRoot();
        return INSTANCE;
    }

    private PackageInstaller packageInstaller;
    private PackageInstaller.Session session;
    private String packageName;
    private int sessionId = -1;

    private PackageInstallerNoRoot() {
    }

    @Override
    public boolean install(@NonNull ApkFile apkFile) {
        packageName = apkFile.getPackageName();
        Log.d(TAG, "Install: opening session...");
        if (!openSession()) return false;
        List<ApkFile.Entry> selectedEntries = apkFile.getSelectedEntries();
        Log.d(TAG, "Install: selected entries: " + selectedEntries.size());
        // Write apk files
        for (ApkFile.Entry entry : selectedEntries) {
            try (InputStream apkInputStream = entry.getSignedInputStream(context);
                 OutputStream apkOutputStream = session.openWrite(entry.getFileName(), 0, entry.getFileSize())) {
                IOUtils.copy(apkInputStream, apkOutputStream);
                session.fsync(apkOutputStream);
                Log.d(TAG, "Install: copied entry " + entry.name);
            } catch (IOException e) {
                sendCompletedBroadcast(packageName, STATUS_FAILURE_SESSION_WRITE, sessionId);
                Log.e(TAG, "Install: Cannot copy files to session.", e);
                return abandon();
            } catch (SecurityException e) {
                sendCompletedBroadcast(packageName, STATUS_FAILURE_SECURITY, sessionId);
                Log.e(TAG, "Install: Cannot access apk files.", e);
                return abandon();
            }
        }
        Log.d(TAG, "Install: Running installation...");
        commit();
        return true;
    }

    @Override
    public boolean install(@NonNull File[] apkFiles, String packageName) {
        this.packageName = packageName;
        if (!openSession()) return false;
        // Write apk files
        for (File apkFile : apkFiles) {
            try (InputStream apkInputStream = new FileInputStream(apkFile);
                 OutputStream apkOutputStream = session.openWrite(apkFile.getName(), 0, apkFile.length())) {
                IOUtils.copy(apkInputStream, apkOutputStream);
                session.fsync(apkOutputStream);
            } catch (IOException e) {
                sendCompletedBroadcast(packageName, STATUS_FAILURE_SESSION_WRITE, sessionId);
                Log.e(TAG, "Install: Cannot copy files to session.", e);
                return abandon();
            } catch (SecurityException e) {
                sendCompletedBroadcast(packageName, STATUS_FAILURE_SECURITY, sessionId);
                Log.e(TAG, "Install: Cannot access apk files.", e);
                return abandon();
            }
        }
        // Commit
        commit();
        return true;
    }

    @Override
    boolean commit() {
        Log.d(TAG, "Commit: calling activity to request permission...");
        Intent callbackIntent = new Intent(AMPackageInstallerBroadcastReceiver.ACTION_PI_RECEIVER);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, callbackIntent, 0);
        session.commit(pendingIntent.getIntentSender());
        return true;
    }

    @Override
    boolean openSession() {
        packageInstaller = context.getPackageManager().getPackageInstaller();
        // Clean old sessions
        cleanOldSessions();
        // Create install session
        PackageInstaller.SessionParams sessionParams = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sessionParams.setInstallLocation((Integer) AppPref.get(AppPref.PrefKey.PREF_INSTALLER_INSTALL_LOCATION_INT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            sessionParams.setInstallReason(PackageManager.INSTALL_REASON_USER);
        try {
            sessionId = packageInstaller.createSession(sessionParams);
            Log.d(TAG, "OpenSession: session id " + sessionId);
        } catch (IOException e) {
            sendCompletedBroadcast(packageName, STATUS_FAILURE_SESSION_CREATE, sessionId);
            Log.e(TAG, "OpenSession: Failed to create install session.", e);
            return false;
        }
        try {
            session = packageInstaller.openSession(sessionId);
            Log.d(TAG, "OpenSession: session opened.");
        } catch (IOException e) {
            sendCompletedBroadcast(packageName, STATUS_FAILURE_SESSION_CREATE, sessionId);
            Log.e(TAG, "OpenSession: Failed to open install session.", e);
            return false;
        }
        sendStartedBroadcast(packageName, sessionId);
        return true;
    }

    private void cleanOldSessions() {
        for (PackageInstaller.SessionInfo sessionInfo : packageInstaller.getMySessions()) {
            try {
                packageInstaller.abandonSession(sessionInfo.getSessionId());
            } catch (Exception e) {
                Log.w(TAG, "CleanOldSessions: Unable to abandon session", e);
            }
        }
    }

    @Override
    boolean abandon() {
        if (session != null) session.close();
        return false;
    }
}
