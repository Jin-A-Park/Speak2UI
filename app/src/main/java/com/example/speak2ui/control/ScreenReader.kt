package com.example.speak2ui.control

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * A utility class for reading the screen content through the accessibility framework.
 *
 * Its primary function is to traverse the accessibility node tree and collect all nodes
 * that are currently visible to the user.
 */
class ScreenReader {
    /**
     * Recursively traverses the accessibility node tree starting from the given [node]
     * and collects all visible nodes into the [visibleList].
     *
     * A node is considered "visible" if it meets one of two criteria:
     * 1. The `isVisibleToUser` property is `true`.
     * 2. Its bounds on the screen intersect with the actual screen dimensions. This is a
     *    fallback for nodes that might be technically off-screen but still partially visible
     *    or reported incorrectly by the system.
     *
     * @param node The starting [AccessibilityNodeInfo] for the traversal (usually the root).
     * @param visibleList The mutable list to which all found visible nodes will be added.
     */
    fun collectVisibleNodes(
        node: AccessibilityNodeInfo,
        visibleList: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.isVisibleToUser) {
            visibleList.add(node)
        } else {
            // Fallback check for nodes that are not technically "visible" but are on screen.
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val displayMetrics = Resources.getSystem().displayMetrics
            val screenRect = Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
            if (Rect.intersects(screenRect, bounds)) {
                visibleList.add(node)
            }
        }
        // Recurse through all children.
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectVisibleNodes(child, visibleList)
            }
        }
    }
}