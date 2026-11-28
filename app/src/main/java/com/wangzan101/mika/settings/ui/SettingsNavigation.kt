package com.wangzan101.mika.settings.ui

/** Subpages reachable from settings; [Home] is the settings home and [Advanced] groups connection, maintenance and engineering capabilities. */
internal enum class SettingsPage { Home, Advanced, AppAccess, SmsLogin, PhoneOps, Calls, Wechat, Telegram, DuringWork, AppPace, Capsule, Language, Notifications, Recommendations, Checklists, Workflows, Sandbox, Plugins, EnvVars, Recovery, SelfCheck, ComputerAccess, McpServers, Root, Developer, DeviceDirectories, RunStats, Proactive, Backup, Pause }

/**
 * The parent of each subpage. The top bar's back button and the system back key both follow this table, so do not repeat it per subpage:
 * two separate copies drift and send Back straight to the settings home. [Home] has no parent and returns to itself.
 */
internal fun SettingsPage.parent(): SettingsPage = when (this) {
    SettingsPage.Home -> SettingsPage.Home
    SettingsPage.Advanced, SettingsPage.AppAccess, SettingsPage.SmsLogin, SettingsPage.PhoneOps,
    SettingsPage.Calls, SettingsPage.Wechat, SettingsPage.Telegram, SettingsPage.DuringWork, SettingsPage.Capsule,
    SettingsPage.Language, SettingsPage.Notifications, SettingsPage.Recommendations,
    SettingsPage.Proactive, SettingsPage.Backup, SettingsPage.Pause -> SettingsPage.Home
    SettingsPage.AppPace -> SettingsPage.DuringWork
    SettingsPage.Checklists, SettingsPage.Workflows -> SettingsPage.Recommendations
    SettingsPage.Sandbox, SettingsPage.Plugins, SettingsPage.EnvVars, SettingsPage.Recovery,
    SettingsPage.ComputerAccess, SettingsPage.McpServers, SettingsPage.Root, SettingsPage.Developer -> SettingsPage.Advanced
    SettingsPage.SelfCheck -> SettingsPage.Recovery
    SettingsPage.DeviceDirectories, SettingsPage.RunStats -> SettingsPage.Developer
}
