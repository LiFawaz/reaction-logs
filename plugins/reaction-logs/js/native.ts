import { callNativeMethodSync } from '@valenced/modules/native'

/** Sends the current per-reaction deletion entries to the Android view hook. */
export function setRemoved(json: string) {
    callNativeMethodSync('lifawaz.reaction-logs.setRemoved', [json])
}

export function setTimeFormat24Hour(enabled: boolean) {
    try {
        callNativeMethodSync('lifawaz.reaction-logs.setTimeFormat24Hour', [enabled])
    } catch {
        // Settings can still be saved while the native plugin is stopped.
    }
}

export function clearRemoved() {
    callNativeMethodSync('lifawaz.reaction-logs.clear', [])
}

declare module '@valenced/modules/native' {
    interface NativeMethods {
        'lifawaz.reaction-logs.setRemoved': [[json: string], null]
        'lifawaz.reaction-logs.setTimeFormat24Hour': [[enabled: boolean], null]
        'lifawaz.reaction-logs.clear': [[], null]
    }
}
