package com.sinesislab.livenotes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sinesislab.livenotes.ui.RootScaffold
import com.sinesislab.livenotes.ui.PermissionScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NotesApp).container
        setContent {
            val granted = remember {
                mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted.value = ok }
            // notifications (33+ runtime): without it the recording's live
            // notification is silently dropped — ask the moment mic lands
            val notifGranted = remember {
                mutableStateOf(
                    Build.VERSION.SDK_INT < 33 ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                )
            }
            val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> notifGranted.value = ok }
            LaunchedEffect(granted.value) {
                if (granted.value && !notifGranted.value) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!granted.value) {
                PermissionScreen(onRequest = { launcher.launch(Manifest.permission.RECORD_AUDIO) })
            } else {
                RootScaffold(container)
            }
        }
    }
}