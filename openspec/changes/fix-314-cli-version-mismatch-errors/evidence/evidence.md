# fix-314 evidence

## 2026-11-18 end-to-end measurement (simulating an old phone's HTTP service)

Simulating `GET /api/v1` returning `server_version=0.1.33`, `capabilities=["tasks","task_steer"]`:

```
$ mika … --json task run --goal test --request-id 00000000-0000-4000-8000-000000000001
{"error":{"code":"incompatible_api","message":"The Mika app on the phone (version 0.1.33) does not advertise 'task_recovery'. Rerun without --request-id to submit anyway, or update Mika on the phone to keep requests recoverable.","capability":"task_recovery","server_version":"0.1.33"}}
exit=2

$ mika … --json task export t1
{"error":{"code":"incompatible_api","message":"The Mika app on the phone (version 0.1.33) does not advertise 'run_export'. Update Mika on the phone to export runs.","capability":"run_export","server_version":"0.1.33"}}
exit=2
```

For comparison, the old copy (a single sentence):
- old: `"Update the phone app to submit a recoverable request."`
- old: `"This Mika APK does not support run export."`

## Unit tests

`python3 -m unittest discover -s companions/mika/tests`: all 89 pass (including 2 new cases and 3 strengthened assertions).
