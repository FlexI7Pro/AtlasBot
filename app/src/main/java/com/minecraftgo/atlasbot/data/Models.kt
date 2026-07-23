package com.minecraftgo.atlasbot.data

data class BotConfig(
    val name: String = "",
    val ip: String = "",
    val port: String = "19132"
)

data class LogEntry(
    val timestamp: String,
    val message: String,
    val isError: Boolean = false
)

enum class ExecutionState {
    IDLE, STARTING, RUNNING, STOPPING, ERROR
}
