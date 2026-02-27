package com.eka.scribesdk.api.models

import androidx.annotation.Keep

@Keep
data class TemplateItem(
    val default: Boolean,
    val desc: String?,
    val id: String,
    val isFavorite: Boolean,
    val sectionIds: List<String>,
    val title: String
)
