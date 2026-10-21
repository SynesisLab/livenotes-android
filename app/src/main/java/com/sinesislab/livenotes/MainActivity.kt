package com.sinesislab.livenotes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.sinesislab.livenotes.ui.RootScaffold

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
            if (!granted.value) {
                // First paint is a permission screen: capture is the whole app, so ask up front.
                com.sinesislab.livenotes.ui.PermissionScreen(onRequest = { launcher.launch(Manifest.permission.RECORD_AUDIO) })
            } else {
                RootScaffold(container)
            }
        }
    }
}