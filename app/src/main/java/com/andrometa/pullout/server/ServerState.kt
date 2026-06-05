package com.andrometa.pullout.server

sealed class ServerState {
    object Cold : ServerState()
    data class Warming(val attempt: Int) : ServerState()
    object Ready : ServerState()
    data class Error(val message: String) : ServerState()
}
