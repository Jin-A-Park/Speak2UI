package com.example.speak2ui.data

/**
 * Represents an interactive element on the screen, simplified for the command parser.
 *
 * This data class is used to create a list of all interactive items, which is then sent to
 * the AI model to provide context for the user's command.
 *
 * @property text The visible text or label of the node (e.g., "Settings", "Send", "3").
 * @property isApp `true` if this node represents an application that can be launched, `false` otherwise.
 */
data class InteractiveNode(
    val text: String,
    val isApp:Boolean
)