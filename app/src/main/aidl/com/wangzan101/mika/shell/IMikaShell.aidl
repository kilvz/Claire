package com.wangzan101.mika.shell;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Surface;

// A resident service running in a process with the shell identity (uid 2000): the built-in helper started through wireless debugging.
interface IMikaShell {
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay(int displayId) = 2;
    // 0=started 1=the user is using the app on the main screen (do not start, do not disturb the main screen) 2=no entry point to start 3=error
    int startApp(int displayId, String packageName) = 3;
    int topTaskId(int displayId) = 4;
    boolean moveTask(int taskId, int displayId) = 5;
    boolean injectKey(int displayId, int keyCode) = 6;
    boolean injectTap(int displayId, int x, int y) = 7;
    boolean setAudioMuted(String packageName, boolean muted) = 8;
    // Cold start: end the app's process first, then start it; return value same as startApp, and when the user is using it on the main screen it is not ended and 1 is returned;
    // when the app has tasks outside Claire's screens it is not ended; a normal start is done instead and 4 is returned
    int restartApp(int displayId, String packageName) = 9;
    // The package's current PLAY_AUDIO mode (allow/ignore/default…); null when it cannot be read
    String getAudioMode(String packageName) = 10;
    // Set PLAY_AUDIO back to the given mode; used to restore from the persisted record after Claire exits abnormally
    boolean setAudioMode(String packageName, String mode) = 11;
    // The screen the task is on; -1 when the task no longer exists
    int displayOfTask(int taskId) = 12;
    // The package that owns the task's top page; null when the task does not exist
    String packageOfTask(int taskId) = 13;
    // With the shell identity, set the clipboard to this plain text (without showing the system "copied" overlay), for pasting into an editor that only accepts real input.
    // The content is marked sensitive (API 33+). The original clipboard is recorded before the first set; when the original clipboard cannot be read it is left alone. Returns 0=set 1=cannot read the original clipboard (unchanged) 2=error
    int setClipText(String text) = 14;
    // Restore the clipboard from before setClipText (empty it if it was empty); when the user already replaced it, leave it alone and treat it as done, and when it cannot be read right now keep the record and return false to restore next time
    boolean restoreClip() = 15;
    // Open a link or an Android settings page (when action is empty, open uri with VIEW; packageName restricts which app opens it).
    // Return value same as startApp, plus 5=no app can open it 6=link not allowed; when the resolved app has a task on the main screen it is not started and 1 is returned
    int startView(int displayId, String action, String uri, String packageName) = 17;
    // The Claire virtual screen the package's task is on (used to check conflicts between parallel tasks); -1 when it is not on any of Claire's screens
    int displayOfPackage(String packageName) = 18;
    // The user's clipboard text before setClipText (an empty string when it was empty or not text); null when there is nothing to restore. Used by Claire to keep an encrypted copy and restore it after the process is killed
    String savedClipText() = 19;
    // While the clipboard still holds ours — the text Claire put there — swap back the user's original text original (empty it if the string is empty); when the user already replaced it, leave it alone
    // Returns true when it is restored, the user already replaced it or no restore is needed; returns false when it cannot be read right now or the write fails, and the caller keeps the encrypted copy
    boolean restoreClipText(String original, String ours) = 20;
    // The [uid, pid] of the process the service runs in; before Claire accepts the built-in helper's Binder it uses this to confirm the helper is in the process that came to hand off
    int[] identity() = 21;
    // Undo the changes Claire's binding left behind (restore the clipboard, unmute, release all virtual screens) without exiting the process; used when the built-in helper unbinds
    void releaseAll() = 22;
    // With the shell identity, connect to Claire's own WebView debugging socket (abstract name webview_devtools_remote_<pid>, pid must be Claire's process),
    // and hand the connected socket back; null when the name does not follow the rule or the connection fails. Used when Claire's own direct connection is blocked by the system
    ParcelFileDescriptor openDevtoolsSocket(String name) = 23;
    // Run a command with the shell identity in the context of the virtual screen already created for this task; returns exitCode and output.
    Bundle runSystemCommand(int displayId, String command) = 24;
    // The fully qualified class name of the top Activity on the current virtual screen; null when there is no task.
    String topActivity(int displayId) = 25;
    // Send Ctrl+key to this screen (only A select-all and V paste are allowed): 0=press not delivered 1=full key delivered 2=possibly only part delivered
    int injectCtrlKey(int displayId, int keyCode) = 26;
    // The raw output of `pm list users`; null on error
    String listUsers() = 27;
    // The raw output of `pm list packages --user <userId>`; null on error
    String listPackages(int userId) = 28;
    // Whether the package is a system package (including updated system apps); false when it cannot be found
    boolean isSystemPackage(String packageName) = 29;
    // Share a piece of text with the package (ACTION_SEND text/plain, EXTRA_TEXT), starting it on this screen; return value same as startApp, plus 5=the package has no page that accepts text shares;
    // when the package has a task on the main screen it is not started and 1 is returned
    int startShare(int displayId, String packageName, String text) = 30;
    // From now on watch the main screen for windowMs: during this time record every task that changes at the top of the main screen (see mainArrivals); return the task at the top of the main screen right now, -1 if none.
    // Called by the Agent before every action: when an app opens another app from the background instead of from its own page on Claire's screen, the system puts it on the main screen, and Claire recognizes it from this and moves it back
    int watchMainScreen(int windowMs) = 31;
    // Tasks that came to the top of the main screen after sinceUptimeMs (SystemClock.uptimeMillis), in order; fields see MainScreenWatch.toBundle. null on error
    Bundle mainArrivals(long sinceUptimeMs) = 32;
    // The user the task belongs to (clones and work profiles are other users); -1 when the task does not exist
    int userOfTask(int taskId) = 33;
    // One-finger stroke on one of Claire's own screens: at times[i] milliseconds (relative to the press) the finger is at (xs[i], ys[i]); the first point presses, the last point lifts.
    // Injected one by one in time order, returning only after all are injected (at most about 10 seconds); returns false when it is not Claire's screen, the arguments are wrong or the press was not injected successfully
    boolean injectStroke(int displayId, in int[] xs, in int[] ys, in long[] times) = 34;
    // System actions (set alarm, timer, open the alarm list, open a prefilled new calendar event, see SystemIntents; no other intent is ever started), starting on this screen.
    // Start with the shell identity first; when the receiving page requires a permission Claire has (such as setting an alarm) and the shell does not, send the asMika Claire prepared instead (start with Claire's identity,
    // the screen is still this one). Return value same as startView: 5=no page accepts it, 6=not on the allowlist; when the receiving app has a task on the main screen it is not started and 1 is returned
    int startSystemAction(int displayId, in Intent intent, in PendingIntent asMika) = 35;
    // Run a setup command with the shell identity (authorization, accessibility list, etc.; the same as running through paired wireless debugging), not limited to one screen; returns exitCode and output.
    // When the helper was started by a computer over USB and wireless debugging was never paired, Claire can only run this kind of command through here
    Bundle runCommand(String command) = 36;
    // Whether the helper should bring Claire back after Claire's process is killed (RestoreWish.flags: 1=bring it back 2=write back the accessibility service before bringing it back).
    // The helper cannot read Claire's settings, only remembers the last copy handed over; Claire hands over one after every handoff and whenever the wish changes
    void setRestoreWish(int flags) = 37;
    // The other users that can open the apps inside (clones, work profiles and the like; rules see AppCopies): users=int[] numbers, summary=one line for diagnostics
    // (number, flags, type, profile group, without names or what is installed); null when users cannot be read
    Bundle appCopyUsers() = 38;
    // Open the package in another user userId, starting it on this screen; only those listed by appCopyUsers are accepted, others return 8. Return value same as startApp / restartApp.
    // Occupancy on the main screen and another Claire screen only considers this copy (package name + user): when the user is using the original copy on the main screen it does not block this copy, and the reverse also holds.
    // With cold only the processes in this user are ended and the other copy is left alone; when userId is Claire's own user it is the same as startApp / restartApp
    int startAppInUser(int displayId, String packageName, int userId, boolean cold) = 39;
    // The Claire virtual screen the package's task in user userId is on; -1 when it is not on any of Claire's screens
    int displayOfPackageInUser(String packageName, int userId) = 40;

    // Main-screen device setup, separate from the virtual-screen key allowlist; credentials are only passed in through Binder, never through the shell command line.
    boolean wakeDevice() = 41;
    boolean dismissDeviceKeyguard() = 42;
    // clear=number of existing characters to delete before typing (0 for an empty field, so an empty field is not closed by DEL on some systems)
    boolean enterDeviceCredential(int kind, in byte[] credential, int clear) = 43;

    // Same as getAudioMode / setAudioMuted / setAudioMode, acting on the copy in user userId (clones, work profiles);
    // when userId is Claire's own user it is the same as the originals. PLAY_AUDIO is set per package + user, and the two copies do not affect each other
    String getAudioModeInUser(String packageName, int userId) = 44;
    boolean setAudioMutedInUser(String packageName, int userId, boolean muted) = 45;
    boolean setAudioModeInUser(String packageName, int userId, String mode) = 46;
    // The package (the userId copy) has a visible task on the main screen, i.e. the user is using it on the main screen right now; returns true when the main screen cannot be seen clearly
    boolean visibleOnMainScreen(String packageName, int userId) = 47;

    // Task finished: handle the apps on this screen by the rules (see AppsAfterTask), then release this screen. Those kept are moved to the main screen, at the very end (out of the user's sight);
    // the ones playing audio and those in close are closed along with the screen. keep / close are keys the model marked (package name, or package name@user for another copy).
    // Returns AppsAfterTask.KEY_*: kept=keys kept, closed=closed "key: reason", note=one reason sentence when it cannot be moved (may be empty). null when it is not Claire's screen
    Bundle finishDisplay(int displayId, in String[] keep, in String[] close) = 48;
    // When the secure lock screen is still on the notification/fingerprint page, do one generic upward swipe directed at SystemUI; contains no credentials.
    boolean revealDeviceCredential() = 49;

    // Only on Claire's virtual screens, in the given top task, type printable ASCII as keyboard events; control characters are not sent
    // 0=not sent 1=all events delivered 2=delivery uncertain (possibly only the prefix was typed)
    int injectAsciiText(int displayId, int taskId, String packageName, String text) = 50;

    // Do one directed tap on SystemUI, for fallback buttons such as "unlock with password" on the biometric-first lock screen page; contains no credentials.
    boolean revealDeviceCredentialTap(int x, int y) = 51;

    // Call audio is independent of call control. 0=auto, 1=direct carrier audio, 2=Android PSTN redirect.
    Bundle probeCallAudio(int mode) = 52;
    // Only for ACTIVE carrier calls; the owner is separate per call and it stops on death. The downstream-readable and upstream-writable anonymous pipes are both mono PCM16LE.
    Bundle openCallAudio(int mode, IBinder owner) = 53;
    void stopCallAudio(IBinder owner) = 54;
    boolean flushCallAudio(IBinder owner) = 55;
    Bundle callAudioStats(IBinder owner) = 56;

    // The absolute path of the current user's /sdcard or /storage/emulated/<user>, sorted by name; the first time afterName=null, limit is 1..128.
    // status=ok/not_found/denied/not_directory/invalid/io_error; on success names, types
    // (0=other or symlink 1=regular file 2=directory) have equal length, and a non-empty next is passed to the next page; the snapshot is not guaranteed while the directory changes.
    Bundle listFiles(String path, String afterName, int limit) = 57;
    // Open a regular file read-only; the result status=ok/not_found/denied/not_file/invalid/io_error,
    // on success fd is a ParcelFileDescriptor, and the caller is responsible for closing it. The path is accessed with the shell's permissions.
    Bundle openFile(String path) = 58;
    // Read-only, bounded search; returns status, paths, visited, truncated and an optional reason.
    Bundle searchFiles(String path, String name, String extension, long modifiedAfter, long modifiedBefore, int maxDepth, int maxEntries, int timeoutMs) = 59;
    // Replace the device-file bridge policy. Empty roots deny all access; protocol and revision are separate.
    Bundle setDeviceFilePolicy(int protocol, String session, long revision, in String[] roots, in boolean[] recursive) = 60;

    // Share a file from the workspace with a package: ACTION_SEND + EXTRA_STREAM, the content URI is granted readable; only opens the share page, does not send.
    int startShareFile(int displayId, String packageName, String uri, String mime, String text) = 61;

    // Change the size of a Claire virtual screen in place and replace the Surface that receives the picture, keeping the tasks on the screen.
    boolean resizeDisplay(int displayId, in Surface surface, int width, int height, int dpi) = 62;

    // Find the task for the same app, preferring the specified display, then the main display, then others. Returns [taskId, displayId]; empty when there is no task and null when reading fails.
    @nullable int[] findTaskForPackage(String packageName, int userId, int displayId) = 63;

    // Reuse the destroy transaction number fixed earlier; AIDL requires either all methods to be numbered or none of them to be
    void destroy() = 16777114;
}
