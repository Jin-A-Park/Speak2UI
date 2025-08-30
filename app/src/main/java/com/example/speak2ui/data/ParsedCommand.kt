package com.example.speak2ui.data

/**
 * Represents a structured command parsed from the user's natural language input.
 *
 * This is the output of the [com.example.speak2ui.control.CommandParser] and the input for the
 * [com.example.speak2ui.control.ActionExecutor].
 *
 * @property intent The primary action the user wants to perform (e.g., "PRESS", "SWIPE", "ENTER").
 * @property value A list of arguments for the intent. For example, for "PRESS", it might contain
 *                 the label of the button to press. For "ENTER", it contains the text to type.
 */
data class ParsedCommand(
    val intent: String,
    val value: List<String> = emptyList()
)