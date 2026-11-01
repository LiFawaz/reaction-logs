import Page from '@valenced/components/Page'
import { Design } from '@valenced/discord/design'
import type { PluginSettingsComponent } from '@valenced/plugins/types'
import type { ComponentProps } from 'react'
import { setTimeFormat24Hour } from './native'
import { DefaultSettings } from './shared'
import type { Settings } from './shared'

const { TableRadioGroup, TableRadioRow } = Design

type SettingsProps = ComponentProps<
    PluginSettingsComponent<{ jsonStorage: Settings }>
>

export default function SettingsComponent({
    api: { jsonStorage: storage },
}: SettingsProps) {
    const { clockFormat } = { ...DefaultSettings, ...storage.use() }

    const changeClockFormat = (format: Settings['clockFormat']) => {
        storage.set({ clockFormat: format })
        setTimeFormat24Hour(format === '24h')
    }

    return (
        <Page>
            <TableRadioGroup
                title="Timestamp clock"
                description="Long-press a red reaction to show when it was removed and the user whose reaction disappeared. Uses your phone's local timezone."
                value={clockFormat}
                onChange={changeClockFormat}
            >
                <TableRadioRow
                    label="12-hour"
                    subLabel="Example: 11:28 AM"
                    value="12h"
                />
                <TableRadioRow
                    label="24-hour"
                    subLabel="Example: 23:28"
                    value="24h"
                />
            </TableRadioGroup>
        </Page>
    )
}
