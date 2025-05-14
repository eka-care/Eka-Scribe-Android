package com.eka.voice2rx_sdk.networking

interface IOkHttpSetup {


    /**
     * Get default headers which will be added to okhttp client.
     */
    fun getDefaultHeaders(url: String): Map<String, String>

    /**
     * Refreshes auth token if we get 401.
     * This should be blocking method else this will not work as intended.
     * @return Map of headers with new auth headers. If the request has failed, return null and we will retry 3 times before moving to logout state.
     */
    fun refreshAuthToken(url: String): Map<String, String>?

    /**
     * We call logout if we are not able to authenticate user multiple times and server is throwing 401.
     * This will only work for apis which have AuthorizationType as ACCESS_TOKEN which is default.
     */
    fun onSessionExpire()

    /**
     * This is the timeout for connection, read, write etc in seconds.
     * Default is 30 seconds.
     * If you want different timeouts for all, please contact the developer.
     */
    fun timeoutsInSeconds(): Long = 30
    fun writeTimeoutInSeconds(): Long = 5 * 60 * 60

}