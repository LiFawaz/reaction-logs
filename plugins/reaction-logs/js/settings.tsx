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
                subLabel="Removed reactions are highlighted directly on their reaction pills. No timestamp or remover label is shown."
            />
        </TableRowGroup>
    )
}
