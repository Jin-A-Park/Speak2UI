package com.example.speak2ui.control

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.example.speak2ui.data.InteractiveNode
import com.example.speak2ui.data.ParsedCommand
import com.example.speak2ui.data.TooltipMap
import com.example.speak2ui.ui.overlay.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The main accessibility service that listens for user commands and controls the screen.
 *
 * This service is the core of the application's functionality. It performs three main tasks in a loop:
 * 1.  **Screen Reading**: It captures the current state of the UI, identifying all visible and
 *     interactive elements.
 * 2.  **Command Parsing**: It receives voice commands (as text) from the [SttService],
 *     sends them to the [CommandParser] along with the screen context to get a structured command.
 * 3.  **Action Execution**: It uses the [ActionExecutor] to perform the parsed command on the screen.
 *
 * It also listens for tooltip data from the [TooltipService] to help identify UI elements.
 */
class Accessibility : AccessibilityService() {

    private var commandActionReceiver: BroadcastReceiver? = null
    private var tooltipMapReceiver: BroadcastReceiver? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screenReader = ScreenReader()
    private lateinit var commandParser: CommandParser

    private var visibleNodes = mutableListOf<AccessibilityNodeInfo>()
    private val clickableNodes = mutableListOf<AccessibilityNodeInfo>()
    private val availableApps = mutableListOf<AccessibilityNodeInfo>()
    private var tooltipMap = mutableListOf<TooltipMap>()

    companion object {
        private const val TAG = "A11y"
    }

    override fun onCreate() {
        super.onCreate()
        commandParser = CommandParser()
    }

    /**
     * Called when the system connects to the service.
     *
     * Sets up broadcast receivers to listen for:
     * - Commands from the [SttService] (`com.example.COMMAND_ACTION`).
     * - Tooltip map updates from the [TooltipService] (`com.example.TOOLTIP_MAP`).
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()

        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, OverlayService::class.java))
        } else {
            Log.w(TAG, "OverlayService permission missing")
        }

        // Receiver for commands from the STT service
        commandActionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val command = intent.getStringExtra("command") ?: return
                handleCommand(command)
            }
        }
        val commandFilter = IntentFilter("com.example.COMMAND_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandActionReceiver, commandFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandActionReceiver, commandFilter)
        }

        // Receiver for the map of numbered tooltips from the TooltipService
        tooltipMapReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != "com.example.TOOLTIP") return
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra("tooltipMap", TooltipMap::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra("tooltipMap")
                }
                tooltipMap = list?.toMutableList() ?: mutableListOf()
            }
        }
        val tooltipFilter = IntentFilter("com.example.TOOLTIP")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                tooltipMapReceiver,
                tooltipFilter,
                null,
                Handler(Looper.getMainLooper()),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(tooltipMapReceiver, tooltipFilter)
        }
    }

    /**
     * The main workflow for handling a single voice command.
     *
     * This function is launched in a coroutine and performs the entire sequence of reading the screen,
     * parsing the command, and executing the resulting action.
     *
     * @param command The raw text command received from the STT service.
     */
    private fun handleCommand(command: String) {
        serviceScope.launch {
            // 1. Read screen to get current UI context
            visibleNodes.clear()
            clickableNodes.clear()
            availableApps.clear()

            rootInActiveWindow?.let { root ->
                screenReader.collectVisibleNodes(root, visibleNodes)
            }
            clickableNodes.addAll(visibleNodes.filter { it.isClickable })

            val activePackage = rootInActiveWindow?.packageName?.toString()
            availableApps.addAll(
                clickableNodes.filter { node ->
                    val nodePackage = node.packageName?.toString()
                    node.window?.type == AccessibilityWindowInfo.TYPE_APPLICATION && nodePackage == activePackage
                }
            )

            // 2. Parse the command using the screen context
            val allInteractiveNodes = getInteractiveNodes()
            val parseStartMs = SystemClock.elapsedRealtime()
            val parsed: ParsedCommand = commandParser.parseCommand(command, allInteractiveNodes)
            Log.d(TAG, "Parsed: ${parsed.intent} ${parsed.value} (${SystemClock.elapsedRealtime() - parseStartMs} ms)")

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    applicationContext,
                    "${parsed.intent} (${parsed.value})",
                    Toast.LENGTH_SHORT
                ).show()
            }

            // 3. Execute the parsed action
            withContext(Dispatchers.Main) {
                val actionStartMs = SystemClock.elapsedRealtime()
                val executor = ActionExecutor(
                    this@Accessibility,
                    visibleNodes,
                    clickableNodes,
                    availableApps,
                    tooltipMap
                )
                executor.handleParsedCommand(parsed)
                Log.d(
                    TAG,
                    "🕒 Command completed (${SystemClock.elapsedRealtime() - actionStartMs} ms)"
                )
            }
        }
    }

    /**
     * Aggregates all available clickable elements into a single list of [InteractiveNode]s.
     *
     * This list is passed to the [CommandParser] to provide context about what is currently
     * interactive on the screen. It includes elements identified by text from apps, general
     * clickable nodes, and numbered tooltips.
     *
     * @return A consolidated list of [InteractiveNode]s.
     */
    private fun getInteractiveNodes(): List<InteractiveNode> {
        val allInteractiveNodes = mutableListOf<InteractiveNode>()

        val availableList = availableApps.mapNotNull { it.text?.toString()?.trim() }
            .filter { it.isNotEmpty() }
            .map { InteractiveNode(it, true) }
        allInteractiveNodes.addAll(availableList)

        val clickableList = clickableNodes.mapNotNull { it.text?.toString()?.trim() }
            .filter { it.isNotEmpty() }
            .map { InteractiveNode(it, false) }
        allInteractiveNodes.addAll(clickableList)

        val tooltipList = tooltipMap.map { it.number.toString() }
            .filter { it.isNotEmpty() }
            .map { InteractiveNode(it, false) }
        allInteractiveNodes.addAll(tooltipList)

        return allInteractiveNodes
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /**
     * Called when the service is being destroyed.
     *
     * Cleans up resources by canceling the coroutine scope and unregistering broadcast receivers.
     */
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        runCatching { unregisterReceiver(commandActionReceiver) }
        runCatching { unregisterReceiver(tooltipMapReceiver) }
    }
}