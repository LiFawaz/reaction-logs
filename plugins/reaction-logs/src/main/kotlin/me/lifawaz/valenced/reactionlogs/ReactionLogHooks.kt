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
private const val REACTION_MESSAGE_EXTRA = "com.discord.intent.extra.EXTRA_MESSAGE_ID"
private const val REACTION_EMOJI_ITEM_PREFIX = "ReactionEmojiItem(reaction="

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
    private val retainedPillKeys = LinkedHashSet<String>()
    private val retainedPillViews = WeakHashMap<ViewGroup, LinkedHashMap<String, LinearLayout>>()
    private val detailLogKeys = LinkedHashSet<String>()
    // Keep real pill views per Flexbox so one message cannot steal another message's pill.
    private val boundPills = WeakHashMap<LinearLayout, String>()
    private val originalPillStyles = WeakHashMap<LinearLayout, OriginalPillStyle>()
    private val userRows = WeakHashMap<View, UserRowBinding>()
    private val originalUserTextColors = WeakHashMap<TextView, ColorStateList>()
    private val originalUserRowTints = WeakHashMap<View, ColorStateList?>()
    private val originalAvatarForegrounds = WeakHashMap<View, Drawable?>()
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

    /** Updates only per-user removal records and timestamps. */
    fun setRemovalLogs(json: String) {
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
            if (users.isNotEmpty()) parsed[key] = users
        }

        synchronized(removedUsersByKey) {
            removedUsersByKey.clear()
            removedUsersByKey.putAll(parsed)
        }

        mainHandler.post {
            refreshUserRows()
        }
    }

    /** Updates only the keys whose rendered red reaction pills must be retained. */
    fun setRetainedPills(json: String) {
        val parsed = LinkedHashSet<String>()
        val array = JSONArray(json)
        for (index in 0 until array.length()) {
            val key = array.optString(index).takeIf { it.isNotBlank() } ?: continue
            parsed.add(key)
        }

        synchronized(retainedPillKeys) {
            retainedPillKeys.clear()
            retainedPillKeys.addAll(parsed)
        }

        mainHandler.post {
            // Drop stale cached references without detaching a view Discord may have
            // already rebound as a fresh reaction after an add event.
            synchronized(retainedPillViews) {
                for ((flexbox, views) in retainedPillViews.entries.toList()) {
                    for ((key, view) in views.toMap()) {
                        if (key !in parsed) {
                            views.remove(key)
                            boundPills.remove(view)
                            applyPillStyle(view, false)
                        }
                    }
                    if (views.isEmpty()) retainedPillViews.remove(flexbox)
                }
            }
            for ((pill, key) in boundPills.entries.toList()) {
                applyPillStyle(pill, isRemoved(key))
            }
        }
    }

    fun clear() {
        synchronized(removedUsersByKey) { removedUsersByKey.clear() }
        synchronized(retainedPillKeys) { retainedPillKeys.clear() }

        mainHandler.post {
            for ((flexbox, views) in retainedPillViews.entries.toList()) {
                for ((_, view) in views) {
                    if (view.parent === flexbox) flexbox.removeView(view)
                    boundPills.remove(view)
                    applyPillStyle(view, false)
                }
            }
            retainedPillViews.clear()
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
        synchronized(retainedPillKeys) { retainedPillKeys.clear() }
        detailsMessageId = null
        detailsEmojiPart = null

        mainHandler.post {
            for ((flexbox, views) in retainedPillViews.entries.toList()) {
                for ((_, view) in views) {
                    if (view.parent === flexbox) flexbox.removeView(view)
                    boundPills.remove(view)
                    applyPillStyle(view, false)
                }
            }
            retainedPillViews.clear()
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
        synchronized(retainedPillKeys) { key in retainedPillKeys }

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

        // The message ID is scoped to the adapter bind only. The Flexbox cache below
        // is also keyed by the actual ViewGroup, so image messages and recycled rows
        // cannot accidentally reuse another message's retained pill.
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
                val flexbox = param.thisObject as? ViewGroup ?: return@hook
                val messageId = bindingMessageId.get()
                val prefix = messageId?.let { "$it|" }

                synchronized(retainedPillViews) {
                    val cached = retainedPillViews.getOrPut(flexbox) { LinkedHashMap() }

                    // A ViewHolder may be recycled for another message. Discard only
                    // the old cache entries for that Flexbox before collecting new ones.
                    if (prefix != null) {
                        for ((oldKey, oldView) in cached.toMap()) {
                            if (!oldKey.startsWith(prefix)) {
                                cached.remove(oldKey)
                                boundPills.remove(oldView)
                                applyPillStyle(oldView, false)
                            }
                        }
                    }

                    for (index in 0 until flexbox.childCount) {
                        val pillView = flexbox.getChildAt(index) as? LinearLayout ?: continue
                        val key = boundPills[pillView] ?: continue
                        if (isRemoved(key) && (prefix == null || key.startsWith(prefix))) {
                            cached[key] = pillView
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

                    if (pill == null || pill.count <= 0 || pill.emoji == null) {
                        val oldKey = boundPills[pillView]
                        if (oldKey != null && isRemoved(oldKey)) {
                            applyPillStyle(pillView, true)
                        } else {
                            boundPills.remove(pillView)
                            applyPillStyle(pillView, false)
                        }
                        continue
                    }

                    val emoji = emojiKey(pill.emoji)
                    if (emoji.isNullOrBlank()) {
                        boundPills.remove(pillView)
                        applyPillStyle(pillView, false)
                        continue
                    }

                    val key = messageId?.let {
                        reactionKey(it, emoji, if (pill.burst) 1 else 0)
                    } ?: boundPills[pillView]

                    if (key == null) {
                        boundPills.remove(pillView)
                        applyPillStyle(pillView, false)
                        continue
                    }

                    currentKeys.add(key)
                    boundPills[pillView] = key
                    applyPillStyle(pillView, isRemoved(key))
                }

                val prefix = messageId?.let { "$it|" }
                val retained = synchronized(retainedPillViews) {
                    retainedPillViews[flexbox]
                        ?.entries
                        ?.filter { (key, _) ->
                            isRemoved(key) && (prefix == null || key.startsWith(prefix))
                        }
                        ?.map { it.key to it.value }
                        .orEmpty()
                }

                for ((key, pillView) in retained) {
                    if (key in currentKeys || !isRemoved(key)) continue
                    if (pillView.parent !== flexbox) {
                        (pillView.parent as? ViewGroup)?.removeView(pillView)
                        runCatching { flexbox.addView(pillView) }.onFailure {
                            XposedBridge.log("Reaction Logs: could not reattach retained reaction pill: $it")
                        }
                    }
                    boundPills[pillView] = key
                    countText(pillView)?.text = "0"
                    applyPillStyle(pillView, true)
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
            detailsEmojiPart = null
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
        val selectedField = instanceFields(emojiItemClass).firstOrNull {
            it.name == "isSelected" && it.type == Boolean::class.javaPrimitiveType
        }?.apply { isAccessible = true }
        val selectedGetter = emojiItemClass.methods.firstOrNull {
            (it.name == "isSelected" || it.name == "getIsSelected") &&
                it.parameterCount == 0 &&
                (it.returnType == Boolean::class.javaPrimitiveType || it.returnType == Boolean::class.java)
        }

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
                val selected = runCatching {
                    selectedField?.getBoolean(data)
                        ?: (selectedGetter?.invoke(data) as? Boolean)
                        ?: false
                }.getOrDefault(false)

                if (selected) {
                    val selectedReaction = runCatching { reactionField.get(data) }.getOrNull()
                    val selectedEmoji = selectedReaction?.let {
                        runCatching { emojiField.get(it) }.getOrNull()
                    }
                    val selectedKey = selectedEmoji?.let { emojiKey(it) }
                    if (!selectedKey.isNullOrBlank()) {
                        detailsEmojiPart = selectedKey
                        mainHandler.post { refreshUserRows() }
                        detailLogOnce("selected-emoji-bound", "selected reaction emoji row resolved as $selectedKey")
                    }
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
