package me.lifawaz.valenced.reactionlogs

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextSwitcher
import android.widget.TextView
import app.valenced.bridge.ValencedBridge
import app.valenced.xposed.api.DexFinder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TimeZone
import java.util.WeakHashMap

private const val SHORTCUTS_FLEXBOX = "com.discord.reactions.ShortcutsFlexbox"
private const val REACTION_MESSAGE_EXTRA = "com.discord.intent.extra.EXTRA_MESSAGE_ID"
private const val REACTION_EMOJI_ITEM_PREFIX = "ReactionEmojiItem(reaction="
private const val REACTION_EMOJI_CONTAINER_ID = "manage_reactions_emoji_container"
private const val REACTION_EMOJI_VIEW_ID = "manage_reactions_emoji_emoji_textview"

private val REMOVED_RED = Color.rgb(237, 66, 69)
private val REMOVED_TINT = Color.argb(48, 237, 66, 69)

internal class ReactionLogHooks(
    dexFinder: DexFinder,
    private val bridge: ValencedBridge,
) {
    private data class Pill(val emoji: Any?, val burst: Boolean, val count: Int)
    private data class DetailsPlacement(val host: ViewGroup, val anchor: View)

    private data class OriginalStyle(
        val backgroundTint: ColorStateList?,
        val countColors: ColorStateList?,
    )

    private val removedAtByKey = LinkedHashMap<String, Long>()
    private val bound = WeakHashMap<LinearLayout, String>()
    private val originalStyles = WeakHashMap<LinearLayout, OriginalStyle>()
    private val detailKeys = WeakHashMap<ViewGroup, String>()
    private val detailLabels = WeakHashMap<ViewGroup, TextView>()
    @Volatile private var detailsMessageId: String? = null
    @Volatile private var use24HourClock = false
    private val bindingMessageId = ThreadLocal<String?>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val unhooks = mutableListOf<XC_MethodHook.Unhook>()
    private val detailLogKeys = LinkedHashSet<String>()

    init {
        hookReactionPills(dexFinder)
        runCatching { hookReactionDetails(dexFinder) }.onFailure {
            XposedBridge.log("Reaction Logs: reaction-details timestamp hook unavailable: $it")
        }
    }

    fun setRemoved(json: String) {
        val parsed = LinkedHashMap<String, Long>()
        val array = JSONArray(json)

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val key = item.optString("pillKey").takeIf { it.isNotBlank() } ?: continue
            parsed[key] = item.optLong("removedAt", 0L)
        }

        synchronized(removedAtByKey) {
            removedAtByKey.clear()
            removedAtByKey.putAll(parsed)
        }

        mainHandler.post {
            for ((pill, key) in bound.entries.toList()) {
                applyStyle(pill, isRemoved(key))
            }
            for ((container, key) in detailKeys.entries.toList()) {
                applyDetailsTimestamp(container, key)
            }
        }
    }

    fun setTimeFormat24Hour(enabled: Boolean) {
        use24HourClock = enabled
        mainHandler.post {
            for ((container, key) in detailKeys.entries.toList()) {
                applyDetailsTimestamp(container, key)
            }
        }
    }

    fun clear() {
        synchronized(removedAtByKey) { removedAtByKey.clear() }
        mainHandler.post {
            for (pill in bound.keys.toList()) {
                applyStyle(pill, false)
            }
            for ((container, key) in detailKeys.entries.toList()) {
                applyDetailsTimestamp(container, key)
            }
        }
    }

    fun dispose() {
        unhooks.forEach { it.unhook() }
        unhooks.clear()
        synchronized(removedAtByKey) { removedAtByKey.clear() }
        detailsMessageId = null
        mainHandler.post {
            for (pill in bound.keys.toList()) {
                applyStyle(pill, false)
            }
            for ((container, label) in detailLabels.entries.toList()) {
                if (label.parent === container) container.removeView(label)
            }
            bound.clear()
            originalStyles.clear()
            detailKeys.clear()
            detailLabels.clear()
        }
    }

    private fun isRemoved(key: String): Boolean =
        synchronized(removedAtByKey) { key in removedAtByKey }

    private fun hookReactionPills(dexFinder: DexFinder) {
        val accessoryClass = dexFinder.findClass {
            matcher { usingEqStrings("messageId", "accessoryDescription") }
        } ?: error("MessageAccessory not found")

        val accessoryMessageId = dexFinder.findMethod {
            matcher {
                declaredClass(accessoryClass.name)
                paramCount(0)
                returnType(String::class.java)
            }
        } ?: error("MessageAccessory.getMessageId not found")

        val emojiClass = dexFinder.findClass {
            matcher { addUsingString("MessageReactionEmoji(name=") }
        } ?: error("MessageReactionEmoji not found")

        val reactionInterface = dexFinder.findClass {
            matcher {
                modifiers(Modifier.INTERFACE)
                addMethod { name("getCount") }
                addMethod { returnType(emojiClass.name) }
            }
        } ?: error("ReactionView.Reaction not found")

        val getCount = reactionInterface.getMethod("getCount")
        val getEmoji = reactionInterface.methods.single { it.returnType == emojiClass }
        val getBurstCount = reactionInterface.methods.single {
            it.returnType == Int::class.javaPrimitiveType &&
                it.parameterCount == 0 &&
                it != getCount
        }

        val onBindViewHolder = dexFinder.findMethod {
            matcher {
                paramCount(2)
                usingEqStrings("reactions", "addNewReactionAccessibilityLabel")
            }
        } ?: error("MessageAccessoriesAdapter.onBindViewHolder not found")

        val adapterItems = onBindViewHolder.declaringClass.declaredFields
            .single { List::class.java.isAssignableFrom(it.type) && !Modifier.isStatic(it.modifiers) }
            .apply { isAccessible = true }

        val setReactions = dexFinder.findMethod {
            matcher {
                declaredClass(SHORTCUTS_FLEXBOX)
                usingEqStrings("reactions", "addNewReactionAccessibilityLabel")
            }
        } ?: error("ShortcutsFlexbox.setReactions not found")

        unhooks += onBindViewHolder.hook(
            before = { param ->
                val index = param.args[1] as Int
                val item = (adapterItems.get(param.thisObject) as List<*>?)?.getOrNull(index)
                if (item != null && accessoryClass.isInstance(item)) {
                    bindingMessageId.set(accessoryMessageId.invoke(item) as String?)
                } else {
                    bindingMessageId.remove()
                }
            },
            after = { bindingMessageId.remove() },
        )

        unhooks += setReactions.hook(after = { param ->
            val flexbox = param.thisObject as ViewGroup
            val reactions = param.args[0] as? List<*> ?: emptyList<Any>()
            val messageId = bindingMessageId.get()
            val reactionPills = pills(reactions, getCount, getBurstCount, getEmoji)

            for (index in 0 until flexbox.childCount) {
                val pillView = flexbox.getChildAt(index) as? LinearLayout ?: continue
                val pill = reactionPills.getOrNull(index)

                if (
                    pill == null ||
                    messageId == null ||
                    pill.count == 0 ||
                    pill.emoji == null
                ) {
                    bound.remove(pillView)
                    applyStyle(pillView, false)
                    continue
                }

                val emoji = emojiKey(pill.emoji)
                if (emoji.isNullOrBlank()) {
                    bound.remove(pillView)
                    applyStyle(pillView, false)
                    continue
                }

                val key = reactionKey(messageId, emoji, if (pill.burst) 1 else 0)
                bound[pillView] = key
                applyStyle(pillView, isRemoved(key))
            }
        })
    }

    private fun applyStyle(view: LinearLayout, removed: Boolean) {
        val count = countText(view)
        val original = originalStyles.getOrPut(view) {
            OriginalStyle(view.backgroundTintList, count?.textColors)
        }

        if (removed) {
            view.backgroundTintList = ColorStateList.valueOf(REMOVED_TINT)
            count?.setTextColor(REMOVED_RED)
        } else {
            view.backgroundTintList = original.backgroundTint
            original.countColors?.let { count?.setTextColor(it) }
        }
    }

    private fun hookReactionDetails(dexFinder: DexFinder) {
        val emojiItemClass = dexFinder.findClass {
            matcher { addUsingString(REACTION_EMOJI_ITEM_PREFIX) }
        } ?: error("ReactionEmojiItem data class not found")

        // Resolve the actual emoji model by the same stable signature used by the
        // reaction-pill hook. Do not assume MessageReaction has only one object field:
        // Discord adds nested count/detail fields between app versions.
        val emojiClass = dexFinder.findClass {
            matcher { addUsingString("MessageReactionEmoji(name=") }
        } ?: error("MessageReactionEmoji model class not found")

        val reactionCandidates = instanceFields(emojiItemClass).filter { field ->
            !field.type.isPrimitive &&
                field.type != String::class.java &&
                field.type != emojiClass &&
                instanceFields(field.type).any { nested -> nested.type == emojiClass }
        }
        val reactionField = reactionCandidates.singleOrNull()
            ?: error(
                "ReactionEmojiItem reaction field not unique; candidates=" +
                    reactionCandidates.joinToString { it.name + ":" + it.type.name },
            )
        reactionField.isAccessible = true

        val emojiField = instanceFields(reactionField.type).singleOrNull {
            it.type == emojiClass
        } ?: error("MessageReaction emoji field not found on ${reactionField.type.name}")
        emojiField.isAccessible = true

        // First search only by the exact runtime signature. The old search required
        // the DEX string "data", which can disappear when Kotlin compiler output changes.
        // Keep that narrower query as a fallback for builds with duplicate signatures.
        val configure = dexFinder.findMethod {
            matcher {
                paramCount(2)
                paramTypes("int", emojiItemClass.name)
            }
        } ?: dexFinder.findMethod {
            matcher {
                paramCount(2)
                paramTypes("int", emojiItemClass.name)
                usingEqStrings("data")
            }
        } ?: error("Reaction emoji row configure method not found by typed signature")

        // Delay until Discord finishes rendering the emoji and counter.
        unhooks += configure.hook(after = { param ->
            val holder = param.thisObject
            val data = param.args.getOrNull(1)
            detailLogOnce("typed-row-hit", "typed reaction emoji row binder was called")
            if (data != null && emojiItemClass.isInstance(data)) {
                mainHandler.post {
                    bindDetailsRow(holder, data, reactionField, emojiField)
                }
            } else {
                detailLogOnce(
                    "typed-row-data-mismatch",
                    "typed row binder received an unexpected data object: ${data?.javaClass?.name ?: "null"}",
                )
            }
        })

        // Also hook the generic RecyclerView holder binder where present. Some Discord
        // builds dispatch through the erased base method rather than the typed override.
        val genericConfigure = generateSequence(configure.declaringClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { method ->
                method.name == "onConfigure" &&
                    method.parameterCount == 2 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    method.parameterTypes[1] == Any::class.java
            }

        if (genericConfigure != null && genericConfigure != configure) {
            genericConfigure.isAccessible = true
            unhooks += genericConfigure.hook(after = { param ->
                val holder = param.thisObject
                val data = param.args.getOrNull(1)
                if (data != null && emojiItemClass.isInstance(data)) {
                    detailLogOnce("generic-row-hit", "generic reaction row binder was called")
                    mainHandler.post {
                        bindDetailsRow(holder, data, reactionField, emojiField)
                    }
                }
            })
        }

        // Prefer the message ID from the popup launcher. The row Activity context
        // is checked independently in bindDetailsRow as a fallback.
        val popupOpener = dexFinder.findMethod {
            matcher {
                paramCount(4)
                paramTypes(
                    "long",
                    "long",
                    "android.content.Context",
                    reactionField.type.name,
                )
                addUsingString(REACTION_MESSAGE_EXTRA)
            }
        }

        if (popupOpener != null) {
            unhooks += popupOpener.hook(after = { param ->
                detailsMessageId = (param.args.getOrNull(1) as? Number)
                    ?.toLong()
                    ?.takeIf { it > 0L }
                    ?.toString()
                detailLogOnce(
                    "popup-opener",
                    "popup launcher captured message " + (detailsMessageId ?: "unavailable"),
                )
            })
        } else {
            detailLogOnce(
                "popup-opener-missing",
                "popup launcher signature was not found; row Activity context will be used",
            )
        }

        detailLogOnce(
            "hooks-installed",
            "reaction details hooks installed; typed=${configure.declaringClass.name}.${configure.name}; " +
                "generic=${genericConfigure?.declaringClass?.name ?: "not found"}; " +
                "emojiItem=${emojiItemClass.name}; emoji=${emojiClass.name}",
        )
    }

    private fun instanceFields(type: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            fields += current.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            current = current.superclass
        }
        return fields
    }

    private fun detailLogOnce(key: String, message: String) {
        val shouldLog = synchronized(detailLogKeys) { detailLogKeys.add(key) }
        if (shouldLog) XposedBridge.log("Reaction Logs: $message")
    }

    private fun bindDetailsRow(
        holder: Any,
        data: Any?,
        reactionField: Field,
        emojiField: Field,
    ) {
        if (data == null) return
        val reaction = runCatching { reactionField.get(data) }.getOrNull() ?: run {
            detailLogOnce("reaction-read-failed", "could not read the reaction object from the popup row")
            return
        }
        val emoji = runCatching { emojiField.get(reaction) }.getOrNull() ?: run {
            detailLogOnce("emoji-read-failed", "could not read the emoji object from the reaction")
            return
        }

        val itemView = holderItemView(holder) ?: run {
            detailLogOnce(
                "item-view-missing",
                "row binder ran, but no RecyclerView itemView was found on ${holder.javaClass.name}",
            )
            return
        }
        val placement = reactionDetailsPlacement(itemView) ?: run {
            detailLogOnce(
                "emoji-container-missing",
                "row binder ran, but no emoji container or suitable row parent was found; itemView=${itemView.javaClass.name}",
            )
            return
        }
        val emojiPart = emojiKey(emoji) ?: run {
            detailLogOnce(
                "emoji-key-missing",
                "row binder ran, but could not read the emoji name/ID from ${emoji.javaClass.name}",
            )
            return
        }
        val messageId = readMessageIdFromContext(itemView.context) ?: detailsMessageId
        val key = if (messageId.isNullOrBlank()) emojiPart else "$messageId|$emojiPart"

        val host = placement.host
        detailKeys[host] = key
        detailLogOnce(
            "row-bound",
            "popup emoji row bound; emoji=$emojiPart; messageId=${messageId ?: "unavailable"}; " +
                "labelHost=${host.javaClass.name}; anchor=${placement.anchor.javaClass.name}",
        )
        applyDetailsTimestamp(host, key)
    }

    private fun readMessageIdFromContext(context: android.content.Context): String? {
        var current: android.content.Context? = context
        val visited = HashSet<android.content.Context>()
        while (current != null && visited.add(current)) {
            if (current is android.app.Activity) {
                val id = runCatching {
                    current.intent?.getLongExtra(REACTION_MESSAGE_EXTRA, 0L) ?: 0L
                }.getOrDefault(0L)
                if (id > 0L) return id.toString()
            }
            current = (current as? android.content.ContextWrapper)?.baseContext
        }
        return null
    }

    private fun holderItemView(holder: Any): View? {
        runCatching {
            holder.javaClass.getField("itemView").get(holder) as? View
        }.getOrNull()?.let { return it }

        var current: Class<*>? = holder.javaClass
        while (current != null) {
            val type = current
            val field = runCatching { type.getDeclaredField("itemView") }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(holder) as? View
                }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    /**
     * Find a row-level host for the timestamp instead of putting the text inside
     * the emoji-only container (which is often measured to the emoji's width).
     * Prefer the nearest horizontal LinearLayout that has sibling content. If the
     * row is vertical, place the label after the emoji group as a separate line.
     */
    private fun reactionDetailsPlacement(itemView: View): DetailsPlacement? {
        val resources = itemView.resources
        val packageName = itemView.context.packageName
        val containerId = resources.getIdentifier(
            REACTION_EMOJI_CONTAINER_ID,
            "id",
            packageName,
        ).takeIf { it != 0 } ?: resources.getIdentifier(
            REACTION_EMOJI_CONTAINER_ID,
            "id",
            "com.discord",
        )
        val emojiId = resources.getIdentifier(
            REACTION_EMOJI_VIEW_ID,
            "id",
            packageName,
        ).takeIf { it != 0 } ?: resources.getIdentifier(
            REACTION_EMOJI_VIEW_ID,
            "id",
            "com.discord",
        )

        val emojiView = (if (emojiId != 0) {
            itemView.findViewById<View>(emojiId)
        } else {
            null
        }) ?: findEmojiView(itemView) ?: return null

        val emojiContainer = (if (containerId != 0) {
            itemView.findViewById<View>(containerId) as? ViewGroup
        } else {
            null
        }) ?: (emojiView.parent as? ViewGroup) ?: return null

        var childOnPath: View = emojiContainer
        var parent = emojiContainer.parent as? ViewGroup
        var nearestLinear: DetailsPlacement? = null

        while (parent != null) {
            if (parent is LinearLayout) {
                val candidate = DetailsPlacement(parent, childOnPath)
                if (nearestLinear == null) nearestLinear = candidate
                if (
                    parent.orientation == LinearLayout.HORIZONTAL &&
                    parent.childCount > 1
                ) {
                    return candidate
                }
            }
            childOnPath = parent
            parent = parent.parent as? ViewGroup
        }

        return nearestLinear ?: DetailsPlacement(emojiContainer, emojiView)
    }

    private fun findEmojiView(root: View): View? {
        if (root.javaClass.name.contains("SimpleDraweeSpanTextView")) return root
        if (root !is ViewGroup) return null

        for (index in 0 until root.childCount) {
            findEmojiView(root.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun ensureDetailsLabel(
        host: ViewGroup,
        anchor: View,
        emojiView: View,
    ): TextView {
        detailLabels[host]?.let { existing ->
            if (existing.parent === host) {
                val anchorIndex = host.indexOfChild(anchor)
                val labelIndex = host.indexOfChild(existing)
                if (anchorIndex >= 0 && labelIndex != anchorIndex + 1) {
                    host.removeView(existing)
                    host.addView(
                        existing,
                        (host.indexOfChild(anchor) + 1).coerceIn(0, host.childCount),
                    )
                }
                return existing
            }
            detailLabels.remove(host)
        }

        val label = TextView(host.context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
            setTextColor(REMOVED_RED)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (132f * host.resources.displayMetrics.density).toInt()
            typeface = (emojiView as? TextView)?.typeface
            visibility = View.GONE
        }
        val margin = (4f * host.resources.displayMetrics.density).toInt()
        val insertIndex = (host.indexOfChild(anchor) + 1).coerceIn(0, host.childCount)
        host.addView(
            label,
            insertIndex,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = margin
                gravity = Gravity.CENTER_VERTICAL
            },
        )
        detailLabels[host] = label
        return label
    }

    private fun applyDetailsTimestamp(container: ViewGroup, key: String) {
        val exactPrefix = if (key.contains("|")) "$key|" else null
        val timestamp = synchronized(removedAtByKey) {
            val exact = exactPrefix?.let { prefix ->
                removedAtByKey.entries.asSequence()
                    .filter { it.key.startsWith(prefix) && it.value > 0L }
                    .maxOfOrNull { it.value }
            }
            if (exact != null) {
                exact
            } else {
                // If Discord no longer exposes the provider's message ID, only use
                // a cross-message match when that emoji appears in one message.
                val emojiPart = key.substringAfter('|', key)
                val matches = removedAtByKey.entries.filter { entry ->
                    val parts = entry.key.split('|')
                    parts.size == 3 && parts[1] == emojiPart && entry.value > 0L
                }
                matches.maxOfOrNull { it.value }
            }
        }
        val label = detailLabels[container] ?: return
        if (timestamp == null) {
            detailLogOnce(
                "timestamp-not-matched",
                "popup row was found but no saved removal timestamp matched key=${key.take(64)}",
            )
            label.visibility = View.GONE
            return
        }

        detailLogOnce(
            "timestamp-matched",
            "saved removal timestamp matched popup row key=${key.take(64)}",
        )
        label.text = "removed at ${formatLocalTime(timestamp)}"
        label.visibility = View.VISIBLE
    }

    private fun formatLocalTime(timestamp: Long): String {
        val pattern = if (use24HourClock) "HH:mm" else "h:mm a"
        val locale = if (use24HourClock) Locale.ROOT else Locale.US
        return SimpleDateFormat(pattern, locale).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(timestamp))
    }

    private fun countText(view: ViewGroup): TextView? {
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            if (child is TextSwitcher) return child.currentView as? TextView
        }
        return null
    }

    private fun pills(
        reactions: List<*>,
        getCount: Method,
        getBurstCount: Method,
        getEmoji: Method,
    ): List<Pill> = reactions.flatMap { reaction ->
        if (reaction == null) return@flatMap emptyList()
        val count = getCount.invoke(reaction) as Int
        val burstCount = getBurstCount.invoke(reaction) as Int
        val emoji = getEmoji.invoke(reaction)
        when {
            burstCount > 0 && count > 0 -> listOf(Pill(emoji, true, burstCount), Pill(emoji, false, count))
            burstCount > 0 -> listOf(Pill(emoji, true, burstCount))
            count > 0 -> listOf(Pill(emoji, false, count))
            else -> emptyList()
        }
    }

    private fun emojiKey(emoji: Any): String? {
        val methods = emoji.javaClass.methods
        val id = runCatching {
            methods.firstOrNull { it.name == "getId" && it.parameterCount == 0 }
                ?.invoke(emoji)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()
        val name = runCatching {
            methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                ?.invoke(emoji)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()

        if (name != null) return if (id != null) "$name:$id" else name
        if (id != null) return id

        val text = emoji.toString()
        val parsedId = ID_PATTERN.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() && it != "null" }
        val parsedName = NAME_PATTERN.find(text)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() && it != "null" }
        return when {
            parsedName != null && parsedId != null -> "$parsedName:$parsedId"
            parsedName != null -> parsedName
            else -> parsedId
        }
    }

    private companion object {
        val ID_PATTERN = Regex("""(?:[(]|, )id=([^,)]*)""")
        val NAME_PATTERN = Regex("""(?:[(]|, )name=([^,)]*)""")
    }
}

private fun reactionKey(messageId: String, emojiKey: String, type: Int) =
    "$messageId|$emojiKey|$type"

private inline fun Method.hook(
    crossinline before: (XC_MethodHook.MethodHookParam) -> Unit = {},
    crossinline after: (XC_MethodHook.MethodHookParam) -> Unit = {},
): XC_MethodHook.Unhook = XposedBridge.hookMethod(this, object : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) = before(param)
    override fun afterHookedMethod(param: MethodHookParam) = after(param)
})
