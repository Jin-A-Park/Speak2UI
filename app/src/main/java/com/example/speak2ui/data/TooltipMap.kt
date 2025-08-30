package com.example.speak2ui.data

import android.graphics.Rect
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A data class that represents a mapping between a numerical tooltip and a UI element.
 *
 * This class is used to share information about UI elements that have been assigned a tooltip
 * between the [com.example.speak2ui.control.TooltipService] (which creates the tooltips) and the [com.example.speak2ui.control.Accessibility]
 * (which uses the tooltips to identify targets for actions).
 *
 * It is [android.os.Parcelable] to allow it to be easily passed between components via [Intent] extras.
 *
 * @property number The integer number displayed in the tooltip badge.
 * @property description A description of the UI element, typically from its `contentDescription` or text.
 * @property bounds The screen coordinates [android.graphics.Rect] of the UI element.
 */
@Parcelize
data class TooltipMap(
    var number: Int,
    var description: String,
    var bounds: Rect
) : Parcelable