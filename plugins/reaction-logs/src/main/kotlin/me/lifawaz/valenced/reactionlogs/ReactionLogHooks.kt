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
private const val MANAGE_REACTIONS_MODEL_PROVIDER =
    "com.discord.widgets.chat.managereactions.ManageReactionsModelProvider"
private const val MANAGE_REACTIONS_EMOJI_VIEW_HOLDER =
    "com.discord.widgets.chat.managereactions.ManageReactionsEmojisAdapter\$ReactionEmojiViewHolder"
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
    private val detailKeys = WeakHashMap<LinearLayout, String>()
    private val detailLabels = WeakHashMap<LinearLayout, TextView>()
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
        val providerClass = dexFinder.classLoader.loadClass(MANAGE_REACTIONS_MODEL_PROVIDER)
        val messageIdGetter = providerClass.methods.firstOrNull {
            it.name == "getMessageId" && it.parameterCount == 0
        } ?: error("ManageReactionsModelProvider.getMessageId not found")
        val getModel = providerClass.methods.firstOrNull {
            it.name == "get" && it.parameterCount == 0
        } ?: error("ManageReactionsModelProvider.get not found")

        unhooks += getModel.hook(before = { param ->
            detailsMessageId = runCatching {
                messageIdGetter.invoke(param.thisObject)?.toString()
            }.getOrNull()
        })

        val holderClass = dexFinder.classLoader.loadClass(MANAGE_REACTIONS_EMOJI_VIEW_HOLDER)
        val configure = holderClass.declaredMethods.firstOrNull {
            (it.name == "onConfigure2" || it.name == "onConfigure") &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1].name.contains("ReactionEmojiItem")
        } ?: error("ReactionEmojiViewHolder.onConfigure not found")
        configure.isAccessible = true

        unhooks += configure.hook(after = { param ->
            val itemView = holderItemView(param.thisObject)
            val container = itemView?.let(::reactionEmojiContainer)
            if (container != null) {
                val messageId = detailsMessageId
                val data = param.args.getOrNull(1)
                val reaction = data?.let { value ->
                    runCatching {
                        value.javaClass.methods.firstOrNull {
                            it.name == "getReaction" && it.parameterCount == 0
                        }?.invoke(value)
                    }.getOrNull()
                }
                val emoji = reaction?.let { value ->
                    runCatching {
                        value.javaClass.methods.firstOrNull {
                            it.name == "getEmoji" && it.parameterCount == 0
                        }?.invoke(value)
                    }.getOrNull()
                }
                val key = if (messageId != null && emoji != null) {
                    emojiKey(emoji)?.let { "$messageId|$it" }
                } else {
                    null
                }

                if (key != null) {
                    detailKeys[container] = key
                    applyDetailsTimestamp(container, key)
                } else {
                    detailKeys.remove(container)
                    detailLabels[container]?.visibility = View.GONE
                }
            }
        })
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
            current = type.superclass
        }
        return null
    }

    private fun reactionEmojiContainer(itemView: View): LinearLayout? {
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
        if (containerId == 0 || emojiId == 0) return null

        val container = itemView.findViewById<View>(containerId) as? LinearLayout ?: return null
        val emojiView = itemView.findViewById<View>(emojiId) ?: return null
        ensureDetailsLabel(container, emojiView)
        return container
    }

    private fun ensureDetailsLabel(container: LinearLayout, emojiView: View): TextView {
        detailLabels[container]?.let { return it }

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
        val layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply {
            marginStart = margin
            gravity = Gravity.CENTER_VERTICAL
        }
        val insertIndex = (container.indexOfChild(emojiView) + 1).coerceIn(0, container.childCount)
        container.addView(label, insertIndex, layoutParams)
        detailLabels[container] = label
        return label
    }

    private fun applyDetailsTimestamp(container: LinearLayout, key: String) {
        val prefix = "$key|"
        val timestamp = synchronized(removedAtByKey) {
            removedAtByKey.entries.asSequence()
                .filter { it.key.startsWith(prefix) && it.value > 0L }
                .maxOfOrNull { it.value }
        }
        val label = detailLabels[container] ?: return
        if (timestamp == null) {
            label.visibility = View.GONE
            return
        }

        label.text = formatLocalTime(timestamp)
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
