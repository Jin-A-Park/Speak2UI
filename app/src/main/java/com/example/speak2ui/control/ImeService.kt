package com.example.speak2ui.control

import android.inputmethodservice.InputMethodService
import android.view.inputmethod.InputConnection
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent

/**
 * A custom [android.inputmethodservice.InputMethodService] to programmatically handle text input
 * and IME (Input Method Editor) actions.
 *
 * This service receives broadcast commands to perform specific input actions, such as committing text,
 * sending an "Enter" key event, or triggering editor actions like "Search" or "Go".
 * It is used by the [Accessibility] to reliably input text and submit forms in various
 * applications, especially in web views where standard accessibility actions might be unreliable.
 *
 * The service can queue commands if it's not the active IME or if the input connection is not yet
 * available, and it will execute them once it becomes active for the target application.
 */
class ImeService : InputMethodService() {

    private var pendingDo: String? = null
    private var pendingText: String? = null
    private var pendingTargetPackage: String? = null

    companion object {
        private const val TAG = "IMEService"
    }

    /**
     * Tries to perform a sequence of IME actions until one succeeds.
     * @param connection The current [InputConnection].
     * @param actions A variable number of action IDs (e.g., [EditorInfo.IME_ACTION_GO]) to attempt.
     * @return `true` if any action was successful, `false` otherwise.
     */
    private fun tryActions(connection: InputConnection, vararg actions: Int): Boolean {
        for (action in actions) if (connection.performEditorAction(action)) return true
        return false
    }

    /**
     * Finishes the composing text on the input connection.
     * This is important for stability in web views like Chrome.
     * @param connection The current [InputConnection].
     */
    private fun finishCompose(connection: InputConnection) {
        // Finalizes the composing region to prevent issues in web views.
        connection.finishComposingText()
    }

    /**
     * Sends an "Enter" key event to the input connection.
     * It also tries committing a newline character first, as some web views respond better to it.
     * @param connection The current [InputConnection].
     */
    private fun sendEnter(connection: InputConnection) {
        finishCompose(connection)
        // Some web views handle a newline commit better.
        connection.commitText("\n", 1)
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_ENTER))
    }

    /**
     * Commits the given text and then attempts to perform a "Go" or equivalent submit action.
     * This replaces any existing text in the field.
     * @param connection The current [InputConnection].
     * @param text The text to commit.
     */
    private fun commitAndGo(connection: InputConnection, text: String) {
        // Replace the entire content of the editor.
        connection.deleteSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE)
        if (text.isNotBlank()) connection.commitText(text, 1)
        finishCompose(connection)

        // Try a sequence of common submit actions, falling back to a simple Enter.
        val ok = tryActions(
            connection,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_SEND
        )
        if (!ok) sendEnter(connection)
    }

    /**
     * Attempts to perform a "Search" or equivalent submit action.
     * @param connection The current [InputConnection].
     */
    private fun searchOrEnter(connection: InputConnection) {
        finishCompose(connection)
        val ok = tryActions(
            connection,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_SEND
        )
        if (!ok) sendEnter(connection)
    }

    /**
     * Checks if the current IME session is for the intended target application.
     * @param targetPacakge The package name of the target application.
     * @return `true` if this IME can execute a command now for the target, `false` otherwise.
     */
    private fun isAbleToRun(targetPacakge: String?): Boolean {
        currentInputConnection ?: return false
        val editorInfo = currentInputEditorInfo
        if (targetPacakge.isNullOrEmpty()) return true
        return editorInfo?.packageName == targetPacakge
    }

    /**
     * BroadcastReceiver to handle incoming commands from the [ActionExecutor].
     */
    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val what = intent?.getStringExtra("do") ?: return
            val text = intent.getStringExtra("text").orEmpty()
            val targetPkg = intent.getStringExtra("targetPkg") // Target app package from accessibility service

            // If this IME is not active for the correct target app, queue the command.
            val connection = currentInputConnection?.takeIf { isAbleToRun(targetPkg) } ?: run {
                pendingDo = what
                pendingText = text
                pendingTargetPackage = targetPkg
                return
            }

            // Execute the command immediately.
            when (what) {
                "COMMIT_AND_GO"   -> commitAndGo(connection, text)
                "SEARCH_OR_ENTER" -> searchOrEnter(connection)
                "ENTER"           -> sendEnter(connection)
                "SEARCH"          -> {
                    finishCompose(connection)
                    connection.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
                }
            }
        }
    }

    /**
     * Called when a new input session starts.
     * It checks for and executes any pending (queued) commands if the new session matches the target.
     */
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val connection = currentInputConnection ?: return

        if (pendingDo != null) {
            // If there is a pending command, check if the new input target matches.
            val isTargetMatch = pendingTargetPackage.isNullOrEmpty() || (attribute?.packageName == pendingTargetPackage)
            if (isTargetMatch) {
                // If it matches, execute the pending command.
                when (pendingDo) {
                    "COMMIT_AND_GO"   -> commitAndGo(connection, pendingText.orEmpty())
                    "SEARCH_OR_ENTER" -> searchOrEnter(connection)
                    "ENTER"           -> sendEnter(connection)
                    "SEARCH"          -> {
                        finishCompose(connection)
                        connection.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
                    }
                }
                // Clear the pending command.
                pendingDo = null; pendingText = null; pendingTargetPackage = null
            }
        }
    }

    /**
     * Registers the command broadcast receiver when the service is created.
     */
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        registerReceiver(receiver, android.content.IntentFilter("com.example.speak2ui.IME_CMD"))
    }

    /**
     * Unregisters the command broadcast receiver when the service is destroyed.
     */
    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }
}
