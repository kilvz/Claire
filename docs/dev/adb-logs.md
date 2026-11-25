# Reading full runtime logs on a USB-connected phone

`./gradlew :app:assembleLogs` builds the local inspection package `app-logs.apk`, with the display
name **Claire Logs** and the package name `com.kilv.claire.logs`. It uses the local debug signature,
installs side by side with the official Claire, and does not overwrite the original app's conversations,
keys and settings.

This build enables Android `debuggable`, so a computer with USB debugging authorization can read this
app's private directory through `adb run-as`; it does not change other apps' access permissions.
Detailed traces are on by default and can also be turned off in Claire's settings. After a run ends, the
existing debug-build logic also mirrors `run.json`, `events.jsonl` and screenshots into the external
files directory `runs/`.

After installing and configuring a model and the phone assistant, run one task, then pull the records:

```sh
adb -s SERIAL install -r app/build/outputs/apk/logs/app-logs.apk
adb -s SERIAL shell run-as com.kilv.claire.logs ls files/runs
python3 tools/pull-logs.py --device SERIAL --output claire-logs.tar
```

An adb outside PATH can be specified with `--adb /absolute/path/to/adb`.
The archive contains only `files/runs` (summaries, tool parameters and results, screenshots) and
`no_backup/harness-traces` (recorded detailed traces), and does not export model keys or the whole app data.
For consistent records, export after the task completes. Traces follow the existing redaction, length
limits and storage quota, and do not include the model's hidden reasoning; requests and responses that
were not recorded before the run cannot be recovered afterwards.

The official build and the local package have different signatures, so it cannot be installed as an
in-place update. This package also cannot read old tasks from the official version's private directory.
The debug flags, trace defaults and package name of the other build types keep their existing configuration.
