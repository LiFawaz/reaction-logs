package me.lifawaz.valenced.reactionlogs

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
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
import java.util.Locale
import java.util.TimeZone
import java.util.WeakHashMap

private const val SHORTCUTS_FLEXBOX = "com.discord.reactions.ShortcutsFlexbox"

private val REMOVED_RED = Color.rgb(237, 66, 69)
private val REMOVED_TINT = Color.argb(48, 237, 66, 69)
private val POPUP_BACKGROUND = Color.rgb(35, 37, 40)

internal class ReactionLogHooks(
    dexFinder: DexFinder,
    private val bridge: ValencedBridge,
) {
    private data class Pill(val emoji: Any?, val burst: Boolean, val count: Int)
    private data class RemovedUser(val id: String, val name: String)
    private data class RemovedEntry(val removedAt: Long, val users: List<RemovedUser>)

    private data class OriginalStyle(
        val backgroundTint: ColorStateList?,
        val countColors: ColorStateList?,
    )

    private val removedAtByKey = LinkedHashMap<String, RemovedEntry>()
    /** Last real reaction model observed before Discord drops a zero-count row. */
    private val knownReactionModels = LinkedHashMap<String, Any>()
    private val lastReactionArgs = WeakHashMap<ViewGroup, Array<Any?>>()
    private val lastReactionMessageIds = WeakHashMap<ViewGroup, String>()
    private var setReactionsMethod: Method? = null
    private val internalRebind = ThreadLocal<Boolean>()
    private val bound = WeakHashMap<LinearLayout, String>()
    private val originalStyles = WeakHashMap<LinearLayout, OriginalStyle>()
    private val originalLongClickListeners = WeakHashMap<View, View.OnLongClickListener?>()
    private val wrappedLongClickListeners = WeakHashMap<View, Boolean>()
    private val removalPopups = WeakHashMap<View, PopupWindow>()
    private val internalListenerUpdate = ThreadLocal<Boolean>()
    @Volatile private var use24HourClock = false
    private val bindingMessageId = ThreadLocal<String?>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val unhooks = mutableListOf<XC_MethodHook.Unhook>()

    init {
        hookLongClickListeners()
        hookReactionPills(dexFinder)
    }

    fun setRemoved(json: String) {
        val parsed = LinkedHashMap<String, RemovedEntry>()
        val array = JSONArray(json)

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val key = item.optString("pillKey").takeIf { it.isNotBlank() } ?: continue
            val removedAt = item.optLong("removedAt", 0L)
            if (removedAt <= 0L) continue

            val users = item.optJSONArray("removedUsers")?.let { userArray ->
                buildList {
                    for (userIndex in 0 until userArray.length()) {
                        val user = userArray.optJSONObject(userIndex) ?: continue
                        val id = user.optString("id").takeIf {
                            it.isNotBlank() && it != "null"
                        }.orEmpty()
                        val name = user.optString("name").takeIf {
                            it.isNotBlank() && it != "null"
                        } ?: id.takeIf { it.isNotBlank() } ?: continue
                        add(RemovedUser(id, name))
                    }
                }
            }.orEmpty()

            parsed[key] = RemovedEntry(removedAt, users)
        }

        synchronized(removedAtByKey) {
            removedAtByKey.clear()
            removedAtByKey.putAll(parsed)
            synchronized(knownReactionModels) {
                knownReactionModels.keys.retainAll(parsed.keys)
            }
        }

        mainHandler.post {
            for ((pill, key) in bound.entries.toList()) {
                applyStyle(pill, isRemoved(key))
            }
            // Flux events can arrive after Discord has already rebound the row.
            // Re-run the last real reaction list now that the removal record exists.
            rebindMessages(parsed.keys)
        }
    }

    fun setTimeFormat24Hour(enabled: Boolean) {
        use24HourClock = enabled
        mainHandler.post {
            for ((pill, key) in bound.entries.toList()) {
                applyStyle(pill, isRemoved(key))
            }
        }
    }

    fun clear() {
        synchronized(removedAtByKey) { removedAtByKey.clear() }
        synchronized(knownReactionModels) { knownReactionModels.clear() }
        mainHandler.post {
            for ((pill, key) in bound.entries.toList()) {
                applyStyle(pill, false)
            }
        }
    }

    fun dispose() {
        unhooks.forEach { it.unhook() }
        unhooks.clear()
        synchronized(removedAtByKey) { removedAtByKey.clear() }
        synchronized(knownReactionModels) { knownReactionModels.clear() }
        mainHandler.post {
            lastReactionArgs.clear()
            lastReactionMessageIds.clear()
            for (popup in removalPopups.values.toList()) {
                runCatching { popup.dismiss() }
            }
            removalPopups.clear()

            for (pill in bound.keys.toList()) {
                applyStyle(pill, false)
            }
            bound.clear()
            originalStyles.clear()
            originalLongClickListeners.clear()
            wrappedLongClickListeners.clear()
        }
    }

    private fun isRemoved(key: String): Boolean =
        synchronized(removedAtByKey) { key in removedAtByKey }

    /**
     * Remember Discord's original listener before the reaction pill is marked red.
     * applyStyle can then wrap it without losing Discord's own long-press behavior.
     */
    private fun hookLongClickListeners() {
        val setter = View::class.java.getDeclaredMethod(
            "setOnLongClickListener",
            View.OnLongClickListener::class.java,
        )
        unhooks += setter.hook(before = { param ->
            if (internalListenerUpdate.get() != true) {
                val view = param.thisObject as? View ?: return@hook
                originalLongClickListeners[view] =
                    param.args.getOrNull(0) as? View.OnLongClickListener
            }
        })
    }

    private fun setManagedLongClickListener(
        view: View,
        listener: View.OnLongClickListener?,
    ) {
        internalListenerUpdate.set(true)
        try {
            view.setOnLongClickListener(listener)
        } finally {
            internalListenerUpdate.remove()
        }
    }

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
        setReactionsMethod = setReactions

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

        unhooks += setReactions.hook(
            before = { param ->
                val originalReactions = param.args.getOrNull(0) as? List<*> ?: return@hook
                val messageId = bindingMessageId.get() ?: return@hook
                val flexbox = param.thisObject as? ViewGroup
                if (flexbox != null && internalRebind.get() != true) {
                    lastReactionArgs[flexbox] = param.args.clone()
                    lastReactionMessageIds[flexbox] = messageId
                }
                val currentKeys = LinkedHashSet<String>()

                // Cache each full concrete model while it is still in Discord's
                // message reaction list. We can then clone it as a local 1-count row
                // if the server removes the final reaction.
                for (reaction in originalReactions) {
                    if (reaction == null) continue
                    val emoji = runCatching { getEmoji.invoke(reaction) }.getOrNull() ?: continue
                    val emojiPart = emojiKey(emoji) ?: continue
                    val count = runCatching { getCount.invoke(reaction) as Int }.getOrDefault(0)
                    val burstCount = runCatching { getBurstCount.invoke(reaction) as Int }.getOrDefault(0)

                    if (count > 0) {
                        val key = reactionKey(messageId, emojiPart, 0)
                        currentKeys.add(key)
                        synchronized(knownReactionModels) {
                            knownReactionModels[key] = reaction
                        }
                    }
                    if (burstCount > 0) {
                        val key = reactionKey(messageId, emojiPart, 1)
                        currentKeys.add(key)
                        synchronized(knownReactionModels) {
                            knownReactionModels[key] = reaction
                        }
                    }
                }

                val missingKeys = synchronized(removedAtByKey) {
                    removedAtByKey.keys.filter { key ->
                        key.startsWith("$messageId|") && key !in currentKeys
                    }
                }
                if (missingKeys.isNotEmpty()) {
                    val synthetic = missingKeys.mapNotNull { key ->
                        val model = synchronized(knownReactionModels) {
                            knownReactionModels[key]
                        } ?: return@mapNotNull null
                        val burst = key.substringAfterLast('|') == "1"
                        createSyntheticReaction(model, getEmoji, burst)
                    }

                    if (synthetic.isNotEmpty()) {
                        // The server-side count can be zero, but the client keeps a
                        // local red row with me=false so it can be tapped to react again.
                        param.args[0] = ArrayList<Any?>(originalReactions.size + synthetic.size).apply {
                            addAll(originalReactions)
                            addAll(synthetic)
                        }
                        XposedBridge.log(
                            "Reaction Logs: retained ${synthetic.size} removed reaction row(s) for message $messageId",
                        )
                    }
                }
            },
            after = { param ->
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
            },
        )
    }

    /**
     * Re-apply the last real reaction list after a Flux removal is recorded.
     * This closes the race where Discord renders its zero-count update before
     * setRemoved reaches the native hook. The setReactions hook then appends the
     * synthetic red row using the cached concrete model.
     */
    private fun rebindMessages(changedKeys: Set<String>) {
        if (changedKeys.isEmpty()) return
        val method = setReactionsMethod ?: return
        val affectedIds = changedKeys.map { it.substringBefore('|') }.toSet()

        for ((flexbox, messageId) in lastReactionMessageIds.entries.toList()) {
            if (messageId !in affectedIds || !flexbox.isAttachedToWindow) continue
            val args = lastReactionArgs[flexbox]?.clone() ?: continue

            try {
                internalRebind.set(true)
                bindingMessageId.set(messageId)
                method.invoke(flexbox, *args)
            } catch (error: Throwable) {
                XposedBridge.log("Reaction Logs: failed to refresh retained reaction row: $error")
            } finally {
                bindingMessageId.remove()
                internalRebind.remove()
            }
        }
    }

    /**
     * Clone Discord's real MessageReaction model so the RecyclerView receives a
     * valid ItemDiffableType with a stable item ID. A proxy is not sufficient here:
     * RecyclerView's diffing and ReactionView both rely on the model defaults.
     */
    private fun createSyntheticReaction(
        model: Any,
        getEmoji: Method,
        burst: Boolean,
    ): Any? {
        return runCatching {
            val methods = model.javaClass.methods
            val copy = methods.firstOrNull { it.name == "copy" && it.parameterCount == 6 }
                ?: error("Reaction model copy method not found on ${model.javaClass.name}")
            val emoji = getEmoji.invoke(model)
            val themedColors = methods.firstOrNull {
                it.name == "getThemedBurstColors" && it.parameterCount == 0
            }?.invoke(model)

            copy.invoke(
                model,
                if (burst) 0 else 1,
                false,
                false,
                emoji,
                if (burst) 1 else 0,
                themedColors,
            )
        }.onFailure {
            XposedBridge.log("Reaction Logs: could not retain removed reaction model: $it")
        }.getOrNull()
    }

    private fun applyStyle(view: LinearLayout, removed: Boolean) {
        val count = countText(view)
        val original = originalStyles.getOrPut(view) {
            OriginalStyle(view.backgroundTintList, count?.textColors)
        }

        if (removed) {
            view.backgroundTintList = ColorStateList.valueOf(REMOVED_TINT)
            count?.setTextColor(REMOVED_RED)
            installRemovalLongClick(view, bound[view])
        } else {
            view.backgroundTintList = original.backgroundTint
            original.countColors?.let { count?.setTextColor(it) }
            restoreOriginalLongClick(view)
        }
    }

    private fun installRemovalLongClick(view: View, key: String?) {
        if (key.isNullOrBlank()) return
        wrappedLongClickListeners[view] = true
        setManagedLongClickListener(view, View.OnLongClickListener { target ->
            // Show the saved log beside the red reaction without opening a competing sheet.
            showRemovalPopup(target, key)
            true
        })
    }

    private fun restoreOriginalLongClick(view: View) {
        val wasWrapped = wrappedLongClickListeners.remove(view) != null
        if (!wasWrapped) return
        setManagedLongClickListener(view, originalLongClickListeners[view])
    }

    private fun showRemovalPopup(anchor: View, key: String) {
        val message = removalText(key) ?: return
        val density = anchor.resources.displayMetrics.density
        val label = TextView(anchor.context).apply {
            text = message
            setTextColor(REMOVED_RED)
            setTextSize(11.5f)
            includeFontPadding = false
            maxWidth = (240f * density).toInt()
            setPadding(
                (10f * density).toInt(),
                (7f * density).toInt(),
                (10f * density).toInt(),
                (7f * density).toInt(),
            )
            background = GradientDrawable().apply {
                setColor(POPUP_BACKGROUND)
                cornerRadius = 8f * density
            }
        }

        val popup = PopupWindow(
            label,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 6f * density
        }

        removalPopups.remove(anchor)?.let { runCatching { it.dismiss() } }
        removalPopups[anchor] = popup
        popup.setOnDismissListener {
            if (removalPopups[anchor] === popup) removalPopups.remove(anchor)
        }

        runCatching {
            popup.showAsDropDown(anchor, anchor.width, -anchor.height)
        }.onFailure {
            removalPopups.remove(anchor)
            XposedBridge.log("Reaction Logs: could not show removal popup: $it")
        }

        mainHandler.postDelayed({
            if (popup.isShowing) popup.dismiss()
        }, 4_500L)
    }

    private fun removalText(key: String): String? {
        val entry = synchronized(removedAtByKey) { removedAtByKey[key] } ?: return null
        val lines = mutableListOf("removed at ${formatLocalTime(entry.removedAt)}")
        val names = entry.users.map { it.name }.filter { it.isNotBlank() }.distinct()

        when (names.size) {
            0 -> Unit
            1 -> lines += "reaction user: ${names[0]}"
            else -> {
                lines += "reaction users: ${names.take(4).joinToString(", ")}"
                if (names.size > 4) lines += "and ${names.size - 4} more"
            }
        }

        return lines.joinToString("\n")
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
