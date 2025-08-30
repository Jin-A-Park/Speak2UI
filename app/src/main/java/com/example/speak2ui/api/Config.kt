package com.example.speak2ui.api

import com.example.speak2ui.BuildConfig

/**
 * The API key for accessing the OpenAI API.
 *
 * **Security Note:** This key is loaded from `BuildConfig.OPENAI_API_KEY`.
 * Ensure that the key is stored securely in your `local.properties` file and
 * not checked into version control.
 *
 * Example `local.properties` entry:
 * `OPENAI_API_KEY="YOUR_API_KEY_HERE"`
 */
val OPENAI_API_KEY: String = BuildConfig.OPENAI_API_KEY

/**
 * The name of the GPT model used for parsing user commands and understanding intent.
 * Currently set to `gpt-4o-mini`.
 */
val PARSER_MODEL_NAME: String = "gpt-4o-mini"

/**
 * The name of the GPT model used for Speech-to-Text (STT) transcription.
 * Currently set to `gpt-4o-mini-transcribe`.
 */
val STT_MODEL_NAME: String = "gpt-4o-mini-transcribe"
