package com.example.data

import kotlinx.serialization.Serializable

@Serializable data class ErrorResponse(val msg: String)

@Serializable data class Success(val sessionId: String)
