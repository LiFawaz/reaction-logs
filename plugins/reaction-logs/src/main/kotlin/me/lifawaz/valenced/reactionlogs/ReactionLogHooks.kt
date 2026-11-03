package me.lifawaz.valenced.reactionlogs

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
    // Keep the actual rendered pill view when Discord drops its final reaction model.
    private val retainedPillViews = LinkedHashMap<String, LinearLayout>()
    private val boundPills = WeakHashMap<LinearLayout, String>()
    private val originalPillStyles = WeakHashMap<LinearLayout, OriginalPillStyle>()
    private val userRows = WeakHashMap<View, UserRowBinding>()
    private val originalUserTextColors = WeakHashMap<TextView, ColorStateList>()
    private val originalUserRowTints = WeakHashMap<View, ColorStateList?>()
    private val originalAvatarForegrounds = WeakHashMap<View, Drawable?>()
    @Volatile private var detailsMessageId: String? = null
    @Volatile private var detailsEmojiKey: String? = null
    private val bindingMessageId = ThreadLocal<String?>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val unhooks = mutableListOf<XC_MethodHook.Unhook>()
    private val detailLogKeys = LinkedHashSet<String>()

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
        val staleRetainedViews = synchronized(retainedPillViews) {
            val stale = retainedPillViews
                .filterKeys { it !in parsed.keys }
                .values
                .toList()
            retainedPillViews.keys.retainAll(parsed.keys)
            stale
        }

        mainHandler.post {
            // Remove an old retained pill when the same emoji is added again.
            // Discord can then render the newly added reaction as a fresh pill.
            staleRetainedViews.forEach { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                boundPills.remove(view)
                applyPillStyle(view, false)
            }
            for ((pill, key) in boundPills.entries.toList()) {
                applyPillStyle(pill, isRemoved(key))
            }
            refreshUserRows()
        }
    }

    fun clear() {
        synchronized(removedUsersByKey) { removedUsersByKey.clear() }
        val retained = synchronized(retainedPillViews) {
            retainedPillViews.values.toList().also { retainedPillViews.clear() }
        }
        mainHandler.post {
            retained.forEach { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                boundPills.remove(view)
                applyPillStyle(view, false)
            }
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
        val retained = synchronized(retainedPillViews) {
            retainedPillViews.values.toList().also { retainedPillViews.clear() }
        }
        detailsMessageId = null
        detailsEmojiKey = null
        mainHandler.post {
            retained.forEach { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                boundPills.remove(view)
                applyPillStyle(view, false)
            }
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
            originalAvatarForegrounds.clear()
        }
    }

    private fun isRemoved(key: String): Boolean =
        synchronized(removedUsersByKey) { key in removedUsersByKey }

    private fun removedUsersForMessage(messageId: String, emojiKey: String?): Set<String> {
        // Never highlight a user based on a different emoji. If Discord does not
        // expose the popup's selected emoji, leave the user list unchanged.
        if (emojiKey.isNullOrBlank()) return emptySet()
        return synchronized(removedUsersByKey) {
            val prefix = "$messageId|$emojiKey|"
            removedUsersByKey.entries
                .asSequence()
                .filter { it.key.startsWith(prefix) }
                .flatMap { it.value.asSequence() }
                .toSet()
        }
    }

    private fun isRemovedUser(messageId: String, userId: String): Boolean =
        userId in removedUsersForMessage(messageId, detailsEmojiKey)

    /**
     * Keep removed reaction pills visible by retaining and reattaching their real
     * rendered views after Discord rebuilds the reaction row.
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
                val flexbox = param.thisObject as? ViewGroup ?: return@hook

                // Save the real pill view before Discord rebuilds this row. A proxy
                // reaction model is not reliable because Discord may filter it out.
                for (index in 0 until flexbox.childCount) {
                    val pillView = flexbox.getChildAt(index) as? LinearLayout ?: continue
                    val key = boundPills[pillView] ?: continue
                    if (key.startsWith("$messageId|") && isRemoved(key)) {
                        synchronized(retainedPillViews) {
                            retainedPillViews[key] = pillView
                        }
                    }
                }
            },
            after = { param ->
            val flexbox = param.thisObject as ViewGroup
            val reactions = param.args.getOrNull(0) as? List<*> ?: emptyList<Any>()
            val messageId = bindingMessageId.get()
            val reactionPills = pills(reactions, getCount, getBurstCount, getEmoji)
            val currentKeys = LinkedHashSet<String>()

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
                currentKeys.add(key)
                boundPills[pillView] = key
                applyPillStyle(pillView, isRemoved(key))
            }

            // Reattach any removed pill whose reaction model disappeared from
            // Discord's list. It remains visible and red until the matching add
            // event clears this key from the active-removal state.
            if (messageId != null) {
                val prefix = "$messageId|"
                val retained = synchronized(retainedPillViews) {
                    retainedPillViews.entries
                        .filter { it.key.startsWith(prefix) && isRemoved(it.key) }
                        .map { it.key to it.value }
                }

                for ((key, pillView) in retained) {
                    if (key in currentKeys) continue
                    if (pillView.parent !== flexbox) {
                        (pillView.parent as? ViewGroup)?.removeView(pillView)
                        runCatching { flexbox.addView(pillView) }.onFailure {
                            XposedBridge.log("Reaction Logs: could not retain removed pill: $it")
                        }
                    }
                    boundPills[pillView] = key
                    applyPillStyle(pillView, true)
                }
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
        val emojiClass = dexFinder.findClass {
            matcher { addUsingString("MessageReactionEmoji(name=") }
        } ?: error("MessageReactionEmoji model class not found")

        // Some Discord versions call the generic RecyclerView binder, while
        // others dispatch through a typed onConfigure(int, UserRowModel) method.
        // Discover the concrete row-model types from the provider's live model and
        // hook their typed binders as well as the generic fallback.
        val hookedTypedBinders = LinkedHashSet<Method>()
        unhooks += getModel.hook(
            before = { param ->
                detailsMessageId = runCatching {
                    messageIdGetter.invoke(param.thisObject)?.toString()
                }.getOrNull()
            },
            after = { param ->
                val model = param.result ?: param.thisObject
                val selectedEmoji = findSelectedEmojiKey(model, emojiClass)
                    ?: findSelectedEmojiKey(param.thisObject, emojiClass)
                detailsEmojiPart = selectedEmoji
                detailLogOnce(
                    "selected-reaction",
                    if (selectedEmoji == null) {
                        "selected reaction emoji was not found in Manage Reactions model"
                    } else {
                        "selected reaction emoji resolved as $selectedEmoji"
                    },
                )
                refreshUserRows()
                val candidates = collectUserRowDataClasses(model)
                detailLogOnce(
                    "user-model-discovery",
                    "Manage Reactions model exposed ${candidates.size} possible user-row model types",
                )

                for (type in candidates) {
                    val configure = runCatching {
                        dexFinder.findMethod {
                            matcher {
                                name("onConfigure")
                                paramCount(2)
                                paramTypes("int", type.name)
                            }
                        }
                    }.getOrNull() ?: continue

                    val shouldHook = synchronized(hookedTypedBinders) {
                        hookedTypedBinders.add(configure)
                    }
                    if (!shouldHook) continue

                    runCatching {
                        configure.isAccessible = true
                        unhooks += configure.hook(after = { rowParam ->
                            val data = rowParam.args.getOrNull(1) ?: return@hook
                            if (!type.isInstance(data)) return@hook
                            bindUserRowFromHolder(rowParam.thisObject, data)
                        })
                        detailLogOnce(
                            "typed-user-binder-${type.name}",
                            "hooked typed reaction-user binder for ${type.name}",
                        )
                    }.onFailure {
                        XposedBridge.log(
                            "Reaction Logs: could not hook typed user-row binder ${type.name}: $it",
                        )
                    }
                }
            },
        )

        // Keep a generic fallback for app versions that route through the base holder.
        val holderBase = dexFinder.classLoader.loadClass(MG_RECYCLER_VIEW_HOLDER)
        val genericConfigure = generateSequence(holderBase) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull {
                it.name == "onConfigure" &&
                    it.parameterCount == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Any::class.java
            }

        if (genericConfigure != null) {
            runCatching {
                genericConfigure.isAccessible = true
                unhooks += genericConfigure.hook(after = { param ->
                    val messageId = detailsMessageId ?: return@hook
                    val holder = param.thisObject
                    val data = param.args.getOrNull(1) ?: return@hook
                    val dataName = data.javaClass.name
                    val userId = extractUserId(data) ?: return@hook
                    val isUserRow =
                        holder.javaClass.name.contains("ManageReactions", ignoreCase = true) ||
                        dataName.contains("User", ignoreCase = true) ||
                        dataName.contains("Member", ignoreCase = true)
                    if (!isUserRow) return@hook
                    val itemView = holderItemView(holder) ?: return@hook
                    detailLogOnce(
                        "generic-user-row-bound",
                        "generic reaction-user binder matched ${dataName.substringAfterLast('.')} for user $userId",
                    )
                    bindUserRow(itemView, messageId, userId)
                })
            }.onFailure {
                XposedBridge.log("Reaction Logs: generic user-row binder unavailable: $it")
            }
        }

        detailLogOnce("user-hook-installed", "reaction-user row hooks installed")
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

    private fun findSelectedEmojiKey(root: Any?, emojiClass: Class<*>): String? {
        val visited = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Any, Boolean>(),
        )

        fun visit(value: Any?, depth: Int): String? {
            if (value == null || depth > 4 || !visited.add(value)) return null
            if (emojiClass.isInstance(value)) return emojiKey(value)

            val type = value.javaClass
            if (
                type.isPrimitive || type.isEnum || value is String ||
                type.name.startsWith("android.") || type.name.startsWith("java.")
            ) return null

            val getterNames = setOf(
                "getSelectedReaction",
                "getReaction",
                "getSelectedEmoji",
                "getEmoji",
            )
            val getters = type.methods
                .filter { it.parameterCount == 0 && it.name in getterNames }
                .sortedBy {
                    when (it.name) {
                        "getSelectedReaction" -> 0
                        "getReaction" -> 1
                        "getSelectedEmoji" -> 2
                        else -> 3
                    }
                }
            for (getter in getters) {
                val nested = runCatching { getter.invoke(value) }.getOrNull()
                visit(nested, depth + 1)?.let { return it }
            }

            val fields = instanceFields(type)
                .filter { field ->
                    val name = field.name.lowercase()
                    name.contains("selected") || name == "reaction" ||
                        name.contains("reaction") || name == "emoji" ||
                        name.contains("emoji")
                }
                .sortedBy {
                    val name = it.name.lowercase()
                    when {
                        name.contains("selected") && name.contains("reaction") -> 0
                        name.contains("selected") && name.contains("emoji") -> 1
                        name == "reaction" -> 2
                        name.contains("reaction") -> 3
                        else -> 4
                    }
                }

            for (field in fields) {
                val nested = runCatching {
                    field.isAccessible = true
                    field.get(value)
                }.getOrNull() ?: continue
                if (nested is Iterable<*> || nested is Map<*, *> || nested is Array<*>) {
                    if (!field.name.contains("selected", ignoreCase = true)) continue
                }
                visit(nested, depth + 1)?.let { return it }
            }
            return null
        }

        return visit(root, 0)
    }

    private fun collectUserRowDataClasses(root: Any?): LinkedHashSet<Class<*>> {
        val found = LinkedHashSet<Class<*>>()
        val visited = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Any, Boolean>(),
        )

        fun visit(value: Any?, depth: Int) {
            if (value == null || depth > 4 || !visited.add(value)) return

            when (value) {
                is Iterable<*> -> {
                    value.take(100).forEach { visit(it, depth + 1) }
                    return
                }
                is Map<*, *> -> {
                    value.values.take(100).forEach { visit(it, depth + 1) }
                    return
                }
                is Array<*> -> {
                    value.take(100).forEach { visit(it, depth + 1) }
                    return
                }
            }

            val type = value.javaClass
            if (type.isPrimitive || type.isEnum || type == String::class.java) return

            val lowerName = type.name.lowercase()
            val hasUserIdAccessor = type.methods.any {
                it.parameterCount == 0 && it.name in setOf("getUserId", "getUserID")
            }
            val hasUserField = instanceFields(type).any {
                val name = it.name.lowercase()
                name == "userid" || name == "memberid" ||
                    name == "user" || name == "member"
            }
            if (
                lowerName.contains("user") || lowerName.contains("member") ||
                hasUserIdAccessor || hasUserField
            ) {
                found.add(type)
            }

            if (depth == 4) return
            for (field in instanceFields(type).take(40)) {
                val fieldName = field.name.lowercase()
                val fieldType = field.type
                if (
                    fieldType.isPrimitive || fieldType.isEnum ||
                    fieldType == String::class.java || fieldType.name.startsWith("android.")
                ) {
                    continue
                }
                val nested = runCatching {
                    field.isAccessible = true
                    field.get(value)
                }.getOrNull() ?: continue
                val collectionLike =
                    nested is Iterable<*> || nested is Map<*, *> || nested is Array<*>
                if (
                    collectionLike || fieldName.contains("user") ||
                    fieldName.contains("member") || fieldName.contains("reaction") ||
                    fieldName.contains("item") || fieldName.contains("data")
                ) {
                    visit(nested, depth + 1)
                }
            }
        }

        visit(root, 0)
        return found
    }

    private fun bindUserRowFromHolder(holder: Any, data: Any) {
        val messageId = detailsMessageId ?: return
        val userId = extractUserId(data) ?: return
        val itemView = holderItemView(holder) ?: return
        bindUserRow(itemView, messageId, userId)
    }

    private fun detailLogOnce(key: String, message: String) {
        val shouldLog = synchronized(detailLogKeys) { detailLogKeys.add(key) }
        if (shouldLog) XposedBridge.log("Reaction Logs: $message")
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
        applyAvatarStyle(view, highlighted)
    }

    private fun applyAvatarStyle(row: View, highlighted: Boolean) {
        if (highlighted) {
            var avatar: View? = null
            visitViews(row) { child ->
                if (avatar == null && child !== row && isAvatarView(child)) avatar = child
            }
            val target = avatar ?: return
            if (!originalAvatarForegrounds.containsKey(target)) {
                val original = target.foreground
                originalAvatarForegrounds[target] = original
                val stroke = (2f * target.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                val ring = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(stroke, REMOVED_RED)
                }
                target.foreground = if (original == null) ring else LayerDrawable(arrayOf(original, ring))
            }
            return
        }

        visitViews(row) { child ->
            if (originalAvatarForegrounds.containsKey(child)) {
                child.foreground = originalAvatarForegrounds.remove(child)
            }
        }
    }

    private fun isAvatarView(view: View): Boolean {
        val className = view.javaClass.name.lowercase()
        if (className.contains("avatar") || className.contains("drawee")) return true
        if (view !is ImageView) return false
        val minSize = (20f * view.resources.displayMetrics.density).toInt()
        val width = if (view.width > 0) view.width else view.layoutParams?.width ?: 0
        val height = if (view.height > 0) view.height else view.layoutParams?.height ?: 0
        return (width <= 0 || width >= minSize) && (height <= 0 || height >= minSize)
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

private fun reactionKey(messageId: String, emojiKey: String, type: Int) =
    "$messageId|$emojiKey|$type"

private inline fun Method.hook(
    crossinline before: (XC_MethodHook.MethodHookParam) -> Unit = {},
    crossinline after: (XC_MethodHook.MethodHookParam) -> Unit = {},
): XC_MethodHook.Unhook = XposedBridge.hookMethod(this, object : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) = before(param)
    override fun afterHookedMethod(param: MethodHookParam) = after(param)
})
