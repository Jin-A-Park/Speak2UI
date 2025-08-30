package com.example.speak2ui.control

import android.util.Log
import com.example.speak2ui.api.OPENAI_API_KEY
import com.example.speak2ui.api.PARSER_MODEL_NAME
import com.example.speak2ui.data.InteractiveNode
import com.example.speak2ui.data.ParsedCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses user's natural language input into a structured [ParsedCommand] using an AI model.
 *
 * This class is responsible for taking the user's voice command as a string, along with the
 * current context of the screen (a list of clickable nodes), and sending it to the OpenAI API.
 * The API, guided by a detailed system prompt, returns a JSON object representing the user's
 * intent, which is then parsed into a [ParsedCommand] data class.
 */
class CommandParser {

    private val client = OkHttpClient()
    private val openaiModel = PARSER_MODEL_NAME

    companion object {
        private const val TAG = "CommandParser"
    }

    /**
     * Asynchronously parses the user's command by making a network request to the OpenAI API.
     *
     * @param userInput The natural language command from the user.
     * @param allInteractiveNode A list of [InteractiveNode] objects representing the interactive
     *                          elements currently on the screen. This provides context to the AI.
     * @return A [ParsedCommand] object representing the structured command. Returns a command with
     *         intent "error" if the API call fails or the response is malformed.
     */
    suspend fun parseCommand(userInput: String, allInteractiveNode: List<InteractiveNode>): ParsedCommand {
        return withContext(Dispatchers.IO) {
            try {
                val promptText = getPrompt(userInput, allInteractiveNode)
                val requestBody = createRequestBody(promptText)
                val request = Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $OPENAI_API_KEY")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    parseResponse(responseBody)
                } else {
                    Log.e(TAG, "API Error: ${response.code} - $responseBody")
                    ParsedCommand("error", emptyList())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception: ${e.message}")
                ParsedCommand("error", emptyList())
            }
        }
    }

    /**
     * Constructs the detailed prompt to be sent to the AI model.
     *
     * This prompt includes the available actions, constraints, the list of clickable nodes
     * on the screen, the required JSON output format, and the actual user input.
     *
     * @param userInput The user's raw text command.
     * @param allInteractiveNode The list of clickable nodes to include as context.
     * @return A formatted string that serves as the full prompt for the AI.
     */
    private fun getPrompt(userInput: String, allInteractiveNode: List<InteractiveNode>): String {
        return """Analyse the user input and return a single JSON object with the appropriate Android action.

## Available Actions (select exactly one):

### UI Interaction Actions:
- **PRESS**: Click a clickable UI element (must be the relatively similar and node's text from allClickableNodes. it can be more than one word)
- **DOUBLE_PRESS**: Double-tap a clickable UI element (must be the most similar node from allClickableNodes. it can be more than one word)  
- **LONG_PRESS**: Long-press/hold a clickable UI element (must be the most similar node from allClickableNodes. it can be more than one word)

### Input Action:
- **ENTER**: Type/write text using keyboard

### Navigation Actions:
- **SWIPE**: Perform swipe gesture (UP/DOWN/LEFT/RIGHT only)
- **HOME**: Press Android Home button
- **BACK**: Press Android Back button
- **OVERVIEW_BUTTON**: Press Android recent apps/overview button

### App Launch:
- **OPEN**: Launch an app, open a URL, or open a specific component/page inside an app  
  - `value` can either be:
    - One component from a sentence (e.g., open gallery app)
    - Two components extracted from a sentence (e.g., open camera in gallery app)
  - check allClickableNode list to find similar app or UI component

### Fallback:
- **NONE**: Input is unclear, not actionable, or lacks required information

## Constraints:
- **PRESS/DOUBLE_PRESS/LONG_PRESS**: `value` must be the most similar node from allClickableNodes
- **OPEN**:
  - If launching an app/URL → `value` is a single string from allClickableNodes(isApp = true)
  - If opening a specific page/component → `value` is a list of exactly two strings in the correct order: 
    1. Component identifier (e.g., "component:mobile_network")
    2. App identifier (e.g., "app:settings")
  - check allClickableNode list to find similar app or UI component (apps are value with isApp=true)
- **SWIPE**: `value` must be exactly one of: UP, DOWN, LEFT, RIGHT
- **ENTER**: `value` contains the text to type (string only)
- **HOME/BACK/OVERVIEW_BUTTON/NONE**: `value` is empty string
- **Only the OPEN action can return a list as value. All other actions must return a single string or empty string.**

## clickable_nodes(search by text, check isApp value to know if it's an app):
$allInteractiveNode

## Response Format:
Return ONLY a JSON object with this exact structure:
- If single value(All cases including OPEN): {{"intent": "<ACTION_NAME>", "value": "<REQUIRED_VALUE>"}}
- If list value (OPEN with two values only): {{"intent": "OPEN", "value": ["<COMPONENT_ID>", "<APP_ID>"]}}

## Analysis Guidelines:
- Use your comprehensive language understanding to interpret user intent
- Recognise synonyms, translations, and common alternative names across languages
- Match conceptually similar terms (e.g., "메일" conceptually matches "Gmail")
- Handle mixed language inputs and colloquial expressions
- Consider semantic meaning, not just literal text matching
- Only return a VALUE list with two components when:
  - intent is OPEN
  - both a specific component/page AND the app are clearly identified
  - Ensure list order correctness for a two value case of OPEN: component first, then app

## User Input: "$userInput"
"""
    }

    /**
     * Creates the JSON request body for the OpenAI Chat Completions API call.
     *
     * @param promptText The fully constructed prompt for the user message.
     * @return An OkHttp [RequestBody] object containing the JSON payload.
     */
    private fun createRequestBody(promptText: String): RequestBody {
        val systemMessage = JSONObject().apply {
            put("role", "system")
            put(
                "content",
                "You are an intelligent assistant designed to understand user input and convert it into a structured command. " +
                "Your task is to determine the most appropriate action the user wants to take on a user interface."
            )
        }

        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", promptText)
        }

        val messages = JSONArray().apply {
            put(systemMessage)
            put(userMessage)
        }

        val requestJson = JSONObject().apply {
            put("model", openaiModel)
            put("messages", messages)
            put("response_format", JSONObject().put("type", "json_object"))
            put("temperature", 0)
        }

        return requestJson.toString().toRequestBody("application/json".toMediaType())
    }

    /**
     * Parses the JSON string response from the AI model into a [ParsedCommand] object.
     *
     * @param responseBody The raw JSON string from the API response.
     * @return A [ParsedCommand] object. If parsing fails, returns a command with intent "ERROR".
     */
    private fun parseResponse(responseBody: String): ParsedCommand {
        return try {
            val jsonResponse = JSONObject(responseBody)
            val choices = jsonResponse.getJSONArray("choices")
            val messageContent = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val resultJson = JSONObject(messageContent)
            val intent = resultJson.optString("intent", "NONE").uppercase()
            val valueAny = resultJson.opt("value")

            // The 'value' can be a single string or a JSON array of strings.
            // This when-block handles both cases and converts them to a unified List<String>.
            val valueList: List<String> = when (valueAny) {
                is JSONArray -> (0 until valueAny.length()).map { i -> valueAny.getString(i) }
                is String -> listOf(valueAny)
                else -> emptyList()
            }
            ParsedCommand(intent, valueList)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: ${e.message}")
            ParsedCommand("ERROR", emptyList())
        }
    }
}