import { Design } from '@valenced/discord/design'
import { DefaultSettings } from './shared'
import type { Settings } from './shared'

export default function SettingsComponent({ api }: { api: any }) {
    const storage = api?.jsonStorage
    if (!storage || typeof storage.use !== 'function' || typeof storage.set !== 'function') {
        return null
    }

    const cached = storage.use() as Partial<Settings> | undefined
    const config: Settings = { ...DefaultSettings, ...cached }
    const TableRowGroup = Design?.TableRowGroup
    const TableSwitchRow = Design?.TableSwitchRow
    if (!TableRowGroup || !TableSwitchRow) return null

    const update = (patch: Partial<Settings>) => {
        storage.set({ ...config, ...patch })
    }

    return (
        <>
            <TableRowGroup
                title="Reaction Logs"
                description="Choose what stays visible when someone removes a reaction."
            >
                <TableSwitchRow
                    label="Keep Removed Reactions"
                    subLabel="Keep the removed reaction visible in red until Discord is refreshed."
                    value={config.enabled}
                    onValueChange={(value: boolean) => update({ enabled: value })}
                />
            </TableRowGroup>

            <TableRowGroup
                title="Log Details"
                description="The details appear below the message. Tap a name or avatar to open the Discord profile."
            >
                <TableSwitchRow
                    label="Show Username"
                    subLabel="Display the person who removed the reaction."
                    value={config.showUserName}
                    onValueChange={(value: boolean) => update({ showUserName: value })}
                />
                <TableSwitchRow
                    label="Show Avatar"
                    subLabel="Display their Discord profile picture."
                    value={config.showAvatar}
                    onValueChange={(value: boolean) => update({ showAvatar: value })}
                />
                <TableSwitchRow
                    label="Show Local Time"
                    subLabel="Use your device's timezone and language, just like Discord timestamps. Off by default."
                    value={config.showTime}
                    onValueChange={(value: boolean) => update({ showTime: value })}
                />
            </TableRowGroup>
        </>
    )
}
