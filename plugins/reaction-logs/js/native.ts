import { callNativeMethodSync } from '@valenced/modules/native'

export function setRemoved(json: string) {
    callNativeMethodSync('lifawaz.reaction-logs.setRemoved', [json])
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
