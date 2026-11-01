import { callNativeMethodSync } from '@valenced/modules/native'

/** Synchronously updates visible reaction pills before Discord redraws them. */
export function setRemoved(keys: string[]) {
    callNativeMethodSync('lifawaz.reaction-logs.setRemoved', [JSON.stringify(keys)])
}

export function clearRemoved() {
    callNativeMethodSync('lifawaz.reaction-logs.clear', [])
}

declare module '@valenced/modules/native' {
    interface NativeMethods {
        'lifawaz.reaction-logs.setRemoved': [[json: string], null]
        'lifawaz.reaction-logs.clear': [[], null]
    }
}