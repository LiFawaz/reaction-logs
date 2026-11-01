package me.lifawaz.valenced.reactionlogs

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
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
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.WeakHashMap

private const val SHORTCUTS_FLEXBOX = "com.discord.reactions.ShortcutsFlexbox"

private val REMOVED_RED = Color.rgb(237, 66, 69)
private val REMOVED_TINT = Color.argb(48, 237, 66, 69)

internal class ReactionLogHooks(
    dexFinder: DexFinder,
    @Suppress("UNUSED_PARAMETER") bridge: ValencedBridge,
) {
    private data class Pill(val emoji: Any?, val burst: Boolean, val count: Int)

    private data class OriginalStyle(
        val backgroundTint: ColorStateList?,
        val countColors: ColorStateList?,
    )

    private val removedKeys = LinkedHashSet<String>()
    private val knownReactionModels = LinkedHashMap<String, Any>()
    private val lastReactionArgs = WeakHashMap<ViewGroup, Array<Any?>>()
    private val lastReactionMessageIds = WeakHashMap<ViewGroup, String>()
    private var setReactionsMethod: Method? = null
    private val internalRebind = ThreadLocal<Boolean>()
    private val bound = WeakHashMap<LinearLayout, String>()
    private val originalStyles = WeakHashMap<LinearLayout, OriginalStyle>()
    private val bindingMessageId = ThreadLocal<String?>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val unhooks = mutableListOf<XC_MethodHook.Unhook>()

    init {
        hookReactionPills(dexFinder)
    }

    fun setRemoved(json: String) {
        val parsed = LinkedHashSet<String>()
        val array = JSONArray(json)

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val key = item.optString("pillKey").takeIf { it.isNotBlank() } ?: continue
            parsed.add(key)
        }

        synchronized(removedKeys) {
            removedKeys.clear()
            removedKeys.addAll(parsed)
        }
        synchronized(knownReactionModels) {
            knownReactionModels.keys.retainAll(parsed)
        }

        mainHandler.post {
            for ((pill, key) in bound.entries.toList()) {
                applyStyle(pill, isRemoved(key))
            }
            rebindMessages(parsed)
        }
    }

    fun clear() {
        synchronized(removedKeys) { removedKeys.clear() }
        synchronized(knownReactionModels) { knownReactionModels.clear() }
        mainHandler.post {
            for (pill in bound.keys.toList()) {
                applyStyle(pill, false)
            }
        }
    }

    fun dispose() {
        unhooks.forEach { it.unhook() }
        unhooks.clear()
        synchronized(removedKeys) { removedKeys.clear() }
        synchronized(knownReactionModels) { knownReactionModels.clear() }
        mainHandler.post {
            lastReactionArgs.clear()
            lastReactionMessageIds.clear()
            for (pill in bound.keys.toList()) {
                applyStyle(pill, false)
            }
            bound.clear()
            originalStyles.clear()
        }
    }

    private fun isRemoved(key: String): Boolean =
        synchronized(removedKeys) { key in removedKeys }

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
                val index = param.args.getOrNull(1) as? Int
                val item = index?.let {
                    (adapterItems.get(param.thisObject) as List<*>?)?.getOrNull(it)
                }
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
                val flexbox = param.thisObject as? ViewGroup
                val messageId = bindingMessageId.get()
                    ?: flexbox?.let { lastReactionMessageIds[it] }
                    ?: return@hook

                if (flexbox != null) {
                    lastReactionMessageIds[flexbox] = messageId
                    if (internalRebind.get() != true) {
                        lastReactionArgs[flexbox] = param.args.clone()
                    }
                }

                val currentKeys = LinkedHashSet<String>()
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

                val missingKeys = synchronized(removedKeys) {
                    removedKeys.filter { key ->
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
                        param.args[0] = ArrayList<Any?>(originalReactions.size + synthetic.size).apply {
                            addAll(originalReactions)
                            addAll(synthetic)
                        }
                        XposedBridge.log(
                            "Reaction Logs: kept " + synthetic.size + " removed reaction row(s) for message " + messageId,
                        )
                    }
                }
            },
            after = { param ->
                val flexbox = param.thisObject as ViewGroup
                val reactions = param.args.getOrNull(0) as? List<*> ?: emptyList<Any>()
                val messageId = bindingMessageId.get() ?: lastReactionMessageIds[flexbox]
                if (messageId != null) lastReactionMessageIds[flexbox] = messageId
                val reactionPills = pills(reactions, getCount, getBurstCount, getEmoji)

                for (index in 0 until flexbox.childCount) {
                    val pillView = flexbox.getChildAt(index) as? LinearLayout ?: continue
                    val pill = reactionPills.getOrNull(index)

                    if (pill == null || messageId == null || pill.count == 0 || pill.emoji == null) {
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
        } else {
            view.backgroundTintList = original.backgroundTint
            original.countColors?.let { count?.setTextColor(it) }
        }
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
        val parsedId = ID_PATTERN.find(text)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() && it != "null" }
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
