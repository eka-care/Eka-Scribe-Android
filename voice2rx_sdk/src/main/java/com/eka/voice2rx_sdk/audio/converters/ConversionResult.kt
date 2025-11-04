package com.eka.voice2rx_sdk.audio.converters

import androidx.annotation.Keep

class ConversionResult {
    val convertCode: ConversionCode
    var errorMessage: String? = null

    constructor() {
        convertCode = ConversionCode.SUCCESS
    }

    constructor(errorMessage: String?) {
        convertCode = ConversionCode.FAILED

        this.errorMessage = errorMessage
    }
    @Keep
    enum class ConversionCode {
        SUCCESS,
        FAILED
    }
}