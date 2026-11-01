import { Design } from '@valenced/discord/design'

export default function SettingsComponent() {
    const TableRowGroup = Design?.TableRowGroup
    const TableRow = Design?.TableRow

    if (!TableRowGroup || !TableRow) return null

    return (
        <TableRowGroup
            title="Reaction Logs"
            description="Removed reactions are always retained locally while this plugin is running."
        >
            <TableRow
                label="Keep removed reactions"
                subLabel="Always on. Each deleted reaction is marked red and gets its own log directly underneath it."
            />
        </TableRowGroup>
    )
}
