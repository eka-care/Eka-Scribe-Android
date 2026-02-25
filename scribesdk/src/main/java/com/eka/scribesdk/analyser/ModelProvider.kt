package com.eka.scribesdk.analyser

interface ModelProvider {
    fun load()
    fun isLoaded(): Boolean
    fun unload()
}
