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
    private data class RemovedUser(
        val userId: String,
        val username: String?,
        val displayName: String?,
        val avatar: String?,
    )
    private data class UserRowBinding(
        val messageId: String,
        val emojiKey: String,
        val userId: String,
    )

    private val removedUsersByKey = LinkedHashMap<String, MutableMap<String, RemovedUser>>()
    private val knownEmojiObjects = LinkedHashMap<String, Any>()
    private val selectedEmojiByProvider = WeakHashMap<Any, String>()
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
        val parsed = LinkedHashMap<String, MutableMap<String, RemovedUser>>()
        val array = JSONArray(json)

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val messageId = item.optString("messageId").takeIf { it.isNotBlank() } ?: continue
            val emojiKey = item.optString("emojiKey").takeIf { it.isNotBlank() } ?: continue
            val userId = item.optString("userId").takeIf {
                it.isNotBlank() && it != "null" && it != "*"
            } ?: continue
            val reactionType = item.optInt("reactionType", 0)
            val key = reactionKey(messageId, emojiKey, reactionType)
            parsed.getOrPut(key) { LinkedHashMap() }[userId] = RemovedUser(
                userId,
                item.optString("username").takeIf { it.isNotBlank() && it != "null" },
                item.optString("displayName").takeIf { it.isNotBlank() && it != "null" },
                item.optString("avatar").takeIf { it.isNotBlank() && it != "null" },
            )
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
        synchronized(removedUsersByKey) { removedUsersByKey[key]?.isNotEmpty() == true }

    private fun removedUsersFor(messageId: String, emojiKey: String): List<RemovedUser> =
        synchronized(removedUsersByKey) {
            removedUsersByKey.entries
                .asSequence()
                .filter { it.key.startsWith("$messageId|$emojiKey|") }
                .flatMap { it.value.values.asSequence() }
                .distinctBy { it.userId }
                .toList()
        }

    private fun isRemovedUser(messageId: String, emojiKey: String, userId: String): Boolean =
        removedUsersFor(messageId, emojiKey).any { it.userId == userId }

    private fun wrapManageReactionsObservable(provider: Any, observable: Any): Any {
        return runCatching {
            val loader = provider.javaClass.classLoader
            val functionClass = loader.loadClass("rx.functions.Func1")
            val mapper = java.lang.reflect.Proxy.newProxyInstance(
                loader,
                arrayOf(functionClass),
            ) { proxy, method, args ->
                when (method.name) {
                    "call" -> runCatching {
                        mergeManageReactionsModel(provider, args?.firstOrNull())
                    }.onFailure {
                        XposedBridge.log("Reaction Logs: could not merge removed users into popup: $it")
                    }.getOrNull() ?: args?.firstOrNull()
                    "toString" -> "ReactionLogsRemovedUserMapper"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
            val observableClass = loader.loadClass("rx.Observable")
            observableClass.getMethod("map", functionClass).invoke(observable, mapper) ?: observable
        }.getOrElse {
            XposedBridge.log("Reaction Logs: could not hook reaction popup model stream: $it")
            observable
        }
    }

    /**
     * The Manage Reactions sheet consumes a model with an emoji list and userItems.
     * Mapping its existing Rx stream lets us append ordinary Discord ReactionUserItem
     * models, so the original RecyclerView, avatars, profile clicks and virtualization remain.
     */
    private fun mergeManageReactionsModel(provider: Any, model: Any?): Any? {
        val messageId = invokeGetter(provider, "getMessageId")?.toString() ?: return model
        val channelId = (invokeGetter(provider, "getChannelId") as? Number)?.toLong() ?: return model

        val reactionItems = (invokeGetter(model, "getReactionItems") as? List<*>)?.toList()
            ?: emptyList()
        val currentUserItems = (invokeGetter(model, "getUserItems") as? List<*>)?.toList()
            ?: emptyList()

        var selectedEmoji: Any? = null
        var selectedEmojiKey: String? = null
        for (item in reactionItems) {
            val selected = invokeGetter(item, "isSelected", "getIsSelected") as? Boolean ?: false
            if (!selected) continue
            val reaction = invokeGetter(item, "getReaction")
            selectedEmoji = invokeGetter(reaction, "getEmoji")
            selectedEmojiKey = selectedEmoji?.let(::emojiKey)
            if (selectedEmojiKey != null) break
        }

        if (selectedEmojiKey == null) {
            selectedEmojiKey = targetedEmojiKey(provider)
            if (selectedEmojiKey != null) {
                selectedEmoji = synchronized(knownEmojiObjects) {
                    knownEmojiObjects["$messageId|$selectedEmojiKey"]
                }
            }
        }
        val emojiKey = selectedEmojiKey ?: return model
        val emoji = selectedEmoji ?: synchronized(knownEmojiObjects) {
            knownEmojiObjects["$messageId|$emojiKey"]
        } ?: return model

        val removed = removedUsersFor(messageId, emojiKey)
        if (removed.isEmpty()) return model

        val activeUserIds = currentUserItems.mapNotNull(::extractUserId).toMutableSet()
        val mergedItems = currentUserItems.toMutableList()
        for (user in removed) {
            // A re-added reaction is active and must not be duplicated as a red history row.
            if (user.userId in activeUserIds) continue
            val item = createReactionUserItem(provider, user, channelId, messageId, emoji)
            if (item != null) {
                mergedItems.add(item)
                activeUserIds.add(user.userId)
            }
        }
        if (mergedItems.size == currentUserItems.size) return model

        return createManageReactionsModel(provider, model, reactionItems, mergedItems)
            ?: model
    }

    private fun createManageReactionsModel(
        provider: Any,
        model: Any?,
        reactionItems: List<*>,
        userItems: List<Any?>,
    ): Any? {
        val modelClass = model?.javaClass
            ?: runCatching {
                provider.javaClass.classLoader.loadClass(
                    "com.discord.widgets.chat.managereactions.ManageReactionsModel",
                )
            }.getOrNull()
            ?: return null

        if (model != null) {
            val copy = modelClass.methods.firstOrNull {
                it.name == "copy" && it.parameterCount == 2
            }
            if (copy != null) {
                return runCatching { copy.invoke(model, reactionItems, userItems) }.getOrNull()
            }
        }

        val constructor = modelClass.declaredConstructors.firstOrNull {
            it.parameterCount == 2 &&
                List::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                List::class.java.isAssignableFrom(it.parameterTypes[1])
        } ?: return null
        return runCatching {
            constructor.isAccessible = true
            constructor.newInstance(reactionItems, userItems)
        }.getOrNull()
    }

    private fun createReactionUserItem(
        provider: Any,
        removed: RemovedUser,
        channelId: Long,
        messageId: String,
        emoji: Any,
    ): Any? {
        val loader = provider.javaClass.classLoader
        val user = resolveDiscordUser(loader, removed) ?: return null
        val itemClass = runCatching {
            loader.loadClass(
                "com.discord.widgets.chat.managereactions.ManageReactionsResultsAdapter\$ReactionUserItem",
            )
        }.getOrNull() ?: return null

        val messageLong = messageId.toLongOrNull() ?: return null
        val constructor = itemClass.declaredConstructors.firstOrNull { candidate ->
            val types = candidate.parameterTypes
            types.size == 6 &&
                types[0].isInstance(user) &&
                (types[1] == java.lang.Long.TYPE || types[1] == java.lang.Long::class.java) &&
                (types[2] == java.lang.Long.TYPE || types[2] == java.lang.Long::class.java) &&
                types[3].isInstance(emoji) &&
                (types[4] == java.lang.Boolean.TYPE || types[4] == java.lang.Boolean::class.java)
        } ?: return null

        return runCatching {
            constructor.isAccessible = true
            constructor.newInstance(user, channelId, messageLong, emoji, false, null)
        }.onFailure {
            XposedBridge.log("Reaction Logs: could not construct historical reactor row: $it")
        }.getOrNull()
    }

    private fun resolveDiscordUser(loader: ClassLoader, removed: RemovedUser): Any? {
        val userId = removed.userId.toLongOrNull() ?: return null
        val cached = runCatching {
            val storeStream = loader.loadClass("com.discord.stores.StoreStream")
            val getter = storeStream.methods.firstOrNull {
                it.name == "getUsers" && it.parameterCount == 0 &&
                    java.lang.reflect.Modifier.isStatic(it.modifiers)
            } ?: return@runCatching null
            val userStore = getter.invoke(null) ?: return@runCatching null
            val users = invokeGetter(userStore, "getUsersInternal") as? Map<*, *>
            users?.get(userId) ?: users?.get(removed.userId)
        }.getOrNull()
        if (cached != null) return cached

        return runCatching {
            val userInterface = loader.loadClass("com.discord.models.user.User")
            if (!userInterface.isInterface) return@runCatching null
            val fallbackName = removed.username
                ?: removed.displayName
                ?: removed.userId
            java.lang.reflect.Proxy.newProxyInstance(
                loader,
                arrayOf(userInterface),
            ) { proxy, method, args ->
                when (method.name) {
                    "getId" -> userId
                    "getUsername" -> fallbackName
                    "getGlobalName" -> removed.displayName
                    "getAvatar" -> removed.avatar
                    "getDiscriminator", "getFlags", "getPublicFlags" -> 0
                    "getIsBot", "isBot", "getIsSystemUser", "isSystemUser", "getIsWebhook", "isWebhook" -> false
                    "getBanner", "getBannerColor", "getBio", "getCollectibles",
                    "getDisplayNameStyles", "getAvatarDecoration", "getPrimaryGuild" -> null
                    "toString" -> "ReactionLogsUser($userId)"
                    "hashCode" -> userId.hashCode()
                    "equals" -> proxy === args?.firstOrNull()
                    else -> defaultValue(method.returnType)
                }
            }
        }.getOrNull()
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private fun targetedEmojiKey(provider: Any): String? {
        selectedEmojiByProvider[provider]?.let { return it }
        var current: Class<*>? = provider.javaClass
        while (current != null) {
            val field = current.declaredFields.firstOrNull {
                it.name == "targetedEmojiKeySubject"
            }
            if (field != null) {
                val raw = runCatching {
                    field.isAccessible = true
                    val subject = field.get(provider) ?: return@runCatching null
                    invokeGetter(subject, "getValue")?.toString()
                        ?: nestedSubjectValue(subject, 0)
                }.getOrNull()
                if (!raw.isNullOrBlank()) return normalizeTargetEmojiKey(raw)
            }
            current = current.superclass
        }
        return null
    }

    private fun nestedSubjectValue(value: Any, depth: Int): String? {
        if (depth > 2) return null
        var current: Class<*>? = value.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                if (!field.name.contains("actual", ignoreCase = true) &&
                    !field.name.contains("subject", ignoreCase = true)) continue
                val nested = runCatching {
                    field.isAccessible = true
                    field.get(value)
                }.getOrNull() ?: continue
                val direct = invokeGetter(nested, "getValue")?.toString()
                if (!direct.isNullOrBlank()) return direct
                nestedSubjectValue(nested, depth + 1)?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun normalizeTargetEmojiKey(raw: String): String {
        if (raw.startsWith("id:") || raw.startsWith("unicode:")) return raw
        val customId = raw.substringAfterLast(':', "")
        return if (customId.isNotBlank() && raw.contains(':')) {
            "id:$customId"
        } else {
            "unicode:$raw"
        }
    }

    private fun invokeGetter(value: Any?, vararg names: String): Any? {
        if (value == null) return null
        val method = value.javaClass.methods.firstOrNull {
            it.name in names && it.parameterCount == 0
        } ?: return null
        return runCatching { method.invoke(value) }.getOrNull()
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
            if (messageId != null) {
                for (pill in reactionPills) {
                    val emoji = pill.emoji ?: continue
                    val key = emojiKey(emoji) ?: continue
                    synchronized(knownEmojiObjects) {
                        val cacheKey = "$messageId|$key"
                        knownEmojiObjects.remove(cacheKey)
                        knownEmojiObjects[cacheKey] = emoji
                        while (knownEmojiObjects.size > 2000) {
                            val oldest = knownEmojiObjects.keys.firstOrNull() ?: break
                            knownEmojiObjects.remove(oldest)
                        }
                    }
                }
            }
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
                    // This is a historical pill, not an active reaction count.
                    setPillCount(pillView, "0")
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

        unhooks += getModel.hook(
            before = { param ->
                detailsMessageId = runCatching {
                    messageIdGetter.invoke(param.thisObject)?.toString()
                }.getOrNull()
            },
            after = { param ->
                val observable = param.result ?: return@hook
                param.result = wrapManageReactionsObservable(param.thisObject, observable)
            },
        )
        providerClass.methods.firstOrNull {
            it.name == "onEmojiTargeted" && it.parameterCount == 1
        }?.let { onEmojiTargeted ->
            unhooks += onEmojiTargeted.hook(after = { param ->
                val raw = param.args.firstOrNull()?.toString()
                if (!raw.isNullOrBlank()) {
                    selectedEmojiByProvider[param.thisObject] = normalizeTargetEmojiKey(raw)
                }
            })
        }

        val holderBase = dexFinder.classLoader.loadClass(MG_RECYCLER_VIEW_HOLDER)
        val configure = holderBase.declaredMethods.firstOrNull {
            it.name == "onConfigure" &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Any::class.java
        } ?: error("MGRecyclerViewHolder.onConfigure(int, Object) not found")
        configure.isAccessible = true

        unhooks += configure.hook(after = { param ->
            val holder = param.thisObject
            val data = param.args.getOrNull(1) ?: return@hook
            val messageId = invokeGetter(data, "getMessageId")?.toString()
                ?: detailsMessageId ?: return@hook
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

            val rowEmoji = invokeGetter(data, "getEmoji")
            val emojiKey = rowEmoji?.let(::emojiKey)
            if (emojiKey.isNullOrBlank()) {
                XposedBridge.log("Reaction Logs: could not read emoji identity from reaction-detail row $dataName")
                return@hook
            }

            bindUserRow(itemView, messageId, emojiKey, userId)
        })

        XposedBridge.log("Reaction Logs: reaction-user row hook installed")
    }

    private fun bindUserRow(view: View, messageId: String, emojiKey: String, userId: String) {
        userRows[view] = UserRowBinding(messageId, emojiKey, userId)
        mainHandler.post {
            val binding = userRows[view] ?: return@post
            if (binding.messageId != messageId || binding.emojiKey != emojiKey ||
                binding.userId != userId) return@post
            applyUserRowStyle(view, isRemovedUser(messageId, emojiKey, userId))
        }
    }

    private fun refreshUserRows() {
        for ((view, binding) in userRows.entries.toList()) {
            applyUserRowStyle(
                view,
                isRemovedUser(binding.messageId, binding.emojiKey, binding.userId),
            )
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

    private fun setPillCount(view: ViewGroup, value: String) {
        countText(view)?.text = value
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

        if (id != null) return "id:$id"
        if (name != null) return "unicode:$name"

        val text = emoji.toString()
        val parsedId = ID_PATTERN.find(text)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() && it != "null" }
        val parsedName = NAME_PATTERN.find(text)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() && it != "null" }
        return when {
            parsedId != null -> "id:$parsedId"
            parsedName != null -> "unicode:$parsedName"
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
