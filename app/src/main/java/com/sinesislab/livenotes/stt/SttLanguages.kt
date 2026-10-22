package com.sinesislab.livenotes.stt

/**
 * Session-language codes → what each engine actually accepts. "zh-Hant"
 * (Traditional Chinese) is a UI-level choice, not an engine language: both
 * whisper.cpp and OpenAI-style /audio/transcriptions speak plain "zh", and
 * the traditional SCRIPT comes out of a conditioning prompt (whisper follows
 * the prompt's script — the standard trick — and cloud providers honor the
 * prompt field the same way). Each engine maps its own params from the raw
 * session code, so callers pass one string and move on.
 */
object SttLanguages {
    /** The code the ENGINE wants; everything else passes through untouched. */
    fun engineLanguage(sessionLanguage: String): String = when (sessionLanguage) {
        "zh-Hant" -> "zh"
        else -> sessionLanguage
    }

    /** The conditioning prompt that anchors the output's script; empty means
     *  send nothing. */
    fun promptFor(sessionLanguage: String): String = when (sessionLanguage) {
        "zh-Hant" -> "以下是中文會議的逐字稿，以繁體中文書寫。"
        else -> ""
    }
}