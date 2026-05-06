package com.example.scankeyboard

object KeyboardSharedState {
    @Volatile
    var pendingText: String? = null
}
