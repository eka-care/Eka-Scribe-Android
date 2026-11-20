package com.eka.scribesdk.api

import com.eka.scribesdk.BuildConfig

/**
 * Which EkaScribe backend the SDK talks to.
 *
 * Use [DEV] when the host app is signed in against the dev auth server (e.g. staging builds),
 * otherwise the prod scribe APIs reject the dev token with 403.
 */
enum class ScribeEnvironment(
    internal val developerUrl: String,
    internal val cogUrl: String,
) {
    PROD(BuildConfig.DEVELOPER_URL, BuildConfig.COG_URL),
    DEV(BuildConfig.DEVELOPER_URL_DEV, BuildConfig.COG_URL_DEV),
}
