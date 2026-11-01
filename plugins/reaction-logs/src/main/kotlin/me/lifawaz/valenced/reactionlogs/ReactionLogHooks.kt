package me.lifawaz.valenced.reactionlogs

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
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
import java.lang.reflect.Proxy
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.WeakHashMap

private const val SHORTCUTS_FLEXBOX = "com.discord.reactions.ShortcutsFlexbox"
private const val MANAGE_REACTIONS_MODEL_PROVIDER =
    "com.discord.widgets.chat.managereactions.ManageReactionsModelProvider"
private const val MG_RECYCLER_VIEW_HOLDER =
    "com.discord.utilities.mg_recycler.MGRecyclerViewHolder"

private val REMOVED_RED = Color.rgb(237, 66, 69)
private val REMOVED_TINT = Color.argb(48, 237, 66, 69)

internal class ReactionLogHooks(
    dexFinder: DexFinder,
    @Suppress("UNUSED_PARAMETER") bridge: ValencedBridge,
) {
    private data class Pill(val emoji: Any?, val burst: Boolean, val count: Int)
    private data class OriginalPillStyle(
        val backgroundTint: ColorStateList?,
        val countColors: ColorStateList?,
    )
    private data class UserRowBinding(val messageId: String, val userId: String)

    private val removedUsersByKey = LinkedHashMap<String, Set<String>>()
    // Keep the last non-zero model so Discord cannot remove a logged pill from the row.
    private val retainedReactionModels = LinkedHashMap<String, Any>()
    private val boundPills = WeakHashMap<LinearLayout, String>()
    private val originalPillStyles = WeakHashMap<LinearLayout, OriginalPillStyle>()
    private val userRows = WeakHashMap<View, UserRowBinding>()
    private val originalUserTextColors = WeakHashMap<TextView, ColorStateList>()
    private val originalUserRowTints = WeakHashMap<View, ColorStateList?>()
    @Volatile private var detailsMessageId: String? = null
    private val bindingMessageId = ThreadLocal<String?>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val unhooks = mutableListOf<XC_MethodHook.Unhook>()

    init {
        hookReactionPills(dexFinder)
        runCatching { hookReactionUserRows(dexFinder) }.onFailure {
            XposedBridge.log("Reaction Logs: reaction-user row hook unavailable: $it")
        }
    }

    fun setRemoved(json: String) {
        val parsed = LinkedHashMap<String, Set<String>>()
        val array = JSONArray(json)

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val key = item.optString("pillKey").takeIf { it.isNotBlank() } ?: continue
            val users = LinkedHashSet<String>()
            val userArray = item.optJSONArray("removedUsers")
            if (userArray != null) {
                for (userIndex in 0 until userArray.length()) {
                    val user = userArray.optJSONObject(userIndex) ?: continue
                    val id = user.optString("id").takeIf {
                        it.isNotBlank() && it != "null" && it != "*"
                    } ?: continue
                    users.add(id)
                }
            }
            parsed[key] = users
        }

        synchronized(removedUsersByKey) {
            removedUsersByKey.clear()
            removedUsersByKey.putAll(parsed)
        }
        synchronized(retainedReactionModels) {
            retainedReactionModels.keys.retainAll(parsed.keys)
        }

        mainHandler.post {
            for ((pill, key) in boundPills.entries.toList()) {
                applyPillStyle(pill, isRemoved(key))
            }
            refreshUserRows()
        }
    }

    fun clear() {
        synchronized(removedUsersByKey) { removedUsersByKey.clear() }
        synchronized(retainedReactionModels) { retainedReactionModels.clear() }
        mainHandler.post {
            for (pill in boundPills.keys.toList()) {
                applyPillStyle(pill, false)
            }
            for (row in userRows.keys.toList()) {
                applyUserRowStyle(row, false)
            }
        }
    }

    fun dispose() {
        unhooks.forEach { it.unhook() }
        unhooks.clear()
        synchronized(removedUsersByKey) { removedUsersByKey.clear() }
        synchronized(retainedReactionModels) { retainedReactionModels.clear() }
        detailsMessageId = null
        mainHandler.post {
            for (pill in boundPills.keys.toList()) {
                applyPillStyle(pill, false)
            }
            for (row in userRows.keys.toList()) {
                applyUserRowStyle(row, false)
            }
            boundPills.clear()
            originalPillStyles.clear()
            userRows.clear()
            originalUserTextColors.clear()
            originalUserRowTints.clear()
        }
    }

    private fun isRemoved(key: String): Boolean =
        synchronized(removedUsersByKey) { key in removedUsersByKey }

    private fun removedUsersForMessage(messageId: String): Set<String> =
        synchronized(removedUsersByKey) {
            removedUsersByKey.entries
                .asSequence()
                .filter { it.key.startsWith("$messageId|") }
                .flatMap { it.value.asSequence() }
                .toSet()
        }

    private fun isRemovedUser(messageId: String, userId: String): Boolean =
        userId in removedUsersForMessage(messageId)

    /**
     * Keep the original, simple reaction-pill behavior. We do not fabricate a
     * zero-count reaction model; Discord owns whether the emoji pill still exists.
     */
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
                val messageId = bindingMessageId.get() ?: return@hook
                val reactions = param.args.getOrNull(0) as? List<*> ?: return@hook
                val currentKeys = LinkedHashSet<String>()

                // Cache a renderable model while Discord still reports a positive count.
                for (reaction in reactions) {
                    if (reaction == null) continue
                    val count = getCount.invoke(reaction) as Int
                    val burstCount = getBurstCount.invoke(reaction) as Int
                    val emoji = getEmoji.invoke(reaction) ?: continue
                    val emojiName = emojiKey(emoji) ?: continue

                    if (count > 0) {
                        val key = reactionKey(messageId, emojiName, 0)
                        currentKeys.add(key)
                        synchronized(retainedReactionModels) {
                            retainedReactionModels[key] = retainedReaction(
                                reaction, reactionInterface, getCount, getBurstCount,
                                getEmoji, false, count,
                            )
                        }
                    }
                    if (burstCount > 0) {
                        val key = reactionKey(messageId, emojiName, 1)
                        currentKeys.add(key)
                        synchronized(retainedReactionModels) {
                            retainedReactionModels[key] = retainedReaction(
                                reaction, reactionInterface, getCount, getBurstCount,
                                getEmoji, true, burstCount,
                            )
                        }
                    }
                }

                val removedKeys = synchronized(removedUsersByKey) {
                    removedUsersByKey.keys.filter { it.startsWith("$messageId|") }
                }
                if (removedKeys.any { it !in currentKeys }) {
                    val augmented = reactions.toMutableList()
                    for (key in removedKeys) {
                        if (key in currentKeys) continue
                        val retained = synchronized(retainedReactionModels) {
                            retainedReactionModels[key]
                        } ?: continue
                        augmented.add(retained)
                    }
                    if (augmented.size != reactions.size) param.args[0] = augmented
                }
            },
            after = { param ->
            val flexbox = param.thisObject as ViewGroup
            val reactions = param.args.getOrNull(0) as? List<*> ?: emptyList<Any>()
            val messageId = bindingMessageId.get()
            val reactionPills = pills(reactions, getCount, getBurstCount, getEmoji)

            for (index in 0 until flexbox.childCount) {
                val pillView = flexbox.getChildAt(index) as? LinearLayout ?: continue
                val pill = reactionPills.getOrNull(index)

                if (pill == null || messageId == null || pill.count <= 0 || pill.emoji == null) {
                    boundPills.remove(pillView)
                    applyPillStyle(pillView, false)
                    continue
                }

                val emoji = emojiKey(pill.emoji)
                if (emoji.isNullOrBlank()) {
                    boundPills.remove(pillView)
                    applyPillStyle(pillView, false)
                    continue
                }

                val key = reactionKey(messageId, emoji, if (pill.burst) 1 else 0)
                boundPills[pillView] = key
                applyPillStyle(pillView, isRemoved(key))
            }
            },
        )
    }

    /**
     * When a reaction's user list is open, mark the row belonging to a user whose
     * reaction-removal event was recorded for that message.
     */
    private fun hookReactionUserRows(dexFinder: DexFinder) {
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

        val holderBase = dexFinder.classLoader.loadClass(MG_RECYCLER_VIEW_HOLDER)
        val configure = holderBase.declaredMethods.firstOrNull {
            it.name == "onConfigure" &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Any::class.java
        } ?: error("MGRecyclerViewHolder.onConfigure(int, Object) not found")
        configure.isAccessible = true

        unhooks += configure.hook(after = { param ->
            val messageId = detailsMessageId ?: return@hook
            val holder = param.thisObject
            val data = param.args.getOrNull(1) ?: return@hook
            val holderName = holder.javaClass.name
            val dataName = data.javaClass.name

            // Do not touch unrelated RecyclerViews. Manage Reactions has a dedicated
            // holder family; the data-class check is a fallback for renamed holders.
            if (
                !holderName.contains("ManageReactions", ignoreCase = true) &&
                !dataName.contains("User", ignoreCase = true) &&
                !dataName.contains("Member", ignoreCase = true)
            ) {
                return@hook
            }

            val itemView = holderItemView(holder) ?: return@hook
            val userId = extractUserId(data)
            if (userId == null) {
                XposedBridge.log("Reaction Logs: could not read user ID from reaction-detail row $dataName")
                return@hook
            }

            bindUserRow(itemView, messageId, userId)
        })

        XposedBridge.log("Reaction Logs: reaction-user row hook installed")
    }

    private fun bindUserRow(view: View, messageId: String, userId: String) {
        userRows[view] = UserRowBinding(messageId, userId)
        mainHandler.post {
            val binding = userRows[view] ?: return@post
            if (binding.messageId != messageId || binding.userId != userId) return@post
            applyUserRowStyle(view, isRemovedUser(messageId, userId))
        }
    }

    private fun refreshUserRows() {
        for ((view, binding) in userRows.entries.toList()) {
            applyUserRowStyle(view, isRemovedUser(binding.messageId, binding.userId))
        }
    }

    private fun applyUserRowStyle(view: View, highlighted: Boolean) {
        if (highlighted) {
            if (!originalUserRowTints.containsKey(view)) {
                originalUserRowTints[view] = view.backgroundTintList
            }
            view.backgroundTintList = ColorStateList.valueOf(REMOVED_TINT)
        } else if (originalUserRowTints.containsKey(view)) {
            view.backgroundTintList = originalUserRowTints.remove(view)
        }

        visitViews(view) { child ->
            if (child !is TextView) return@visitViews
            if (highlighted) {
                if (!originalUserTextColors.containsKey(child)) {
                    originalUserTextColors[child] = child.textColors
                }
                child.setTextColor(REMOVED_RED)
            } else if (originalUserTextColors.containsKey(child)) {
                child.setTextColor(originalUserTextColors.remove(child))
            }
        }
    }

    private fun visitViews(view: View, action: (View) -> Unit) {
        action(view)
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) {
            visitViews(view.getChildAt(index), action)
        }
    }

    private fun extractUserId(value: Any?, depth: Int = 0): String? {
        if (value == null || depth > 3) return null
        val type = value.javaClass
        val typeName = type.name.lowercase()

        val userIdMethod = type.methods.firstOrNull {
            it.name in setOf("getUserId", "getUserID") && it.parameterCount == 0
        }
        val directId = runCatching { userIdMethod?.invoke(value)?.toString() }.getOrNull()
        if (!directId.isNullOrBlank() && directId != "null") return directId

        if (typeName.contains("user") || typeName.contains("member")) {
            val idMethod = type.methods.firstOrNull {
                it.name == "getId" && it.parameterCount == 0
            }
            val id = runCatching { idMethod?.invoke(value)?.toString() }.getOrNull()
            if (!id.isNullOrBlank() && id != "null") return id
        }

        val nestedGetters = type.methods.filter {
            it.parameterCount == 0 && it.name in setOf("getUser", "getMember", "getUserModel")
        }
        for (getter in nestedGetters) {
            val nested = runCatching { getter.invoke(value) }.getOrNull()
            extractUserId(nested, depth + 1)?.let { return it }
        }

        var current: Class<*>? = type
        while (current != null) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                val fieldName = field.name.lowercase()
                if (!fieldName.contains("user") && !fieldName.contains("member")) continue
                val nested = runCatching {
                    field.isAccessible = true
                    field.get(value)
                }.getOrNull()
                if (nested is String && nested.isNotBlank() && fieldName.contains("userid")) {
                    return nested
                }
                extractUserId(nested, depth + 1)?.let { return it }
            }
            current = current.superclass
        }

        return null
    }

    private fun holderItemView(holder: Any): View? {
        runCatching {
            holder.javaClass.getField("itemView").get(holder) as? View
        }.getOrNull()?.let { return it }

        var current: Class<*>? = holder.javaClass
        while (current != null) {
            val field = runCatching { current.getDeclaredField("itemView") }.getOrNull()
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

    private fun applyPillStyle(view: LinearLayout, removed: Boolean) {
        val count = countText(view)
        val original = originalPillStyles.getOrPut(view) {
            OriginalPillStyle(view.backgroundTintList, count?.textColors)
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

private fun retainedReaction(
    original: Any,
    reactionInterface: Class<*>,
    getCount: Method,
    getBurstCount: Method,
    getEmoji: Method,
    burst: Boolean,
    count: Int,
): Any = Proxy.newProxyInstance(
    reactionInterface.classLoader,
    arrayOf(reactionInterface),
) { proxy, method, args ->
    when {
        method == getCount -> if (burst) 0 else count
        method == getBurstCount -> if (burst) count else 0
        method == getEmoji -> getEmoji.invoke(original)
        method.name == "toString" -> original.toString()
        method.name == "hashCode" -> System.identityHashCode(proxy)
        method.name == "equals" -> proxy === args?.getOrNull(0)
        else -> method.invoke(original, *(args ?: emptyArray()))
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
