package com.sinesislab.livenotes

import android.app.Application
import com.sinesislab.livenotes.capture.CaptureBus
import com.sinesislab.livenotes.stt.WhisperEngine
import com.sinesislab.livenotes.store.AppSettings
import com.sinesislab.livenotes.store.SessionStore

/** Single wiring point: everything the screens need, built once. */
class NotesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(
            settings = AppSettings(this),
            sessions = SessionStore(this),
            whisper = WhisperEngine(this),
            bus = CaptureBus,
        )
    }
}

data class AppContainer(
    val settings: AppSettings,
    val sessions: SessionStore,
    val whisper: WhisperEngine,
    val bus: CaptureBus,
)