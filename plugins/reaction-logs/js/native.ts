import { callNativeMethodSync } from '@valenced/modules/native'

/** Updates only the per-user removal log shown in reaction details. */
export function setRemovalLogs(json: string) {
    callNativeMethodSync('lifawaz.reaction-logs.setRemovalLogs', [json])
}

/** Updates only the local red-pill retention state. */
export function setRetainedPills(json: string) {
    callNativeMethodSync('lifawaz.reaction-logs.setRetainedPills', [json])
}

/** Clears both native layers when the plugin stops. */
export function clearNativeState() {
    callNativeMethodSync('lifawaz.reaction-logs.clear', [])
}

declare module '@valenced/modules/native' {
    interface NativeMethods {
        'lifawaz.reaction-logs.setRemovalLogs': [[json: string], null]
        'lifawaz.reaction-logs.setRetainedPills': [[json: string], null]
        'lifawaz.reaction-logs.clear': [[], null]
    }
}
