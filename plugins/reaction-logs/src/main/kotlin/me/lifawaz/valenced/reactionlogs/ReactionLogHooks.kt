package me.lifawaz.valenced.reactionlogs

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextSwitcher
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import app.valenced.xposed.api.DexFinder
import org.json.JSONArray
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

private const val DRAWEE_SPAN_TEXT_VIEW = "com.facebook.drawee.span.SimpleDraweeSpanTextView"
private const val SHORTCUTS_FLEXBOX = "com.discord.reactions.ShortcutsFlexbox"

private const val COLOR_START = ''
private const val COLOR_SEPARATOR = ''
private const val COLOR_END = ''

private val DELETED_RED = Color.rgb(237, 66, 69)
private val DELETED_TINT = Color.argb(48, 237, 66, 69)

/** Keeps removed reaction pills visible and marks them red for this app session. */
internal class ReactionLogHooks(dexFinder: DexFinder) {
    private data class Pill(val emoji: Any?, val burst: Boolean, val count: Int)
    private data class OriginalStyle(
        val backgroundTint: ColorStateList?,
        val countColors: ColorStateList?,
    )

    private val removed = HashSet<String>()
    private val bound = WeakHashMap<LinearLayout, String>()
    private val originalStyles = WeakHashMap<LinearLayout, OriginalStyle>()
    private val bindingMessageId = ThreadLocal<String?>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val unhooks = mutableListOf<XC_MethodHook.Unhook>()

    init {
        hookReactionPills(dexFinder)
        hookDeletedLabel(dexFinder)
    }

    fun setRemoved(json: String) {
        val array = JSONArray(json)
        synchronized(removed) {
            removed.clear()
            for (index in 0 until array.length()) removed.add(array.getString(index))
        }

        mainHandler.post {
            for ((view, key) in bound) applyStyle(view, isRemoved(key))
        }
    }

    fun clear() {
        synchronized(removed) { removed.clear() }
        mainHandler.post {
            for (view in bound.keys.toList()) applyStyle(view, false)
        }
    }

    fun dispose() {
        unhooks.forEach { it.unhook() }
        unhooks.clear()
        synchronized(removed) { removed.clear() }
        mainHandler.post {
            for (view in bound.keys.toList()) applyStyle(view, false)
            bound.clear()
            originalStyles.clear()
        }
    }

    private fun isRemoved(key: String) = synchronized(removed) { key in removed }

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

        val viewHolderClass = onBindViewHolder.parameterTypes[0]
        val itemViewField = generateSequence<Class<*>>(viewHolderClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .first { it.type == View::class.java && !Modifier.isStatic(it.modifiers) }
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
                val view = flexbox.getChildAt(index) as? LinearLayout ?: continue
                val pill = reactionPills.getOrNull(index)

                if (pill == null || messageId == null || pill.count == 0 || pill.emoji == null) {
                    bound.remove(view)
                    applyStyle(view, false)
                    continue
                }

                val emoji = emojiKey(pill.emoji) ?: continue
                val key = reactionKey(messageId, emoji, if (pill.burst) 1 else 0)
                bound[view] = key
                applyStyle(view, isRemoved(key))
            }
        })
    }

    private fun hookDeletedLabel(dexFinder: DexFinder) {
        try {
            val method = dexFinder.classLoader
                .loadClass(DRAWEE_SPAN_TEXT_VIEW)
                .declaredMethods
                .singleOrNull { it.name == "setDraweeSpanStringBuilder" }
                ?: return

            unhooks += method.hook(before = { param ->
                (param.args.firstOrNull() as? SpannableStringBuilder)?.styleDeletedLabels()
            })
        } catch (_: Throwable) {
            // The reaction pill highlight still works if this Discord text view changes.
        }
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
        val text = emoji.toString()
        val id = ID_PATTERN.find(text)?.groupValues?.get(1)?.takeIf { it != "null" }
        return id ?: NAME_PATTERN.find(text)?.groupValues?.get(1)
    }

    private fun SpannableStringBuilder.styleDeletedLabels() {
        if (TextUtils.indexOf(this, COLOR_START) < 0) return

        while (true) {
            val text = toString()
            val start = text.lastIndexOf(COLOR_START)
            if (start < 0) return

            val separator = text.indexOf(COLOR_SEPARATOR, start)
            val end = text.indexOf(COLOR_END, start)
            if (separator !in 0..end) {
                delete(start, start + 1)
                continue
            }

            val color = text.substring(start + 1, separator).toIntOrNull()
            delete(end, end + 1)
            delete(start, separator + 1)

            val labelEnd = start + end - separator - 1
            if (color != null) {
                setSpan(
                    ForegroundColorSpan(color),
                    start,
                    labelEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    private companion object {
        val ID_PATTERN = Regex(""", id=([^,)]*)""")
        val NAME_PATTERN = Regex("""\(name=(.*?), src=""")
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