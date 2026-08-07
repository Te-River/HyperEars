package dev.hyperears.hook

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
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
 * Depth levels (deep/smart/light/medium) are not physical MiLink modes; a native host ANC item
 * (same icon and layout as the ANC row) is appended to the card as a depth indicator. Tapping the
 * item cycles the vendor depth order and sends the adapter's WIND trigger, which encodes an ANC
 * command with the freshly selected depth. The item title keeps its own index because
 * [EarbudState] carries no depth projection; order and naming live in [HonorAncDepthControlPolicy].
 */
internal object HonorX5sProMiLinkCardAdapter : MiLinkCardAdapter {
    override val presentationId: MiLinkCardPresentationId = HonorX5sProAdapter.PRESENTATION_ID

    override fun bind(
        root: View,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkCardBinding? {
        val ancCard = root.findMiLinkView(ANC_CARD_ID) as? LinearLayout ?: return null
        val noiseCancellation = root.findMiLinkView(ANC_NOISE_CANCELLATION_ID) ?: return null

        val depthItem = createNativeMiLinkAncItem(
            context = root.context,
            hostClassLoader = environment.hostClassLoader,
            layoutTemplate = noiseCancellation,
        ) ?: return null
        val depthTitle = depthItem.findMiLinkView(ANC_TITLE_ID) as? TextView ?: return null
        val depthIcon = depthItem.findMiLinkView(ANC_ICON_ID) as? ImageView ?: return null
        val noiseIcon =
            noiseCancellation.findMiLinkView(ANC_ICON_ID) as? ImageView ?: return null

        val depthIconDrawable = findHeadsetIcon(root, noiseIcon)
        depthTitle.text = HonorAncDepthControlPolicy.displayName(0)
        depthIcon.setImageDrawable(depthIconDrawable)
        depthItem.contentDescription = HonorAncDepthControlPolicy.displayName(0)
        depthItem.isSaveEnabled = false
        depthItem.isClickable = true
        depthItem.isFocusable = true

        ancCard.addView(depthItem)

        val binding = Binding(
            parent = ancCard,
            depthItem = depthItem,
            depthTitle = depthTitle,
            address = address,
            environment = environment,
        )
        depthItem.setOnClickListener(binding::onDepthTapped)
        dumpCardImageViews(root)
        ModuleLog.debug("MiLinkUi", "bound Honor X5s Pro native depth item")
        return binding
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

    private class Binding(
        parent: LinearLayout,
        depthItem: View,
        depthTitle: TextView,
        private val address: String,
        private val environment: MiLinkCardEnvironment,
    ) : MiLinkCardBinding {
        private val parent = WeakReference(parent)
        private val depthItem = WeakReference(depthItem)
        private val depthTitle = WeakReference(depthTitle)

        private var depthIndex = 0

        override fun render(state: EarbudState) {
            val depthItem = depthItem.get() ?: return
            // Depth is a sub-option of ANC: visible and enabled only while ANC is active.
            val enabled = state.sessionActive && state.connected && state.noiseMode == NoiseMode.ANC
            depthItem.isVisible = enabled
            depthItem.isEnabled = enabled
            depthItem.alpha = if (enabled) ENABLED_ALPHA else DISABLED_ALPHA
        }

        fun onDepthTapped(view: View) {
            depthIndex = HonorAncDepthControlPolicy.nextIndex(depthIndex)
            val name = HonorAncDepthControlPolicy.displayName(depthIndex)
            depthTitle.get()?.text = name
            depthItem.get()?.contentDescription = name
            environment.controlSender(address, NoiseMode.WIND)
        }

        override fun unbind() {
            val parent = parent.get() ?: return
            val depthItem = depthItem.get() ?: return
            if (depthItem.parent !== parent) return

            depthItem.setOnClickListener(null)
            parent.removeView(depthItem)
        }
    }

    private const val ANC_CARD_ID = "anc_card"
    private const val ANC_NOISE_CANCELLATION_ID = "anc_noise_cancel"
    private const val ANC_TITLE_ID = "anc_title"
    private const val ANC_ICON_ID = "anc_icon"
    private const val ENABLED_ALPHA = 1.0f
    private const val DISABLED_ALPHA = 0.45f

    /**
     * Prefers a headset-looking icon inside the card; falls back to the ANC icon. Candidate ids
     * are resolved by name so ROM resource renames degrade to the fallback instead of failing.
     */
    private fun findHeadsetIcon(root: View, noiseIcon: ImageView): android.graphics.drawable.Drawable? {
        HEADSET_ICON_IDS.mapNotNull { name ->
            runCatching { root.findMiLinkView(name) as? ImageView }.getOrNull()
        }.firstOrNull { it.drawable != null }?.let { candidate ->
            ModuleLog.debug("MiLinkUi", "depth item uses headset icon from $HEADSET_ICON_IDS")
            return candidate.drawable
        }
        return noiseIcon.drawable?.constantState
            ?.newDrawable(root.resources)
            ?.mutate()
            ?: noiseIcon.drawable
    }

    private val HEADSET_ICON_IDS = listOf(
        "headset_icon",
        "device_icon",
        "headset_icon_img",
        "ic_headset",
        "headset_image",
        "device_image",
    )

    private val cardImagesDumped = java.util.concurrent.atomic.AtomicBoolean()
}

/** Pure cycle policy for the Honor ANC depth item; UI code contains no depth state logic. */
internal object HonorAncDepthControlPolicy {
    private val DEPTH_NAMES = arrayOf("深度", "智能", "轻度", "中度")

    fun displayName(index: Int): String = DEPTH_NAMES[index]

    fun nextIndex(index: Int): Int = (index + 1) % DEPTH_NAMES.size
}
