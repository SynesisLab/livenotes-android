package com.sinesislab.livenotes.ai

/**
 * The prompt texts, kept in lockstep with the desktop app's
 * server/ai/prompts.js — same families, same contract, so notes produced on
 * the phone feel identical to notes produced on the desktop. If you change
 * wording here, change it there too (and vice versa).
 */
object Prompts {
    val SYSTEM =
        "You are a note-taking assistant inside a live meeting transcription app. You write clean, concise markdown. " +
            "Output only the requested content — no preamble, no explanations, no code fences."

    // ---------------------------------------------------------------- live notes
    // Incremental: existing bullets in, ONLY new bullets out — the notes grow
    // as the session progresses, exactly like the desktop's auto-notes job.
    fun autoNotesSystem(existing: String): String = buildString {
        append(
            "You are a note-taking assistant embedded in a live transcription app. You maintain a \"Live notes\" section that grows " +
                "incrementally as a session progresses. You receive the current notes (your previous bullets) and a chunk of NEW " +
                "transcript material. Output ONLY markdown bullet points covering genuinely NEW information (facts, decisions, " +
                "action items, open questions) that is not already captured anywhere in the notes. Never repeat, reword, or " +
                "reorganize existing notes. Never output headings, preamble, or explanations. If the new material adds nothing " +
                "of value, output nothing at all."
        )
    }

    fun autoNotesUser(existing: String, newMaterial: String): String = buildString {
        append("Live notes so far (your new bullets get appended to these):\n\n")
        append(existing.ifBlank { "(empty — this is the first update)" })
        append("\n\nNew transcript material:\n\n")
        append(newMaterial)
        append("\n\nNew bullet points only (or nothing):")
    }

    // -------------------------------------------------------- re-analysis
    // End-of-session comprehensive pass (the ✨ Re-analyze button): the whole
    // transcript, one call — headings allowed this time (unlike live notes).
    fun analysisSystem(profileCtx: String?): String = buildString {
        append(
            "You are a note-taking assistant embedded in a live transcription app. A session has ended and you are asked for a " +
                "COMPREHENSIVE RE-ANALYSIS of its complete transcript. Organize the result as markdown with exactly these " +
                "sections, omitting any section with nothing in it: \"Key points\", \"Decisions\", \"Action items\", \"Open questions\", " +
                "\"Highlights\". Use short bullet points; in Highlights, brief quotes with the speaker, when the transcript " +
                "context makes attribution clear. Be concise; never invent content; no preamble and no code fences."
        )
        if (!profileCtx.isNullOrBlank()) append("\n\nSession context:\n").append(profileCtx)
    }

    fun analysisUser(manualNotes: String?, transcript: String): String = buildString {
        append("Rest of the user's notes document (context — never contradict what is written here):\n\n")
        append(manualNotes?.trim()?.ifBlank { null } ?: "(nothing yet)")
        append("\n\nFull session transcript:\n\n")
        append(transcript)
        append("\n\nComprehensive re-analysis:")
    }
}