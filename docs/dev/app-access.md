# App access (prototype)

Settings → App access controls Claire's standard phone tools by package name. New apps and apps whose permissions have never been set default to allowed, including financial apps. AI classifies apps by their main purpose; the classification, and a failure to classify, do not change permissions. Recommended disabling takes effect only after the user's concrete app list, and does not automatically disable similar apps installed later. Clones with the same package name and work-profile copies share the rules.

After an app is disabled, the standard phone tools refuse to read and operate it automatically, and return `app_blocked`. Changing permissions while a task is running also affects subsequent calls. After the model receives `app_blocked`, it first re-evaluates the route: if there is another app, share target or web path, it continues; if the target app must be used, it explains to the user that the app is disabled in "Settings › App access" and that they can allow it themselves, and does not end with the raw error code. Answers and error explanations follow the language of the user's message. When the block list is empty, nothing is refused because of a missing package name; when the list is non-empty and ownership cannot be confirmed, it waits briefly and re-reads once, and if the target's ownership still cannot be confirmed it returns `app_access_unverified` without changing permissions; if the permission file is corrupted it returns `app_access_unavailable`, which can be explicitly reset in Settings; for authorization-sensitive screens it returns `app_access_protected` and hands over to the user.

When the app access page is open, it only reads the local app list and does not send the content to the model. When "Refresh app list" is tapped and there are still unclassified apps, it first confirms that app names and package names will be sent to the configured AI model for risk classification; the content inside apps is not sent. Recommended disabling lists only financial apps that are still allowed, which can be disabled one by one or all at once; apps already disabled no longer appear.

Page copy follows the interface language. Category names are provided by the UI and core stores no Chinese; classification reasons are written in the interface language, old reasons stay as they are after a language change and are updated the next time classification runs. Categories changed manually are not overwritten by AI; the UI displays them as 「用户选择」/「Set by you」.

When the user actively shares the screen of a disabled app, they can choose:

- **Allow screen sharing only**: afterwards the app's screen can be actively shared, while automatic reading and operation remain prohibited; can be revoked on the app access page.
- **Allow this app**: restores normal calls and continues this sharing.
- **Cancel sharing**: no capture, no sharing, no permission change.

Active sharing includes images and text on the same screen. The source is confirmed before capture, and permission is re-checked after capture and before sending; multiple windows must cover all involved apps. Allowing this app clears the separate sharing permission, and re-disabling requires re-authorization.

When the list is empty, app access does nothing extra: it does not query windows, and no app-access rules are added to the system prompt. When the list is non-empty, screen reading checks against the window ownership carried by the screen it reads; when the window has not changed, the immediately following read reuses the just-obtained check result (at most 1 second).

When opening an App with `wait` while another task releases it, check the target first before waiting: a disabled app returns `app_blocked` on the spot instead of waiting for it. Waiting only looks at the occupancy registry, does not read the screen, and does not go through the window check.

## Prototype boundaries

This feature only constrains the standard phone tools; shell commands, the sandbox and computer access (MCP) are not subject to this gate. Arbitrary Android shell and Linux commands keep their existing capabilities and CommandSteer behavior. Scripts calling standard phone tools are still subject to the gate; arbitrary commands themselves and same-UID file access are not the isolation target of this version, so it cannot be claimed that app access rules resist these path bypasses. The prompt asks the AI to respect disable decisions, but it is not a hard block for the shell.

Third-party apps may still launch a disabled app. The package-name check stops standard tools from following up, but cannot withdraw input already submitted to Android, nor does it promise zero races between checking and input. History and content already sent to the model are not retroactively deleted. Recognizing an app uninstall/reinstall depends on a list refresh and is not an identity isolation guarantee.
