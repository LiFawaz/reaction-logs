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
    // Remove our previous output before rebuilding it, preventing duplicate log rows.
    const baseContent = existing.filter(node => node?.[GeneratedNode] !== true)

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

    const config = settings()
    const labels: ContentNode[] = []

    for (const log of entries) {
        const mentionContent: ContentNode[] = []
        const remover = log.removedBy

        if (remover) {
            if (config.showAvatar && remover.avatarUrl) {
                mentionContent.push({
                    type: 'customEmoji',
                    id: 'reaction-log-avatar-' + remover.userId,
                    alt: remover.displayName + ' avatar',
                    src: remover.avatarUrl,
                    frozenSrc: remover.avatarUrl,
                    [GeneratedNode]: true,
                })
            }

            if (config.showUserName) {
                mentionContent.push({
                    type: 'text',
                    content: '@' + remover.displayName,
                    [GeneratedNode]: true,
                })
            }
        }

        const hasMention = !!remover && mentionContent.length > 0
        if (!hasMention && !config.showTime) continue

        labels.push({
            type: 'text',
            content: '\n',
            [GeneratedNode]: true,
        })

        if (hasMention) {
            labels.push({
                type: 'text',
                content: 'Removed by ',
                [GeneratedNode]: true,
            })
            labels.push({
                type: 'mention',
                userId: remover!.userId,
                ...(log.guildId ? { guildId: log.guildId } : {}),
                content: mentionContent,
                [GeneratedNode]: true,
            })
        } else {
            labels.push({
                type: 'text',
                content: 'Reaction removed',
                [GeneratedNode]: true,
            })
        }

        if (config.showTime) {
            labels.push({
                type: 'text',
                content: (hasMention ? ' · ' : ' at ') + formatLocalTime(log.deletedAtMs),
                [GeneratedNode]: true,
            })
        }
    }

    row.message.content = [...baseContent, ...labels]
}

/** Uses the device's current locale and local timezone, matching Discord's local timestamps. */
function formatLocalTime(timestamp: number) {
    const date = new Date(timestamp)
    try {
        return date.toLocaleTimeString(undefined, {
            hour: 'numeric',
            minute: '2-digit',
        })
    } catch {
        return String(date.getHours()).padStart(2, '0') + ':' +
            String(date.getMinutes()).padStart(2, '0')
    }
}
