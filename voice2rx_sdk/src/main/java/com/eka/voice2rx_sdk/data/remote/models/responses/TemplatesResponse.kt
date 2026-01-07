package com.eka.voice2rx_sdk.data.remote.models.responses


import androidx.annotation.Keep
import com.eka.voice2rx_sdk.sdkinit.models.TemplateItem
import com.google.gson.annotations.SerializedName

@Keep
data class TemplatesResponse(
    @SerializedName("items")
    var items: List<Item?>?
) {
    @Keep
    data class Item(
        @SerializedName("default")
        var default: Boolean?,
        @SerializedName("desc")
        var desc: String?,
        @SerializedName("id")
        var id: String?,
        @SerializedName("is_favorite")
        var isFavorite: Boolean?,
        @SerializedName("section_ids")
        var sectionIds: List<String?>?,
        @SerializedName("title")
        var title: String?
    )
}

fun TemplatesResponse.Item.toTemplateItem(): TemplateItem? {
    if (id == null) {
        return null
    }
    return TemplateItem(
        id = id ?: "",
        title = title ?: "",
        desc = desc ?: "",
        default = default ?: false,
        isFavorite = isFavorite ?: false,
        sectionIds = sectionIds?.filterNotNull() ?: emptyList(),
    )
}