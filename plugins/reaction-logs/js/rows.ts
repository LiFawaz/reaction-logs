import type { Settings } from './shared'

export interface ReactionLog {
    messageId: string
    channelId: string
    guildId?: string
    emojiKey: string
    reactionType: number
    deletedAt: string
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

export function styleRow(
    row: MessageRow,
    logs: Map<string, ReactionLog>,
    changed: Set<string>,
    settings: () => Settings,
) {
    if (!settings().enabled || row?.type !== MessageRowType || !row.message?.id) return

    const messageId = row.message.id
    if (changed.delete(messageId)) row.changeType = UpdateChangeType

    const entries = [...logs.values()]
        .filter(log => log.messageId === messageId)
        .sort((a, b) => a.deletedAt.localeCompare(b.deletedAt))

    if (!entries.length) return

    const labels = entries.map(log => ({
        type: 'text',
        content: '\n' + ColorStart + DeletedColor + ColorSeparator +
            'Reaction deleted at ' + log.deletedAt + ColorEnd,
    }))

    const existing = Array.isArray(row.message.content) ? row.message.content : []
    row.message.content = [...existing, ...labels]
}