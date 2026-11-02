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
private const val MANAGE_REACTIONS_MODEL_PROVIDER =
    "com.discord.widgets.chat.managereactions.ManageReactionsModelProvider"
private const val MG_RECYCLER_VIEW_HOLDER =
    "com.discord.utilities.mg_recycler.MGRecyclerViewHolder"
private const val REACTION_MESSAGE_EXTRA = "com.discord.intent.extra.EXTRA_MESSAGE_ID"
private const val REACTION_EMOJI_ITEM_PREFIX = "ReactionEmojiItem(reaction="
private const val REACTION_EMOJI_CONTAINER_ID = "manage_reactions_emoji_container"
private const val REACTION_EMOJI_VIEW_ID = "manage_reactions_emoji_emoji_textview"

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
    private val removedAtByKey = LinkedHashMap<String, Long>()
    private val detailKeys = WeakHashMap<ViewGroup, String>()
    private val detailLabels = WeakHashMap<ViewGroup, TextView>()
    private val detailLogKeys = LinkedHashSet<String>()
    @Volatile private var use24HourClock = false
    // Keep the actual rendered pill view when Discord drops its final reaction model.
    private val retainedPillViews = LinkedHashMap<String, LinearLayout>()
    private val boundPills = WeakHashMap<LinearLayout, String>()
    private val originalPillStyles = WeakHashMap<LinearLayout, OriginalPillStyle>()
    private val userRows = WeakHashMap<View, UserRowBinding>()
    private val originalUserTextColors = WeakHashMap<TextView, ColorStateList>()
    private val originalUserRowTints = WeakHashMap<View, ColorStateList?>()
    @Volatile private var detailsMessageId: String? = null
    @Volatile private var detailsEmojiPart: String? = null
    private val bindingMessageId = ThreadLocal<String?>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val unhooks = mutableListOf<XC_MethodHook.Unhook>()

    init {
        hookReactionPills(dexFinder)
        runCatching { hookReactionUserRows(dexFinder) }.onFailure {
            XposedBridge.log("Reaction Logs: reaction-user row hook unavailable: $it")
        }
        runCatching { hookReactionDetails(dexFinder) }.onFailure {
            XposedBridge.log("Reaction Logs: reaction-details timestamp hook unavailable: $it")
        }
    }

    fun setRemoved(json: String) {
        val parsed = LinkedHashMap<String, Set<String>>()
        val parsedTimes = LinkedHashMap<String, Long>()
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
            parsedTimes[key] = item.optLong("removedAt", 0L)
        }

        synchronized(removedUsersByKey) {
            removedUsersByKey.clear()
            removedUsersByKey.putAll(parsed)
        }
        synchronized(removedAtByKey) {
            removedAtByKey.clear()
            removedAtByKey.putAll(parsedTimes)
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
            for ((container, key) in detailKeys.entries.toList()) {
                applyDetailsTimestamp(container, key)
            }
        }
    }

    fun clear() {
        synchronized(removedUsersByKey) { removedUsersByKey.clear() }
        synchronized(removedAtByKey) { removedAtByKey.clear() }
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
            for ((container, key) in detailKeys.entries.toList()) {
                applyDetailsTimestamp(container, key)
            }
        }
    }

    fun dispose() {
        unhooks.forEach { it.unhook() }
        unhooks.clear()
        synchronized(removedUsersByKey) { removedUsersByKey.clear() }
        synchronized(removedAtByKey) { removedAtByKey.clear() }
        val retained = synchronized(retainedPillViews) {
            retainedPillViews.values.toList().also { retainedPillViews.clear() }
        }
        detailsMessageId = null
        detailsEmojiPart = null
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
            for ((container, label) in detailLabels.entries.toList()) {
                if (label.parent === container) container.removeView(label)
            }
            boundPills.clear()
            originalPillStyles.clear()
            userRows.clear()
            originalUserTextColors.clear()
            originalUserRowTints.clear()
            detailKeys.clear()
            detailLabels.clear()
        }
    }

    private fun isRemoved(key: String): Boolean =
        synchronized(removedUsersByKey) { key in removedUsersByKey }

    private fun removedUsersForReaction(messageId: String, emojiPart: String): Set<String> =
        synchronized(removedUsersByKey) {
            val prefix = "$messageId|$emojiPart|"
            removedUsersByKey.entries
                .asSequence()
                .filter { it.key.startsWith(prefix) }
                .flatMap { it.value.asSequence() }
                .toSet()
        }

    private fun isRemovedUser(messageId: String, userId: String): Boolean {
        val emojiPart = detailsEmojiPart ?: return false
        return userId in removedUsersForReaction(messageId, emojiPart)
    }

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
                    countText(pillView)?.text = "0"
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
        val container = reactionEmojiContainer(itemView) ?: run {
            detailLogOnce(
                "emoji-container-missing",
                "row binder ran, but the emoji TextView/container was not found; itemView=${itemView.javaClass.name}",
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

        detailKeys[container] = key
        detailLogOnce(
            "row-bound",
            "popup emoji row bound; emoji=$emojiPart; messageId=${messageId ?: "unavailable"}; " +
                "container=${container.javaClass.name}",
        )
        applyDetailsTimestamp(container, key)
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
                val messageIds = matches.map { it.key.substringBefore('|') }.distinct()
                if (messageIds.size == 1) matches.maxOfOrNull { it.value } else null
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

private fun reactionKey(messageId: String, emojiKey: String, type: Int) =
    "$messageId|$emojiKey|$type"

private inline fun Method.hook(
    crossinline before: (XC_MethodHook.MethodHookParam) -> Unit = {},
    crossinline after: (XC_MethodHook.MethodHookParam) -> Unit = {},
): XC_MethodHook.Unhook = XposedBridge.hookMethod(this, object : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) = before(param)
    override fun afterHookedMethod(param: MethodHookParam) = after(param)
})
