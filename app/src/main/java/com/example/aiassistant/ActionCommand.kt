package com.example.aiassistant

enum class ActionType {
    CLICK,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    SWIPE,
    TYPE
}

data class ActionCommand(
    val type: ActionType,
    val target: String
)
