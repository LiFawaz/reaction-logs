import { Design } from '@valenced/discord/design'
import { DefaultSettings } from './shared'
import type { Settings } from './shared'

export default function SettingsComponent({ api }: { api: any }) {
    const storage = api?.jsonStorage
    if (!storage || typeof storage.use !== 'function' || typeof storage.set !== 'function') {
        return null
    }

    const cached = storage.use() as Partial<Settings> | undefined
    const enabled = typeof cached?.enabled === 'boolean' ? cached.enabled : DefaultSettings.enabled
    const TableRowGroup = Design?.TableRowGroup
    const TableSwitchRow = Design?.TableSwitchRow
    if (!TableRowGroup || !TableSwitchRow) return null

    return (
        <TableRowGroup>
            <TableSwitchRow
                label="Keep Removed Reactions"
                subLabel="Keep removed reactions visible in this session, mark them red, and show the removal time."
                value={enabled}
                onValueChange={(value: boolean) => storage.set({ ...cached, enabled: value })}
            />
        </TableRowGroup>
    )
}