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
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.text.Normalizer
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.WeakHashMap

private const val SHORTCUTS_FLEXBOX = "com.discord.reactions.ShortcutsFlexbox"
private const val MANAGE_REACTIONS_MODEL_PROVIDER =
    "com.discord.widgets.chat.managereactions.ManageReactionsModelProvider"
private const val MANAGE_REACTIONS_WIDGET =
    "com.discord.widgets.chat.managereactions.WidgetManageReactions"
private const val REACTION_USER_ITEM =
    "com.discord.widgets.chat.managereactions.ManageReactionsResultsAdapter$ReactionUserItem"
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
    private data class RemovalEntry(
        val id: String,
        val guildId: String?,
        val channelId: String,
        val messageId: String,
        val userId: String,
        val emojiKey: String,
        val reactionType: Int,
        val timestamp: Long?,
    )
    private data class ActiveEntry(
        val guildId: String?,
        val channelId: String,
        val messageId: String,
        val userId: String,
        val emojiKey: String,
        val reactionType: Int,
    )

    private val removedUsersByKey = LinkedHashMap<String, Set<String>>()
    private val removalHistory = LinkedHashMap<String, RemovalEntry>()
    private val activeReactions = LinkedHashSet<ActiveEntry>()
    private val popupRemovedUsers = LinkedHashSet<String>()
    @Volatile private var detailsChannelId: String? = null
    @Volatile private var detailsEmojiKey: String? = null
    private var lastPopupWidget: WeakReference<Any>? = null
    private var lastPopupModel: Any? = null
    private var configurePopupMethod: Method? = null
    // Keep the actual rendered pill view when Discord drops its final reaction model.
    private val retainedPillViews = LinkedHashMap<String, LinearLayout>()
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
        val decoded = JSONTokener(json).nextValue()
        val root = decoded as? JSONObject
        val legacy = decoded as? JSONArray
        val markerArray = root?.optJSONArray("activeRemovals") ?: legacy
        val parsedMarkers = LinkedHashMap<String, Set<String>>()
        if (markerArray != null) {
            for (index in 0 until markerArray.length()) {
                val item = markerArray.optJSONObject(index) ?: continue
                val key = item.optString("pillKey").takeIf { it.isNotBlank() } ?: continue
                val users = LinkedHashSet<String>()
                val userArray = item.optJSONArray("removedUsers")
                if (userArray != null) {
                    for (userIndex in 0 until userArray.length()) {
                        val user = userArray.opt(userIndex)
                        val id = when (user) {
                            is JSONObject -> user.optString("id")
                            is String -> user
                            else -> ""
                        }.takeIf { it.isNotBlank() && it != "null" && it != "*" } ?: continue
                        users.add(id)
                    }
                }
                if (users.isNotEmpty()) parsedMarkers[key] = users
            }
        }

        val parsedHistory = LinkedHashMap<String, RemovalEntry>()
        val historyArray = root?.optJSONArray("removalHistory")
        if (historyArray != null) {
            for (index in 0 until historyArray.length()) {
                val item = historyArray.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val channelId = item.optString("channelId")
                val messageId = item.optString("messageId")
                val userId = item.optString("userId")
                val emojiKey = item.optString("emojiKey")
                if (messageId.isBlank() || userId.isBlank() || emojiKey.isBlank()) continue
                parsedHistory[id] = RemovalEntry(
                    id = id,
                    guildId = item.optString("guildId").takeIf { it.isNotBlank() && it != "null" },
                    channelId = channelId,
                    messageId = messageId,
                    userId = userId,
                    emojiKey = emojiKey,
                    reactionType = item.optInt("reactionType", 0),
                    timestamp = if (item.isNull("timestamp")) null else item.optLong("timestamp"),
                )
            }
        }

        val parsedActive = LinkedHashSet<ActiveEntry>()
        val activeArray = root?.optJSONArray("activeReactions")
        if (activeArray != null) {
            for (index in 0 until activeArray.length()) {
                val item = activeArray.optJSONObject(index) ?: continue
                val channelId = item.optString("channelId")
                val messageId = item.optString("messageId")
                val userId = item.optString("userId")
                val emojiKey = item.optString("emojiKey")
                if (channelId.isBlank() || messageId.isBlank() || userId.isBlank() || emojiKey.isBlank()) continue
                parsedActive.add(
                    ActiveEntry(
                        guildId = item.optString("guildId").takeIf { it.isNotBlank() && it != "null" },
                        channelId = channelId,
                        messageId = messageId,
                        userId = userId,
                        emojiKey = emojiKey,
                        reactionType = item.optInt("reactionType", 0),
                    ),
                )
            }
        }

        synchronized(removedUsersByKey) {
            removedUsersByKey.clear()
            removedUsersByKey.putAll(parsedMarkers)
        }
        synchronized(removalHistory) {
            removalHistory.clear()
            removalHistory.putAll(parsedHistory)
        }
        synchronized(activeReactions) {
            activeReactions.clear()
            activeReactions.addAll(parsedActive)
        }

        val staleRetainedViews = synchronized(retainedPillViews) {
            val stale = retainedPillViews
                .filterKeys { it !in parsedMarkers.keys }
                .values
                .toList()
            retainedPillViews.keys.retainAll(parsedMarkers.keys)
            stale
        }

        mainHandler.post {
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
        synchronized(removalHistory) { removalHistory.clear() }
        synchronized(activeReactions) { activeReactions.clear() }
        synchronized(popupRemovedUsers) { popupRemovedUsers.clear() }
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
        detailsChannelId = null
        detailsEmojiKey = null
        lastPopupWidget = null
        lastPopupModel = null
        configurePopupMethod = null
        synchronized(popupRemovedUsers) { popupRemovedUsers.clear() }
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
        val widgetClass = dexFinder.classLoader.loadClass(MANAGE_REACTIONS_WIDGET)
        val configureUi = widgetClass.declaredMethods.firstOrNull {
            it.name == "configureUI" && it.parameterCount == 1
        } ?: error("WidgetManageReactions.configureUI(model) not found")
        configureUi.isAccessible = true
        configurePopupMethod = configureUi

        unhooks += configureUi.hook(before = { param ->
            val model = param.args.getOrNull(0) ?: return@hook
            val widget = param.thisObject
            lastPopupWidget = WeakReference(widget)
            lastPopupModel = model
            val augmented = augmentReactionPopup(widget, model, dexFinder.classLoader)
            if (augmented !== model) param.args[0] = augmented
        })

        val holderBase = dexFinder.classLoader.loadClass(MG_RECYCLER_VIEW_HOLDER)
        val configure = generateSequence(holderBase) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull {
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
            if (
                !holderName.contains("ManageReactionsResultsAdapter", ignoreCase = true) &&
                !dataName.contains("ReactionUserItem", ignoreCase = true)
            ) return@hook

            val itemView = holderItemView(holder) ?: return@hook
            val userId = extractUserId(data) ?: return@hook
            bindUserRow(itemView, messageId, userId)
        })

        XposedBridge.log("Reaction Logs: exact reaction-popup row hooks installed")
    }

    /**
     * Append the users removed from this exact message + selected emoji to the
     * existing Discord model. Constructing Discord's own ReactionUserItem keeps
     * its real User, avatar, display name and profile-click behavior.
     */
    private fun augmentReactionPopup(widget: Any, model: Any, loader: ClassLoader): Any {
        val provider = readMember(widget, "modelProvider") ?: return model
        val channelId = readMember(provider, "channelId")?.toString() ?: return model
        val messageId = readMember(provider, "messageId")?.toString() ?: return model

        val reactionItems = readMember(model, "reactionItems") as? List<*> ?: return model
        val selectedItem = reactionItems.firstOrNull {
            readMember(it, "isSelected") == true
        } ?: return model
        val selectedReaction = readMember(selectedItem, "reaction") ?: return model
        val emoji = readMember(selectedReaction, "emoji") ?: return model
        val selectedEmojiKey = emojiKey(emoji) ?: return model
        val channelLong = channelId.toLongOrNull() ?: return model
        val messageLong = messageId.toLongOrNull() ?: return model
        val guildId = resolveGuildId(loader, channelLong)

        detailsChannelId = channelId
        detailsMessageId = messageId
        detailsEmojiKey = selectedEmojiKey

        val originalItems = readMember(model, "userItems") as? List<*> ?: return model
        val existingUsers = originalItems.mapNotNull(::extractUserId).toMutableSet()
        val output = ArrayList<Any>(originalItems.size + 8)
        originalItems.filterNotNullTo(output)

        val activeForSelection = synchronized(activeReactions) {
            activeReactions.filter { active ->
                active.channelId == channelId &&
                    active.messageId == messageId &&
                    active.emojiKey == selectedEmojiKey &&
                    (active.guildId.isNullOrBlank() || guildId.isNullOrBlank() || active.guildId == guildId)
            }.toList()
        }
        val historyForSelection = synchronized(removalHistory) {
            removalHistory.values.filter { entry ->
                entry.messageId == messageId &&
                    (entry.channelId.isBlank() || entry.channelId == channelId) &&
                    entry.emojiKey == selectedEmojiKey &&
                    (entry.guildId.isNullOrBlank() || guildId.isNullOrBlank() || entry.guildId == guildId)
            }.sortedBy { it.timestamp ?: 0L }
        }

        val currentActiveIds = activeForSelection.mapTo(LinkedHashSet()) { it.userId }
        val newlyAdded = LinkedHashSet<String>()
        for (active in activeForSelection) {
            if (!existingUsers.add(active.userId)) continue
            val user = resolveUser(loader, active.userId) ?: continue
            val member = guildId?.let { resolveGuildMember(loader, it, active.userId) }
            createReactionUserItem(loader, user, channelLong, messageLong, emoji, member)?.let {
                output.add(it)
                newlyAdded.add(active.userId)
            }
        }

        val removedIds = LinkedHashSet<String>()
        for (entry in historyForSelection) {
            if (entry.userId in existingUsers || entry.userId in currentActiveIds) continue
            val user = resolveUser(loader, entry.userId) ?: continue
            val member = guildId?.let { resolveGuildMember(loader, it, entry.userId) }
            val row = createReactionUserItem(loader, user, channelLong, messageLong, emoji, member)
                ?: continue
            output.add(row)
            existingUsers.add(entry.userId)
            removedIds.add(entry.userId)
        }

        synchronized(popupRemovedUsers) {
            popupRemovedUsers.clear()
            popupRemovedUsers.addAll(removedIds)
        }
        refreshUserRows()

        if (output.size == originalItems.size) return model
        val copy = model.javaClass.methods.firstOrNull {
            it.name == "copy" && it.parameterCount == 2
        }
        if (copy != null) {
            return runCatching { copy.invoke(model, reactionItems, output) }.getOrNull() ?: model
        }
        val constructor = model.javaClass.declaredConstructors.firstOrNull {
            it.parameterCount == 2 &&
                List::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                List::class.java.isAssignableFrom(it.parameterTypes[1])
        } ?: return model
        return runCatching {
            constructor.isAccessible = true
            constructor.newInstance(reactionItems, output)
        }.getOrNull() ?: model
    }

    private fun readMember(target: Any?, name: String): Any? {
        if (target == null) return null
        val suffix = name.replaceFirstChar { it.uppercase() }
        val method = target.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name in setOf(name, "get$suffix", "is$suffix")
        }
        if (method != null) {
            runCatching { method.invoke(target) }.getOrNull()?.let { return it }
        }

        var current: Class<*>? = target.javaClass
        while (current != null) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null && !Modifier.isStatic(field.modifiers)) {
                runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull()?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun resolveStore(loader: ClassLoader, getter: String): Any? = runCatching {
        val stream = loader.loadClass("com.discord.stores.StoreStream")
        val method = stream.methods.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.name == getter && it.parameterCount == 0
        } ?: return@runCatching null
        method.invoke(null)
    }.getOrNull()

    private fun resolveUser(loader: ClassLoader, userId: String): Any? {
        val numericId = userId.toLongOrNull() ?: return null
        val store = resolveStore(loader, "getUsers") ?: return null
        val users = readMember(store, "users") as? Map<*, *>
            ?: runCatching {
                store.javaClass.methods.firstOrNull {
                    it.name == "getUsers" && it.parameterCount == 0
                }?.invoke(store) as? Map<*, *>
            }.getOrNull()
        users?.get(numericId)?.let { return it }
        users?.get(userId)?.let { return it }

        return runCatching {
            store.javaClass.methods.firstOrNull {
                it.name == "getUser" && it.parameterCount == 1
            }?.invoke(store, numericId)
        }.getOrNull()
    }

    private fun resolveGuildId(loader: ClassLoader, channelId: Long): String? = runCatching {
        val store = resolveStore(loader, "getChannels") ?: return@runCatching null
        val channel = store.javaClass.methods.firstOrNull {
            it.name == "getChannel" && it.parameterCount == 1
        }?.invoke(store, channelId) ?: return@runCatching null
        readMember(channel, "guildId")?.toString()?.takeIf { it != "0" && it != "null" }
    }.getOrNull()

    private fun resolveGuildMember(loader: ClassLoader, guildId: String, userId: String): Any? = runCatching {
        val guilds = resolveStore(loader, "getGuilds") ?: return@runCatching null
        val members = guilds.javaClass.methods.firstOrNull {
            it.name == "getMembers" && it.parameterCount == 0
        }?.invoke(guilds) as? Map<*, *> ?: return@runCatching null
        val guildMembers = members[guildId.toLongOrNull()] as? Map<*, *>
            ?: members[guildId] as? Map<*, *>
            ?: return@runCatching null
        val numericUserId = userId.toLongOrNull()
        guildMembers[numericUserId] ?: guildMembers[userId]
    }.getOrNull()

    private fun createReactionUserItem(
        loader: ClassLoader,
        user: Any,
        channelId: Long,
        messageId: Long,
        emoji: Any,
        guildMember: Any?,
    ): Any? = runCatching {
        val itemClass = loader.loadClass(REACTION_USER_ITEM)
        val constructor = itemClass.declaredConstructors.firstOrNull { it.parameterCount == 6 }
            ?: return@runCatching null
        constructor.isAccessible = true
        constructor.newInstance(user, channelId, messageId, emoji, false, guildMember)
    }.onFailure {
        XposedBridge.log("Reaction Logs: could not create removed-user popup row: $it")
    }.getOrNull()

    private fun bindUserRow(view: View, messageId: String, userId: String) {
        userRows[view] = UserRowBinding(messageId, userId)
        mainHandler.post {
            val binding = userRows[view] ?: return@post
            if (binding.messageId != messageId || binding.userId != userId) return@post
            val highlighted = synchronized(popupRemovedUsers) { userId in popupRemovedUsers }
            applyUserRowStyle(view, highlighted)
        }
    }

    private fun refreshUserRows() {
        for ((view, binding) in userRows.entries.toList()) {
            val highlighted = synchronized(popupRemovedUsers) { binding.userId in popupRemovedUsers }
            applyUserRowStyle(view, highlighted)
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

        if (id != null) return "custom:$id"
        if (name != null) return "unicode:${Normalizer.normalize(name, Normalizer.Form.NFC)}"

        val text = emoji.toString()
        val parsedId = ID_PATTERN.find(text)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() && it != "null" }
        val parsedName = NAME_PATTERN.find(text)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() && it != "null" }
        return when {
            parsedId != null -> "custom:$parsedId"
            parsedName != null -> "unicode:${Normalizer.normalize(parsedName, Normalizer.Form.NFC)}"
            else -> null
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
