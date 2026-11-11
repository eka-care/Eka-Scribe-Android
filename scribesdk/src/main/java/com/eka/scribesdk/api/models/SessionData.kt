package com.eka.scribesdk.api.models

import androidx.annotation.Keep

@Keep
data class SessionData(
    val templateId: String,
    val documentId: String,
    /** Base64 document text. The website and Get Session read this back as the note. */
    val data: String,
    /** TipTap document, the copy the website draws. Null leaves that copy unchanged. */
    val tiptapJson: String? = null,
)
