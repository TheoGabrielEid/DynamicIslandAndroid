package com.dynamicisland.app.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.widget.NestedScrollView

/** A wrap-content scroll view that caps tall overlay content without filling the unused space. */
class MaxHeightNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = maxHeightPx
        if (cap <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val parentMode = View.MeasureSpec.getMode(heightMeasureSpec)
        val parentSize = View.MeasureSpec.getSize(heightMeasureSpec)
        val heightLimit = if (parentMode == View.MeasureSpec.UNSPECIFIED) cap else minOf(cap, parentSize)
        val cappedHeightSpec = View.MeasureSpec.makeMeasureSpec(heightLimit, View.MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, cappedHeightSpec)
    }
}
