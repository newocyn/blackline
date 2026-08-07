package online.pcguys.opsec

import android.view.View
import android.view.ViewGroup

@Suppress("UNCHECKED_CAST")
fun <T : View> View.findViewWithTag(tag: String): T? {
    if (this.tag == tag) return this as? T
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            val found = getChildAt(i).findViewWithTag<T>(tag)
            if (found != null) return found
        }
    }
    return null
}
