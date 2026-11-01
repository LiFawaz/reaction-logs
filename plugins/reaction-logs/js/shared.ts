export interface Settings {
    enabled: boolean
    showUserName: boolean
    showAvatar: boolean
    showTime: boolean
}

export const DefaultSettings: Settings = {
    enabled: true,
    showUserName: true,
    showAvatar: true,
    showTime: false,
}
