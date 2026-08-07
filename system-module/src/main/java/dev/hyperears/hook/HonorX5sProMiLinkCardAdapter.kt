package dev.hyperears.hook

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.HonorX5sProAdapter
import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.NoiseMode
import java.lang.ref.WeakReference

/**
 * Adds Honor X5s Pro ANC depth cycling to MiLink's stock three-state ANC card.
 *
 * Depth levels (deep/smart/light/medium) are not physical MiLink modes; the card exposes a
 * tappable depth label beside the ANC title. Each tap cycles the vendor depth order and sends
 * the adapter's WIND trigger, which encodes an ANC command with the freshly selected depth.
 * The label keeps its own index because [EarbudState] carries no depth projection.
 */
internal object HonorX5sProMiLinkCardAdapter : MiLinkCardAdapter {
    override val presentationId: MiLinkCardPresentationId = HonorX5sProAdapter.PRESENTATION_ID

    override fun bind(
        root: View,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkCardBinding? {
        val title = root.findMiLinkView(ANC_CARD_TITLE_ID) as? TextView ?: return null
        val ancCard = root.findMiLinkView(ANC_CARD_ID) ?: return null
        val parent = title.parent as? ViewGroup ?: return null
        val index = parent.indexOfChild(title).takeIf { it >= 0 } ?: return null
        val originalParams = title.layoutParams
        val originalWidth = originalParams.width

        parent.removeViewAt(index)
        val wrapper = FrameLayout(root.context).apply {
            layoutParams = originalParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        title.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        wrapper.addView(title)

        val depthLabel = TextView(root.context).apply {
            text = DEPTH_NAMES[0]
            setTextColor(title.currentTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, title.textSize)
            typeface = title.typeface
            setPadding(0, 0, root.context.dp(END_PADDING_DP), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        wrapper.addView(
            depthLabel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ),
        )
        parent.addView(wrapper, index)

        return Binding(
            parent = parent,
            originalIndex = index,
            originalLayoutParams = originalParams,
            originalWidth = originalWidth,
            wrapper = wrapper,
            title = title,
            ancCard = ancCard,
            depthLabel = depthLabel,
            address = address,
            environment = environment,
        ).also { binding ->
            depthLabel.setOnClickListener(binding::onDepthTapped)
            ModuleLog.debug("MiLinkUi", "bound Honor X5s Pro depth label")
        }
    }

    private class Binding(
        parent: ViewGroup,
        private val originalIndex: Int,
        private val originalLayoutParams: ViewGroup.LayoutParams,
        private val originalWidth: Int,
        wrapper: View,
        title: View,
        ancCard: View,
        depthLabel: TextView,
        private val address: String,
        private val environment: MiLinkCardEnvironment,
    ) : MiLinkCardBinding {
        private val parent = WeakReference(parent)
        private val wrapper = WeakReference(wrapper)
        private val title = WeakReference(title)
        private val ancCard = WeakReference(ancCard)
        private val depthLabel = WeakReference(depthLabel)

        // Vendor cycle order: deep -> smart -> light -> medium -> deep.
        private var depthIndex = 0

        override fun render(state: EarbudState) {
            val wrapper = wrapper.get() ?: return
            val title = title.get() ?: return
            val ancCard = ancCard.get() ?: return
            val depthLabel = depthLabel.get() ?: return

            wrapper.visibility = ancCard.visibility
            depthLabel.visibility =
                if (ancCard.isVisible && title.isVisible && state.noiseMode == NoiseMode.ANC) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }

        fun onDepthTapped(view: View) {
            depthIndex = (depthIndex + 1) % DEPTH_NAMES.size
            depthLabel.get()?.text = DEPTH_NAMES[depthIndex]
            environment.controlSender(address, NoiseMode.WIND)
        }

        override fun unbind() {
            val parent = parent.get() ?: return
            val wrapper = wrapper.get() ?: return
            val title = title.get() ?: return
            val depthLabel = depthLabel.get()
            depthLabel?.setOnClickListener(null)
            if (wrapper.parent !== parent) return

            (title.parent as? ViewGroup)?.removeView(title)
            parent.removeView(wrapper)
            originalLayoutParams.width = originalWidth
            title.layoutParams = originalLayoutParams
            parent.addView(title, originalIndex.coerceAtMost(parent.childCount))
        }
    }

    private fun android.content.Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private const val ANC_CARD_TITLE_ID = "anc_card_title"
    private const val ANC_CARD_ID = "anc_card"
    private const val END_PADDING_DP = 8
    private val DEPTH_NAMES = arrayOf("深度", "智能", "轻度", "中度")
}
