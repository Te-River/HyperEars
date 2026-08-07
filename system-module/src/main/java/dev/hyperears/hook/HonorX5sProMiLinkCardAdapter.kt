package dev.hyperears.hook

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
 * Depth is a device-specific variant of the ANC branch, so it is exposed as a tappable label
 * beside the native ANC title (same accessory contract as the wind-noise switches used by the
 * StarRing / Rose / NiceHCK models). The stock three-state row and the card's trailing host
 * controls stay untouched. Tapping the label sends the adapter's WIND trigger, which cycles the
 * vendor depth order and encodes an ANC command with the freshly selected depth.
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
            text = DEPTH_LABEL
            setTextColor(title.currentTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, title.textSize)
            typeface = title.typeface
            setPadding(0, 0, root.context.dp(LABEL_END_PADDING_DP), 0)
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
            // Layout may not be finished during bind; defer the icon injection so the fallback
            // can measure the card's largest ImageView reliably.
            root.post { applyHeadsetIcon(root) }
            dumpCardImageViews(root)
            ModuleLog.debug("MiLinkUi", "bound Honor X5s Pro depth accessory")
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

        override fun render(state: EarbudState) {
            val wrapper = wrapper.get() ?: return
            val title = title.get() ?: return
            val ancCard = ancCard.get() ?: return
            val depthLabel = depthLabel.get() ?: return

            wrapper.visibility = ancCard.visibility
            // Depth is a sub-option of ANC: visible and enabled only while ANC is active.
            val enabled = ancCard.isVisible && title.isVisible && state.noiseMode == NoiseMode.ANC
            depthLabel.visibility = if (enabled) View.VISIBLE else View.GONE
            depthLabel.isEnabled = enabled
            depthLabel.alpha = if (enabled) ENABLED_ALPHA else DISABLED_ALPHA
        }

        fun onDepthTapped(view: View) {
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

    /** Injects the transparent product icon into the card's headset/device icon slot. */
    private fun applyHeadsetIcon(root: View) {
        val drawable = HonorX5sHeadsetIcon.drawable(root.resources) ?: return
        val target = HEADSET_ICON_IDS.mapNotNull { name ->
            runCatching { root.findMiLinkView(name) as? ImageView }.getOrNull()
        }.firstOrNull { it.drawable != null || it.parent != null }
            ?: largestCardImageView(root)
        if (target == null) return
        target.setImageDrawable(drawable)
        ModuleLog.debug("MiLinkUi", "applied Honor headset product icon")
    }

    private fun largestCardImageView(root: View): ImageView? {
        var best: ImageView? = null
        var bestArea = -1L
        root.forEachImageView { view ->
            val width = view.width.takeIf { it > 0 } ?: view.layoutParams?.width ?: 0
            val height = view.height.takeIf { it > 0 } ?: view.layoutParams?.height ?: 0
            val area = width.toLong() * height
            if (area > bestArea && view.drawable != null) {
                best = view
                bestArea = area
            }
        }
        return best
    }

    /** One-shot inventory of card ImageViews so the headset icon slot can be identified. */
    private fun dumpCardImageViews(root: View) {
        if (cardImagesDumped.get()) return
        cardImagesDumped.set(true)
        var found = 0
        root.forEachImageView { view ->
            val idName = runCatching {
                view.resources.getResourceEntryName(view.id)
            }.getOrNull() ?: "0x${Integer.toHexString(view.id)}"
            ModuleLog.debug(
                "MiLinkUi",
                "card ImageView id=$idName drawable=${view.drawable?.javaClass?.simpleName}",
            )
            found++
        }
        ModuleLog.debug("MiLinkUi", "card ImageView inventory done, found=$found")
    }

    private fun View.forEachImageView(block: (ImageView) -> Unit) {
        if (this is ImageView) block(this)
        if (this is ViewGroup) {
            for (index in 0 until childCount) getChildAt(index).forEachImageView(block)
        }
    }

    private fun android.content.Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private const val ANC_CARD_TITLE_ID = "anc_card_title"
    private const val ANC_CARD_ID = "anc_card"
    private const val DEPTH_LABEL = "降噪深度"
    private const val LABEL_END_PADDING_DP = 8
    private const val ENABLED_ALPHA = 1.0f
    private const val DISABLED_ALPHA = 0.45f

    private val HEADSET_ICON_IDS = listOf(
        "headset_icon",
        "device_icon",
        "headset_icon_img",
        "ic_headset",
        "headset_image",
        "device_image",
        "avatar",
        "device_img",
    )

    private val cardImagesDumped = java.util.concurrent.atomic.AtomicBoolean()
}
