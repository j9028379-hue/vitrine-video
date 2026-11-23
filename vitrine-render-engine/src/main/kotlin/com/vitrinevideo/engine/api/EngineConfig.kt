package com.vitrinevideo.engine.api

data class EngineConfig(
    val minFreeStorageBytes: Long = 150L * 1024L * 1024L,
    val lowRamThresholdMb: Int = 2048,
    val maxSimultaneousDecoders: Int = 2,
    val preferHardwareEncoder: Boolean = true,
    val preferBaselineH264Profile: Boolean = true,
    val allowSoftwareFallback: Boolean = true,
    val audioSampleRateHz: Int = 48_000,
    val audioChannels: Int = 2,
    val audioBitrateBps: Int = 192_000,
    val videoBitrateBps: Int = 4_000_000,
    val durationToleranceSec: Double = 0.25,
    val cleanTempOnStart: Boolean = true
)
