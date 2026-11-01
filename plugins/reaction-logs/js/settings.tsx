import Page from '@valenced/components/Page'
import { Design } from '@valenced/discord/design'
import type { PluginSettingsComponent } from '@valenced/plugins/types'
import type { ComponentProps } from 'react'
import { Linking } from 'react-native'
import type { HistoryStorage, RemovalLogEntry } from './shared'

type SettingsProps = ComponentProps<
    PluginSettingsComponent<{ jsonStorage: HistoryStorage }>
>

const { TableRow, TableRowGroup, Button } = Design

const emojiLabel = (entry: RemovalLogEntry) =>
    entry.emojiId
        ? entry.emojiName + ' (custom emoji)'
        : entry.emojiName

const messageUrl = (entry: RemovalLogEntry) => {
    if (!entry.channelId || !entry.messageId) return null
    return (
        'https://discord.com/channels/' +
        (entry.guildId || '@me') +
        '/' + entry.channelId +
        '/' + entry.messageId
    )
}

const openLink = (url: string) => {
    void Linking.openURL(url).catch(() => undefined)
}

export default function SettingsComponent({
    api: { jsonStorage: storage },
}: SettingsProps) {
    const data = storage.use() ?? { entries: [], nextId: 1, activeRemovals: [] }
    const entries = data.entries ?? []
    const latestEntries = [...entries].reverse().slice(0, 100)

    const clearHistory = () => {
        void storage.set({
            entries: [],
            nextId: 1,
            activeRemovals: data.activeRemovals ?? [],
        }, true)
    }

    return (
        <Page>
            <TableRowGroup
                title={'Removal history (' + entries.length + ')'}
                description="Local history of removed reactions. Each entry keeps the emoji, affected account, and message link. Red reaction pills stay until that same emoji is added again."
            >
                {latestEntries.length === 0 ? (
                    <TableRow
                        label="No removed reactions logged yet"
                        subLabel="New removals will appear here."
                    />
                ) : (
                    latestEntries.map(entry => {
                        const emoji = emojiLabel(entry)
                        const message = messageUrl(entry)
                        const hasProfile = Boolean(entry.userId && entry.userId !== '*')

                        return (
                            <TableRowGroup
                                key={entry.id}
                                title={entry.userName + ' · ' + emoji}
                                description={entry.reactionType === 1 ? 'Super reaction removed' : 'Reaction removed'}
                            >
                                <TableRow
                                    label="View profile"
                                    subLabel={hasProfile ? 'Open ' + entry.userName + ' on Discord · ' + entry.userId : 'User details unavailable'}
                                    arrow={hasProfile}
                                    disabled={!hasProfile}
                                    onPress={
                                        hasProfile
                                            ? () => openLink('https://discord.com/users/' + entry.userId)
                                            : undefined
                                    }
                                />
                                <TableRow
                                    label="Open message"
                                    subLabel={message ?? (entry.messageId ? 'Message ' + entry.messageId : 'Message link unavailable')}
                                    arrow={Boolean(message)}
                                    disabled={!message}
                                    onPress={message ? () => openLink(message) : undefined}
                                />
                            </TableRowGroup>
                        )
                    })
                )}
            </TableRowGroup>
            {entries.length > 0 ? (
                <Button
                    text="Clear removal history"
                    variant="secondary"
                    onPress={clearHistory}
                />
            ) : null}
        </Page>
    )
}
