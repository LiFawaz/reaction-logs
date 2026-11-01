package me.lifawaz.valenced.reactionlogs

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
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
        // Match the popup's row data model by its stable Kotlin data-class string.
        // This is the same DexFinder anchor pattern used by Valenced's who-reacted plugin.
        val emojiItemClass = dexFinder.findClass {
            matcher { addUsingString(REACTION_EMOJI_ITEM_PREFIX) }
        } ?: error("ReactionEmojiItem data class not found")

        val reactionField = emojiItemClass.declaredFields.singleOrNull {
            !Modifier.isStatic(it.modifiers) &&
                !it.type.isPrimitive &&
                it.type != String::class.java
        } ?: error("ReactionEmojiItem reaction field not found")
        reactionField.isAccessible = true

        // MessageReaction contains one reference field: its MessageReactionEmoji.
        // Determine it from runtime field types rather than R8-renamed getters.
        val emojiField = reactionField.type.declaredFields.singleOrNull {
            !Modifier.isStatic(it.modifiers) &&
                !it.type.isPrimitive &&
                it.type != String::class.java
        } ?: error("MessageReaction emoji field not found")
        emojiField.isAccessible = true

        // The row's specialized configure method runs after RecyclerView assigned
        // the data item. Hook it after binding, then update that row's native view.
        val configure = dexFinder.findMethod {
            matcher {
                paramCount(2)
                paramTypes("int", emojiItemClass.name)
            }
        } ?: error("Reaction emoji row configure method not found")

        unhooks += configure.hook(after = { param ->
            bindDetailsRow(
                param.thisObject,
                param.args.getOrNull(1),
                reactionField,
                emojiField,
            )
        })

        // Capture the message ID at the exact point Discord opens the popup.
        // The second long argument is the message ID; the first is channel ID.
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
                XposedBridge.log(
                    "Reaction Logs: popup opener captured message " +
                        (detailsMessageId ?: "unavailable"),
                )
            })
        }

        // Compatibility fallback for builds where Discord changed the popup
        // launch signature. Use addUsingString because lifecycle methods can
        // contain multiple EXTRA_* constants.
        val popupLifecycleHooks = listOfNotNull(
            dexFinder.findMethod {
                matcher {
                    name("onViewCreated")
                    paramCount(2)
                    addUsingString(REACTION_MESSAGE_EXTRA)
                }
            },
            dexFinder.findMethod {
                matcher {
                    name("onViewBound")
                    paramCount(1)
                    addUsingString(REACTION_MESSAGE_EXTRA)
                }
            },
        ).distinct()

        for (method in popupLifecycleHooks) {
            unhooks += method.hook(after = { param ->
                val messageId = readDetailsMessageId(param.thisObject)
                if (!messageId.isNullOrBlank()) detailsMessageId = messageId
                XposedBridge.log(
                    "Reaction Logs: popup lifecycle captured message " +
                        (detailsMessageId ?: "unavailable"),
                )
            })
        }

        if (popupOpener == null && popupLifecycleHooks.isEmpty()) {
            XposedBridge.log(
                "Reaction Logs: WARNING: popup row hook installed but message ID hook was not found",
            )
        }

        XposedBridge.log(
            "Reaction Logs: popup row hook installed (row=" +
                configure.declaringClass.name + "." + configure.name +
                ", opener=" + (popupOpener != null) +
                ", lifecycle=" + popupLifecycleHooks.size + ")",
        )
    }

    private fun readDetailsMessageId(fragment: Any): String? {
        val arguments = invokeNoArgs(fragment, "getArguments") as? android.os.Bundle
        val argumentId = runCatching {
            arguments?.getLong(REACTION_MESSAGE_EXTRA, 0L) ?: 0L
        }.getOrDefault(0L)
        if (argumentId > 0L) return argumentId.toString()

        // Older Discord popup implementations read the message ID from the host Intent.
        val intent = invokeNoArgs(fragment, "getMostRecentIntent") as? android.content.Intent
        val intentId = runCatching {
            intent?.getLongExtra(REACTION_MESSAGE_EXTRA, 0L) ?: 0L
        }.getOrDefault(0L)
        return intentId.takeIf { it > 0L }?.toString()
    }

    private fun invokeNoArgs(target: Any, methodName: String): Any? {
        val method = generateSequence(target.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == methodName && it.parameterCount == 0 }
            ?: target.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 0
            }
            ?: return null

        return runCatching {
            method.isAccessible = true
            method.invoke(target)
        }.getOrNull()
    }

    private fun bindDetailsRow(
        holder: Any,
        data: Any?,
        reactionField: java.lang.reflect.Field,
        emojiField: java.lang.reflect.Field,
    ) {
        if (data == null) return
        val reaction = runCatching { reactionField.get(data) }.getOrNull() ?: return
        val emoji = runCatching { emojiField.get(reaction) }.getOrNull() ?: return

        val itemView = holderItemView(holder) ?: return
        val container = reactionEmojiContainer(itemView) ?: return
        val emojiPart = emojiKey(emoji) ?: return
        val messageId = detailsMessageId
        val key = if (messageId.isNullOrBlank()) emojiPart else "$messageId|$emojiPart"

        detailKeys[container] = key
        applyDetailsTimestamp(container, key)
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

    private fun reactionEmojiContainer(itemView: View): ViewGroup? {
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

        val container = if (containerId != 0) {
            itemView.findViewById<View>(containerId) as? ViewGroup
        } else {
            null
        } ?: (emojiView.parent as? ViewGroup) ?: return null

        ensureDetailsLabel(container, emojiView)
        return container
    }

    private fun findEmojiView(root: View): View? {
        if (root.javaClass.name.contains("SimpleDraweeSpanTextView")) return root
        if (root !is ViewGroup) return null

        for (index in 0 until root.childCount) {
            findEmojiView(root.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun ensureDetailsLabel(container: ViewGroup, emojiView: View): TextView {
        detailLabels[container]?.let { existing ->
            if (existing.parent === container) return existing
            detailLabels.remove(container)
        }

        val label = TextView(container.context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTextColor(REMOVED_RED)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            maxLines = 1
            typeface = (emojiView as? TextView)?.typeface
            visibility = View.GONE
        }
        val margin = (3f * container.resources.displayMetrics.density).toInt()
        val layoutParams: ViewGroup.LayoutParams = if (container is LinearLayout) {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                marginStart = margin
                gravity = Gravity.CENTER_VERTICAL
            }
        } else {
            ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                marginStart = margin
            }
        }
        val insertIndex = (container.indexOfChild(emojiView) + 1).coerceIn(0, container.childCount)
        container.addView(label, insertIndex, layoutParams)
        detailLabels[container] = label
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
                if (matches.map { it.key.substringBefore('|') }.distinct().size == 1) {
                    matches.maxOfOrNull { it.value }
                } else {
                    null
                }
            }
        }
        val label = detailLabels[container] ?: return
        if (timestamp == null) {
            label.visibility = View.GONE
            return
        }

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
        val parsedId = ID_PATTERN.find(text)?.groupValues?.get(1)?.takeIf { it != "null" }
        val parsedName = NAME_PATTERN.find(text)?.groupValues?.get(1)
            ?: FALLBACK_NAME_PATTERN.find(text)?.groupValues?.get(1)
        return when {
            parsedName != null && parsedId != null -> "$parsedName:$parsedId"
            parsedName != null -> parsedName
            else -> parsedId
        }
    }

    private companion object {
        val ID_PATTERN = Regex(""", id=([^,)]*)""")
        val NAME_PATTERN = Regex("""\(name=(.*?), src=""")
        val FALLBACK_NAME_PATTERN = Regex("""\(name=([^,)]*)""")
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
