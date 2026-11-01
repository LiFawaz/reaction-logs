import { Design } from '@valenced/discord/design'

export default function SettingsComponent() {
    const TableRowGroup = Design?.TableRowGroup
    const TableRow = Design?.TableRow

    if (!TableRowGroup || !TableRow) return null

    return (
        <TableRowGroup
            title="Reaction Logs"
            description="Removed reactions are retained locally while this plugin is running."
        >
            <TableRow
                label="Keep removed reactions"
                subLabel="Removed reaction pills are highlighted red and show their removal time beside the emoji in your device's local 24-hour time."
            />
        </TableRowGroup>
    )
}
