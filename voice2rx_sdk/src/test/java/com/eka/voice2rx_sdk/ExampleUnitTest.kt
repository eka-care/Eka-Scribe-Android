package com.eka.voice2rx_sdk

import com.eka.voice2rx_sdk.common.Voice2RxInternalUtils
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        print(
            Voice2RxInternalUtils.getUserTokenData(
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOiJhbmRyb2lkZG9jIiwiYi1pZCI6Ijc3MjcyMzE5MTkzNjQzIiwiY2MiOnt9LCJleHAiOjE3NDY3MDMwNDIsImZuIjoiRGl2eWVzaCIsImdlbiI6Ik0iLCJpYXQiOjE3NDY2OTk0NDIsImlkcCI6Im1vYiIsImlzcyI6ImVtci5la2EuY2FyZSIsImxuIjoiSml2YW5pIiwib2lkIjoiMTczODMzMTg4OTgyNTk1IiwicHJpIjp0cnVlLCJ1dWlkIjoiZmIwMTFiMTQtNGMzMC00Y2M3LTkwOTMtZmNlZDgwZWY0OGZjIn0.0N14t1GfdHfziTVTPr--EUrxRCZvU0NDOzDr1tOLvpo"
            )
        )
    }
}