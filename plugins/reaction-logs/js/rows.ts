import type { Settings } from './shared'

export interface ReactionRemover {
    userId: string
    displayName: string
    avatarUrl?: string
}

export interface ReactionLog {
    id: string
    messageId: string
    channelId: string
    guildId?: string
    emojiKey: string
    reactionType: number
    pillKey: string
    deletedAt: string
    deletedAtMs: number
    removedBy?: ReactionRemover
}

export interface RowManager {
    prototype: {
        generate(args: GenerateArgs): MessageRow
    }
}

export interface GenerateArgs {
    message: {
        id: string
        channel_id?: string
        channelId?: string
        content?: ContentNode[]
        [key: string]: unknown
    }
}

export interface MessageRow {
    type?: number
    changeType?: number
    message?: {
        id: string
        content?: ContentNode[]
        [key: string]: unknown
    }
}

interface ContentNode {
    type?: string
    content?: unknown
    [key: string]: unknown
}

const MessageRowType = 1
const UpdateChangeType = 2

// These markers are removed and styled by ReactionLogHooks.kt.
const ColorStart = ''
const ColorSeparator = ''
const ColorEnd = ''
const DeletedColor = (0xFFED4245 | 0).toString()
const GeneratedNode = 'reactionLogsGenerated'

export function styleRow(
    row: MessageRow,
    logs: Map<string, ReactionLog>,
    changed: Set<string>,
    settings: () => Settings,
) {
    if (row?.type !== MessageRowType || !row.message?.id) return

    const messageId = row.message.id
    if (changed.delete(messageId)) row.changeType = UpdateChangeType

    const existing = Array.isArray(row.message.content) ? row.message.content : []
    // Strip our previous output before rebuilding it. This prevents duplicate names,
    // avatars and timestamps when Discord regenerates the same row object.
    const baseContent = existing.filter(node =>
        node?.[GeneratedNode] !== true &&
        !(node?.type === 'text' && typeof node.content === 'string' &&
            node.content.includes(ColorStart) && node.content.includes('Reaction deleted at ') &&
            node.content.includes(ColorEnd)),
    )

    if (!settings().enabled) {
        if (baseContent.length !== existing.length) row.message.content = baseContent
        return
    }

    const entries = [...logs.values()]
        .filter(log => log.messageId === messageId)
        .sort((a, b) => a.deletedAtMs - b.deletedAtMs)

    if (!entries.length) {
        if (baseContent.length !== existing.length) row.message.content = baseContent
        return
    }

    const labels: ContentNode[] = []
    for (const log of entries) {
        labels.push({
            type: 'text',
            content: '\n' + ColorStart + DeletedColor + ColorSeparator +
                'Reaction deleted at ' + log.deletedAt + ColorEnd,
            [GeneratedNode]: true,
        })

        if (!log.removedBy) continue

        labels.push({
            type: 'text',
            content: ' by ',
            [GeneratedNode]: true,
        })

        const mentionContent: ContentNode[] = []
        if (log.removedBy.avatarUrl) {
            mentionContent.push({
                type: 'customEmoji',
                id: 'reaction-log-avatar-' + log.removedBy.userId,
                alt: log.removedBy.displayName + ' avatar',
                src: log.removedBy.avatarUrl,
                frozenSrc: log.removedBy.avatarUrl,
                [GeneratedNode]: true,
            })
        }
        mentionContent.push({
            type: 'text',
            content: '@' + log.removedBy.displayName,
        })

        // Discord's native mention node makes the name and avatar one tappable
        // target and opens the same profile sheet as an ordinary chat mention.
        labels.push({
            type: 'mention',
            userId: log.removedBy.userId,
            ...(log.guildId ? { guildId: log.guildId } : {}),
            content: mentionContent,
            [GeneratedNode]: true,
        })
    }

    row.message.content = [...baseContent, ...labels]
}
