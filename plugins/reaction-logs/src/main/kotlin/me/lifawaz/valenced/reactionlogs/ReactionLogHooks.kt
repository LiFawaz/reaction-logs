package me.lifawaz.valenced.reactionlogs

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Gravity
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
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
import java.util.Date
import java.util.LinkedHashMap
import java.util.WeakHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

private const val SHORTCUTS_FLEXBOX = "com.discord.reactions.ShortcutsFlexbox"
private const val WRAPPER_TAG = "lifawaz.reaction-logs.wrapper"
private const val OPEN_PROFILE_METHOD = "lifawaz.reaction-logs.openProfile"

private val DELETED_RED = Color.rgb(237, 66, 69)
private val DELETED_TINT = Color.argb(48, 237, 66, 69)

internal class ReactionLogHooks(
    dexFinder: DexFinder,
    private val bridge: ValencedBridge,
) {
    private data class Pill(val emoji: Any?, val burst: Boolean, val count: Int)

    private data class OriginalStyle(
        val backgroundTint: ColorStateList?,
        val countColors: ColorStateList?,
    )

    private data class Deletion(
        val timestampMs: Long,
        val userId: String?,
        val displayName: String,
    )

    private val removedByKey = LinkedHashMap<String, List<Deletion>>()
    private val bound = WeakHashMap<LinearLayout, String>()
    private val originalStyles = WeakHashMap<LinearLayout, OriginalStyle>()
    private val bindingMessageId = ThreadLocal<String?>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val unhooks = mutableListOf<XC_MethodHook.Unhook>()

    init {
        hookReactionPills(dexFinder)
    }

    fun setRemoved(json: String) {
        val parsed = LinkedHashMap<String, MutableList<Deletion>>()
        val array = JSONArray(json)

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val key = item.optString("pillKey").takeIf(String::isNotBlank) ?: continue
            val timestamp = item.optLong("deletedAtMs", 0L)
            if (timestamp <= 0L) continue

            val userId = item.optString("userId")
                .takeIf { it.isNotBlank() && USER_ID_PATTERN.matches(it) }
            val name = item.optString("displayName")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
                .take(48)
                .ifBlank { "Unknown user" }

            parsed.getOrPut(key) { mutableListOf() }.add(
                Deletion(timestamp, userId, name),
            )
        }

        synchronized(removedByKey) {
            removedByKey.clear()
            for ((key, entries) in parsed) {
                removedByKey[key] = entries.sortedBy(Deletion::timestampMs)
            }
        }

        mainHandler.post {
            for ((pill, key) in bound.entries.toList()) {
                val entries = deletionsFor(key)
                applyStyle(pill, entries.isNotEmpty())
                applyDeletionLabels(pill, entries)
            }
        }
    }

    fun clear() {
        synchronized(removedByKey) { removedByKey.clear() }
        mainHandler.post {
            for (pill in bound.keys.toList()) {
                applyStyle(pill, false)
                applyDeletionLabels(pill, emptyList())
            }
        }
    }

    fun dispose() {
        unhooks.forEach { it.unhook() }
        unhooks.clear()
        synchronized(removedByKey) { removedByKey.clear() }
        mainHandler.post {
            for (pill in bound.keys.toList()) {
                applyStyle(pill, false)
                applyDeletionLabels(pill, emptyList())
            }
            bound.clear()
            originalStyles.clear()
        }
    }

    private fun deletionsFor(key: String): List<Deletion> =
        synchronized(removedByKey) { removedByKey[key].orEmpty().toList() }

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
                val child = flexbox.getChildAt(index) as? LinearLayout ?: continue
                val pillView = if (child.tag == WRAPPER_TAG) {
                    child.getChildAt(0) as? LinearLayout
                } else {
                    child
                } ?: continue

                val pill = reactionPills.getOrNull(index)
                if (
                    pill == null ||
                    messageId == null ||
                    pill.count == 0 ||
                    pill.emoji == null
                ) {
                    bound.remove(pillView)
                    applyStyle(pillView, false)
                    applyDeletionLabels(pillView, emptyList())
                    continue
                }

                val emoji = emojiKey(pill.emoji)
                if (emoji.isNullOrBlank()) {
                    bound.remove(pillView)
                    applyStyle(pillView, false)
                    applyDeletionLabels(pillView, emptyList())
                    continue
                }

                val key = reactionKey(messageId, emoji, if (pill.burst) 1 else 0)
                bound[pillView] = key
                val entries = deletionsFor(key)
                applyStyle(pillView, entries.isNotEmpty())
                applyDeletionLabels(pillView, entries)
            }
        })
    }

    private fun applyStyle(view: LinearLayout, deleted: Boolean) {
        val count = countText(view)
        val original = originalStyles.getOrPut(view) {
            OriginalStyle(view.backgroundTintList, count?.textColors)
        }

        if (deleted) {
            view.backgroundTintList = ColorStateList.valueOf(DELETED_TINT)
            count?.setTextColor(DELETED_RED)
        } else {
            view.backgroundTintList = original.backgroundTint
            original.countColors?.let { count?.setTextColor(it) }
        }
    }

    private fun applyDeletionLabels(pill: LinearLayout, entries: List<Deletion>) {
        var wrapper = findWrapper(pill)

        if (entries.isEmpty()) {
            if (wrapper != null) unwrapPill(pill, wrapper)
            return
        }

        if (wrapper == null) {
            wrapper = wrapPill(pill) ?: return
        }

        val labels = wrapper.getChildAt(1) as? LinearLayout ?: return
        labels.removeAllViews()

        for (entry in entries) {
            val time = DateFormat.getTimeFormat(pill.context).format(Date(entry.timestampMs))
            val name = entry.displayName.ifBlank { "Unknown user" }
            val line = TextView(pill.context).apply {
                text = "↓ This was deleted at $time by @$name"
                setTextColor(DELETED_RED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(pill.context, 224f)
                setPadding(0, dp(pill.context, 2f), 0, 0)
                contentDescription = "This reaction was deleted at $time by $name"

                if (entry.userId != null) {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { openUserProfile(entry.userId) }
                }
            }
            labels.addView(
                line,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun findWrapper(pill: LinearLayout): LinearLayout? =
        (pill.parent as? LinearLayout)?.takeIf { it.tag == WRAPPER_TAG }

    private fun wrapPill(pill: LinearLayout): LinearLayout? {
        val parent = pill.parent as? ViewGroup ?: return null
        val index = parent.indexOfChild(pill)
        if (index < 0) return null

        val outerParams = pill.layoutParams
            ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        val innerParams = LinearLayout.LayoutParams(outerParams.width, outerParams.height).apply {
            if (outerParams is ViewGroup.MarginLayoutParams) {
                setMargins(
                    outerParams.leftMargin,
                    outerParams.topMargin,
                    outerParams.rightMargin,
                    outerParams.bottomMargin,
                )
                marginStart = outerParams.marginStart
                marginEnd = outerParams.marginEnd
            }
        }

        val wrapper = LinearLayout(pill.context).apply {
            tag = WRAPPER_TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val labels = LinearLayout(pill.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        return try {
            parent.removeViewAt(index)
            wrapper.addView(pill, innerParams)
            wrapper.addView(
                labels,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            parent.addView(wrapper, index, outerParams)
            wrapper
        } catch (error: Throwable) {
            (pill.parent as? ViewGroup)?.removeView(pill)
            runCatching { parent.addView(pill, index, outerParams) }
            XposedBridge.log(error)
            null
        }
    }

    private fun unwrapPill(pill: LinearLayout, wrapper: LinearLayout) {
        val parent = wrapper.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(wrapper)
        if (index < 0) return

        val outerParams = wrapper.layoutParams
        wrapper.removeView(pill)
        parent.removeView(wrapper)
        parent.addView(pill, index, outerParams)
    }

    private fun openUserProfile(userId: String) {
        if (!USER_ID_PATTERN.matches(userId)) return

        try {
            val call: suspend () -> Any? = {
                bridge.callJSMethod(OPEN_PROFILE_METHOD, listOf(userId))
            }
            call.startCoroutine(
                object : Continuation<Any?> {
                    override val context = EmptyCoroutineContext

                    override fun resumeWith(result: Result<Any?>) {
                        result.exceptionOrNull()?.let { XposedBridge.log(it) }
                    }
                },
            )
        } catch (error: Throwable) {
            XposedBridge.log(error)
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
        if (id != null) return id

        val name = runCatching {
            methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                ?.invoke(emoji)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()
        if (name != null) return name

        val text = emoji.toString()
        val parsedId = ID_PATTERN.find(text)?.groupValues?.get(1)?.takeIf { it != "null" }
        return parsedId ?: NAME_PATTERN.find(text)?.groupValues?.get(1)
            ?: FALLBACK_NAME_PATTERN.find(text)?.groupValues?.get(1)
    }

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics,
        ).toInt()

    private companion object {
        val USER_ID_PATTERN = Regex("""\d{15,25}""")
        val ID_PATTERN = Regex(""", id=([^,)]*)""")
        val NAME_PATTERN = Regex("""\(name=(.*?), src=""")
        val FALLBACK_NAME_PATTERN = Regex("""\(name=([^,)]*)""")
    }
}

private fun reactionKey(messageId: String, emojiKey: String, type: Int) =
    "$messageId:$emojiKey:$type"

private inline fun Method.hook(
    crossinline before: (XC_MethodHook.MethodHookParam) -> Unit = {},
    crossinline after: (XC_MethodHook.MethodHookParam) -> Unit = {},
): XC_MethodHook.Unhook = XposedBridge.hookMethod(this, object : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) = before(param)
    override fun afterHookedMethod(param: MethodHookParam) = after(param)
})
