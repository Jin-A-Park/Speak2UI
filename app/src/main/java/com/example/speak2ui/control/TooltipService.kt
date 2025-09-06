package com.example.speak2ui.control

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.example.speak2ui.R
import com.example.speak2ui.data.TooltipMap

/**
 * An [AccessibilityService] that automatically displays numbered tooltips (badges)
 * on specific UI elements.
 *
 * This service scans the screen for targetable UI elements (e.g., unlabeled icons, search bars)
 * and overlays a small, numbered view on top of them. It then broadcasts a map of these
 * numbers to the corresponding UI element details ([com.example.speak2ui.data.TooltipMap]) for other services, like
 * [AccessibilityService], to use.
 *
 * This allows the user to refer to UI elements by number in their voice commands (e.g., "tap 3").
 */
class TooltipService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private val overlays = mutableMapOf<String, View>()
    private val tooltipMap = mutableListOf<TooltipMap>()

    // 200ms debounce
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = Runnable { updateTooltips() }

    companion object {
        private const val TAG = "Tooltip"
    }

    /**
     * Called when the system successfully connects to this accessibility service.
     * Initializes the [WindowManager] and triggers the first tooltip update.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        updateTooltips()
    }

    /**
     * This method is called when the system wants to interrupt the feedback this service is providing.
     * Currently, it does nothing.
     */
    override fun onInterrupt() = Unit

    /**
     * Called when an [AccessibilityEvent] is received.
     *
     * Listens for `TYPE_WINDOW_CONTENT_CHANGED` events to refresh the tooltips.
     * A debounce mechanism is used to avoid excessive updates in rapid succession.
     *
     * @param event The received accessibility event.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            handler.removeCallbacks(updateRunnable)
            handler.postDelayed(updateRunnable, 200)
        }
    }

    /**
     * The core logic for updating the tooltips on the screen.
     *
     * This function performs the following steps:
     * 1. Removes all existing tooltips from the screen.
     * 2. Clears the internal [tooltipMap].
     * 3. Traverses the accessibility node tree of the active window using [dfs] to find target elements.
     * 4. For each found element, it creates a badge view and adds it to the screen.
     * 5. Broadcasts the updated list of [TooltipMap] objects to other components.
     */
    private fun updateTooltips() {
        val root = rootInActiveWindow ?: return

        overlays.values.forEach { view ->
            try {
                windowManager.removeView(view)
            } catch (_: IllegalArgumentException) { /* Ignore */
            }
        }
        overlays.clear()
        tooltipMap.clear()

        dfs(root)
        val toolMapList = ArrayList<TooltipMap>()
        tooltipMap.forEach { toolMap ->
            val badge = makeBadgeView(toolMap.number.toString())
            positionOverlay(badge, toolMap.bounds)
            overlays["${toolMap.number}"] = badge

            toolMapList.add(
                TooltipMap(
                    number = toolMap.number,
                    description = toolMap.description,
                    bounds = toolMap.bounds
                )
            )
        }

        val broadcastIntent = Intent("com.example.TOOLTIP").apply {
            setPackage(packageName)
            putParcelableArrayListExtra("tooltipMap", toolMapList)
        }
        sendBroadcast(broadcastIntent)
    }


    /**
     * Performs a Depth-First Search (DFS) traversal of the accessibility node tree to find
     * UI elements that should receive a tooltip.
     *
     * This is the core logic that determines which elements are important enough to be labeled.
     * The criteria for adding a tooltip are heuristic-based and target elements that are often
     * hard to refer to by voice, such as unlabeled icons or generic search bars.
     *
     * @param n The [AccessibilityNodeInfo] to start the traversal from.
     */
    private fun dfs(n: AccessibilityNodeInfo) {
        // Get the node's bounds and description.
        val bounds = Rect().also { n.getBoundsInScreen(it) }
        val desc = n.contentDescription?.toString().orEmpty()

        // Extract a token from the view ID resource name to help identify its purpose.
        val idTok = n.className?.toString()?.substringAfterLast("/")?.lowercase().orEmpty()
        // Ignore nodes that are likely just for layout purposes.
        val isNotLayoutId = !idTok.contains("Layout")

        // Only consider nodes that have a physical presence on the screen.
        if (bounds.width() > 0 && bounds.height() > 0) {
            // --- Heuristics for identifying target nodes ---
            val cls = n.className?.toString().orEmpty()
            val hasVisibleText = n.text?.toString()?.trim()?.isNotEmpty() == true
            // An "icon" is considered to be an ImageView, ImageButton, or a Button without any text.
            val isIconClass =
                cls.contains("ImageView") || cls.contains("ImageButton") ||
                        (cls.endsWith("Button") && !hasVisibleText)  // A button with no text is treated as an icon.
            val isEditText = cls.contains(".widget.EditText") || cls.endsWith("EditText")
            val hintLow = n.hintText?.toString()?.lowercase().orEmpty()
            // A node "looks like a search bar" if its ID, text, description, or hint contains search-related keywords.
            val looksSearchKeyword = sequenceOf(
                idTok,
                n.text?.toString()?.lowercase().orEmpty(),
                desc.lowercase(),
                hintLow
            ).any {
                it.contains("검색") || it.contains("search") || it.contains("magnifier") || it.contains(
                    "돋보기"
                )
            }

            when {
                // Condition 1: Add tooltip to clickable icons that don't have a visible text label.
                (isNotLayoutId && isIconClass && n.isClickable && !hasVisibleText) -> {
                    val label =
                        desc.ifEmpty { "(icon)" } // Use content description or a generic label.
                    tooltipMap.add(
                        TooltipMap(
                            number = tooltipMap.size + 1,
                            description = label,
                            bounds = bounds
                        )
                    )
                    /*
                    Log.d(
                        TAG,
                        "[ICON] " +
                                "tooltip=${tooltipMap.size}, class=${n.className}, id=${n.viewIdResourceName ?: ""}, " +
                                "text=${n.text ?: ""}, desc=${n.contentDescription ?: ""}, " +
                                "clickable=${n.isClickable}, editable=${n.isEditable}, focusable=${n.isFocusable}, " +
                                "enabled=${n.isEnabled}, labeledBy=${n.getLabeledBy()?.className ?: "null"}, " +
                                "bounds=($bounds), hintText=${n.hintText}"
                    )
                    */
                }

                // Condition 2: Add tooltip to search bars (either EditTexts or nodes that look like search bars).
                (isNotLayoutId && (isEditText || looksSearchKeyword)) -> {
                    val label =
                        if (desc.isNotEmpty()) desc
                        else if (hintLow.isNotEmpty()) (n.hintText.toString())
                        else "(search bar)" // Use description, hint text, or a generic label.

                    tooltipMap.add(
                        TooltipMap(
                            number = tooltipMap.size + 1,
                            description = label,
                            bounds = bounds
                        )
                    )
                    /*
                    Log.d(
                        TAG,
                        "[SEARCH] " +
                                "tooltip=${tooltipMap.size}, class=${n.className}, id=${n.viewIdResourceName ?: ""}, " +
                                "text=${n.text ?: ""}, desc=${n.contentDescription ?: ""}, " +
                                "clickable=${n.isClickable}, editable=${n.isEditable}, focusable=${n.isFocusable}, " +
                                "enabled=${n.isEnabled}, labeledBy=${n.getLabeledBy()?.className ?: "null"}, " +
                                "bounds=($bounds), hintText=${n.hintText}"
                    )
                    */
                }
            }
            /*
            Log.d(
                TAG,
                "class=${n.className}, id=${n.viewIdResourceName ?: ""}, " +
                        "text=${n.text ?: ""}, desc=${n.contentDescription ?: ""}, " +
                        "clickable=${n.isClickable}, editable=${n.isEditable}, " +
                        "labeledBy=${n.getLabeledBy()?.className ?: "null"}, " +
                        "bounds=($bounds), hintText=${n.hintText}"
            )
            */
        }

        // Recurse to all children of the current node.
        for (i in 0 until n.childCount) {
            n.getChild(i)?.let { dfs(it) }
        }
    }

    /**
     * Creates and inflates the tooltip badge view.
     *
     * @param label The text to display inside the badge (e.g., "1", "2").
     * @return The inflated [View] for the tooltip badge.
     */
    private fun makeBadgeView(label: String): View {
        val root = LayoutInflater.from(this)
            .inflate(R.layout.tooltip_view, null, false)
        val tv = root.findViewById<TextView>(R.id.tooltip)  // ← TextView id
        tv.text = label
        root.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        return root
    }

    /**
     * Positions the tooltip badge on the screen relative to its target UI element.
     *
     * It calculates the position to be near the center of the target element, with an offset,
     * and ensures the badge does not go off-screen.
     *
     * @param badge The tooltip [View] to position.
     * @param bounds The [Rect] of the target UI element.
     */
    private fun positionOverlay(badge: View, bounds: Rect) {
        val w = badge.measuredWidth
        val h = badge.measuredHeight

        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val centerX = (bounds.left + bounds.right) / 2
        val centerY = (bounds.top + bounds.bottom) / 2

        var x = centerX + (w / 2)
        var y = centerY - h

        x = x.coerceIn(0, screenW - w)
        y = y.coerceIn(0, screenH - h)

        val type = TYPE_ACCESSIBILITY_OVERLAY

        val params = WindowManager.LayoutParams(
            w, h, type,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        windowManager.addView(badge, params)
    }
}