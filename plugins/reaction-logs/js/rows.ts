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
    if (row?.type !== MessageRowType || !row.message?.id) return

    const messageId = row.message.id
    if (changed.delete(messageId)) row.changeType = UpdateChangeType

    const existing = Array.isArray(row.message.content) ? row.message.content : []
    // RowManager can regenerate a row from an object that already contains our labels.
    // Strip old labels first so repeated renders never stack duplicate timestamps.
    const baseContent = existing.filter(node =>
        !(node?.type === 'text' && typeof node.content === 'string' &&
            node.content.includes(ColorStart) && node.content.includes('Reaction deleted at ') &&
            node.content.includes(ColorEnd)),
    )
    if (baseContent.length !== existing.length) row.message.content = baseContent

    if (!settings().enabled) return

    const entries = [...logs.values()]
        .filter(log => log.messageId === messageId)
        .sort((a, b) => a.deletedAt.localeCompare(b.deletedAt))

    if (!entries.length) return

    const labels = entries.map(log => ({
        type: 'text',
        content: '\\n' + ColorStart + DeletedColor + ColorSeparator +
            'Reaction deleted at ' + log.deletedAt + ColorEnd,
    }))

    row.message.content = [...baseContent, ...labels]
}
