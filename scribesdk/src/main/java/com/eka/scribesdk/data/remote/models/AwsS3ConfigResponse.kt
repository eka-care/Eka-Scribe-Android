package com.eka.scribesdk.data.remote.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class AwsS3ConfigResponse(
    @SerializedName("credentials")
    val credentials: Credentials?,
    @SerializedName("expiry")
    val expiry: Int?,
    @SerializedName("identity_id")
    val identityId: String?,
    @SerializedName("token")
    val token: String?
)

@Keep
data class Credentials(
    @SerializedName("AccessKeyId")
    val accessKeyId: String?,
    @SerializedName("Expiration")
    val expiration: String?,
    @SerializedName("SecretKey")
    val secretKey: String?,
    @SerializedName("SessionToken")
    val sessionToken: String?
)
