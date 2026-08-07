package dev.hyperears.hook

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import dev.hyperears.integration.HonorX5sProAdapter
import dev.hyperears.integration.MiLinkCardPresentationId

/**
 * Honor X5s Pro MiLink card presentation.
 *
 * Noise control stays entirely native: like the OPPO and vivo/iQOO adapters, only MiLink's
 * stock three-state ANC card is used and no depth-switching extension is added. This adapter
 * only injects the transparent product icon into the headset icon slot and inventories card
 * ImageViews on first bind.
 */
internal object HonorX5sProMiLinkCardAdapter : MiLinkCardAdapter {
    override val presentationId: MiLinkCardPresentationId = HonorX5sProAdapter.PRESENTATION_ID

    override fun bind(
        root: View,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkCardBinding? {
        // Layout may not be finished during bind; defer the icon injection so the fallback
        // can measure the card's largest ImageView reliably.
        root.post { applyHeadsetIcon(root) }
        dumpCardImageViews(root)
        ModuleLog.debug("MiLinkUi", "bound Honor X5s Pro card presentation")
        return MiLinkCardBinding {}
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

    private val HEADSET_ICON_IDS = listOf(
        "circulate_headset_icon",
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
