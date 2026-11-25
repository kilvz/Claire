# Using phone folders granted by the system

On the "Phone folders" page in Settings, choose "Choose folders for read-only access" or "Choose readable and writable folders", then grant access with the Android system picker. Either kind of grant lets Claire find files in that directory and its subdirectories and import ordinary files such as PDFs, images and spreadsheets into the current workspace. For example, after granting "Work documents" you can ask Claire to find the expense forms in it. In read-write mode, and when the document provider allows writing, Claire can also create, replace or append UTF-8 text. Tapping "Revoke access" withdraws the directory grant; copies already imported into the workspace remain usable.

System-granted folders are independent of the phone-control experimental switch. The tools require a device that supports the files and computing environment, and the user must start an attended task from the UI.

## File tools

- `list_folder_files` lists the folders the system has persistently granted when the path is omitted. When the system can confirm the on-device storage location, the Settings page and the tool both show it. For example, "Download/Claire in internal storage" as the user says it can be matched with `/storage/emulated/0/Download/Claire` in `displayLocation`. The same entry's `/folders/…` identifier is used by later file operations. When there is only a display name and several possible targets, ask the user before modifying anything. Passing a directory identifier lists up to 200 direct children and returns the writable state and a truncation flag.
- `search_device_files` recursively searches a given directory by file name, extension or modification time. The search can scan entries beyond the list preview. When the depth, elapsed time, entry count or match count reaches its limit, the result states the reason for truncation.
- `copy_device_file` copies an ordinary file unchanged into `/workspace/inbox/` in the current workspace and returns the actual file name and byte count. A single file is at most 200 MiB; name collisions get a numbered suffix. Virtual documents must first be exported as ordinary files by their provider.
- `read_file`: accepts the `/folders/…` identifier returned by the list, reads text in byte-sized pages, and also returns the SHA-256 of the whole file.
- `write_file`: can create, replace or append text. Creation passes no version; an existing file must be passed the expectedSha256 returned by the previous read, and nothing is written if the version does not match.
- `share_file`: saves a text file produced in the workspace to the location given by folderPath; an existing target likewise requires expectedSha256. When folderPath is omitted, the original download directory and file-card behaviour are kept.

Scripts work on the `/workspace/…` copy returned after import. To read and write text directly in a granted directory, pass the `/folders/…` identifier returned by the list to the file tools. Each text read or written in place is at most 1 MiB.

## Implementation and limits

The grant comes from Android's persistable URI permission and is verified each time; UI operations, writes and revocation run serially under the same process lock. Revocation waits for a write that has already started to finish, and later operations are refused. A directory lookup checks at most 2000 entries, duplicate file names are refused for writing, and targets are never chosen by guessing a path.

The original version is checked before an update and the content is read back and verified after writing. Android document providers have no general atomic-replace interface: if a write fails, Claire tries to restore the old content or delete the leftovers created this time; if the restore fails, it reports explicitly that the file may have changed instead of falsely claiming success. Hash checks can detect a modification made after reading, but they cannot stop another app from editing the same file between the final check and the write.

Import uses a streaming copy and reuses the workspace attachment's space check and failure cleanup. Access to a remote document provider takes as long as that provider's own response.

## Verification

Local coverage includes tool routing, Chinese paging, version parameters, the export branch, path checks, size limits, and inaccessibility from remote/unattended tasks. Existing workspace tools, directory grants, resources and prompt checks are regressed together.

`GrantedFolderFilesDeviceTest` uses the real system picker and a dedicated MikaFolderTest645 folder to cover grant persistence, read-only grants, Markdown version checks and export, searching files beyond the list preview, importing a binary file larger than 1 MiB, and revoking a grant within the same task. Before running, create the empty dedicated directory, select it in the system picker during the run, and clean up the test files afterwards.
