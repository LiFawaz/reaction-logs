import Page from '@valenced/components/Page'
import { Design } from '@valenced/discord/design'
import type { PluginSettingsComponent } from '@valenced/plugins/types'
import type { ComponentProps } from 'react'
import type { HistoryStorage, RemovalLogEntry } from './shared'

type SettingsProps = ComponentProps<
    PluginSettingsComponent<{ jsonStorage: HistoryStorage }>
>

const { TableRow, TableRowGroup, Button } = Design

const describeEntry = (entry: RemovalLogEntry) => {
    const emoji = entry.emojiId
        ? entry.emojiName + ' (custom emoji)'
        : entry.emojiName
    const user = entry.userName || entry.userId || 'Unknown user'
    const message = entry.messageId
        ? 'Message ' + entry.messageId
        : 'Message ID unavailable'

    return {
        label: user + ' · ' + emoji,
        subLabel: message,
    }
}

export default function SettingsComponent({
    api: { jsonStorage: storage },
}: SettingsProps) {
    const data = storage.use() ?? { entries: [], nextId: 1 }
    const entries = data.entries ?? []
    const latestEntries = [...entries].reverse().slice(0, 100)

    const clearHistory = () => {
        void storage.set({ entries: [], nextId: 1 }, true)
    }

    return (
        <Page>
            <TableRowGroup
                title={'Removal history (' + entries.length + ')'}
                description="Saved independently from reaction pills. Entries stay here when an emoji row disappears, when someone reacts again, or when the message is deleted. The user shown is the account whose reaction was removed."
            >
                {latestEntries.length === 0 ? (
                    <TableRow
                        label="No removed reactions logged yet"
                        subLabel="New removals will appear here."
                    />
                ) : (
                    latestEntries.map(entry => {
                        const row = describeEntry(entry)
                        return (
                            <TableRow
                                key={entry.id}
                                label={row.label}
                                subLabel={row.subLabel}
                            />
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
