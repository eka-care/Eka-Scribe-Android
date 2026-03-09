package com.eka.scribesdk.api.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class TemplateItem(
    @SerializedName("default")
    val default: Boolean,
    @SerializedName("desc")
    val desc: String?,
    @SerializedName("id")
    val id: String,
    @SerializedName("isFavorite")
    val isFavorite: Boolean,
    @SerializedName("sectionIds")
    val sectionIds: List<String>,
    @SerializedName("title")
    val title: String
)
