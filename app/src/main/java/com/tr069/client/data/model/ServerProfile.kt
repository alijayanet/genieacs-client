package com.tr069.client.data.model

data class ServerProfile(
    val id: String,
    val name: String,
    val url: String,
    val username: String = "",
    val password: String = "",
    val isEnabled: Boolean = true
)
