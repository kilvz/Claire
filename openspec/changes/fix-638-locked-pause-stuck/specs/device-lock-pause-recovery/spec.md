# device-lock-pause-recovery capability spec (added)

## ADDED Requirements

### Requirement: Recovery from a lock-screen pause does not depend on broadcast delivery
After a task pauses at 「等你解锁手机」 (waiting for you to unlock the phone) because of screen-off/lock, the recovery decision MUST be driven by actively re-reading the system state:
as long as a task is waiting for this state and the last reading is still 「锁着」 (locked — screen off or lock screen both count), keep re-reading at the set polling period;
broadcasts (SCREEN_ON / USER_PRESENT) serve only as acceleration signals for an immediate re-read and MUST NOT become the only route to recovery.

#### Scenario: Automatic continuation even when all broadcasts are lost
- **WHEN** a task is paused waiting for unlock, the system delivers no SCREEN_ON / USER_PRESENT broadcast afterwards, and the user turns the screen on and unlocks the phone
- **THEN** on the next poll reading 「亮屏且已解锁」 (screen on and unlocked), the pause lifts automatically and the task continues, with no button for the user to tap

#### Scenario: Polling does not stop while the screen is off
- **WHEN** the last reading was screen-off (interactive=false)
- **THEN** polling still continues (otherwise nothing would trigger a re-read after the screen is turned on)

#### Scenario: No system reads when nobody is waiting
- **WHEN** no subscriber is waiting for this lock state
- **THEN** no periodic system reads are issued

### Requirement: Calibrate the old state when a subscription starts
When a new subscriber starts waiting for the lock state, it SHOULD re-read the system state once immediately, so the stale value is calibrated as soon as the wait begins,
rather than waiting for the first polling period.

#### Scenario: Self-healing on subscription
- **WHEN** the state holds a stale 「锁着」 (locked) while the system is actually unlocked, and a subscriber starts waiting
- **THEN** a re-read happens immediately after the subscription starts, the state becomes 「未锁」 (unlocked) right away, and the waiter is released at once

### Requirement: The recovery decision still uses the system's true state
Recovery only accepts the re-read true value (screen on and device unlocked) and does not proceed because a broadcast arrived or the unlock flow claims success;
during a true lock (e.g. the window where the unlock broadcast arrives before the lock screen dismisses) it MUST NOT proceed early.

#### Scenario: Unlock broadcast arrives first while the device is still locked
- **WHEN** the re-read shows the screen on but `isDeviceLocked` is still true
- **THEN** the pause is kept until the device is read as truly unlocked
