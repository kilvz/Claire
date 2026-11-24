---
name: google-calendar
display: Google Calendar
packages: ["com.google.android.calendar"]
aliases: ["Google Calendar", "Google 日历", "谷歌日历", "日历"]
sites: ["calendar.google.com"]
open: Google Calendar
description: Scheduling and time management with event creation, multi-view switching and reminders
---
## What it is
Google Calendar is Google's official scheduling and time management app, package name `com.google.android.calendar`. For website knowledge see `calendar.google.com`. It supports creating calendar events and to-do tasks on the phone and viewing your schedule.

## UI
The left of the top bar is the hamburger side menu, the middle is the current month title (tap to expand the month calendar and jump to a date), and the right has a back-to-today button (a calendar icon carrying today's date), a search magnifier and the account avatar.
- **Side drawer**: switches the main views: 「日程 (Schedule, continuous flow)」, 「日 (Day)」, 「3天 (3 Days)」, 「周 (Week)」, 「月 (Month)」; below it lists each Google account and the sub-calendars under it (personal events, work, Tasks, birthdays, public holidays) with multi-select checkboxes.
- **Floating create button (+)**: the colored 「+」 button at the bottom right; tapping it pops up the options 「事件 (Event)」 and 「任务 (Task)」.
- **Event detail page**: tapping any schedule block slides out a full-screen page showing the title, start and end times, location, Google Meet link, attendee list (with response status: accepted/tentative/declined) and detailed notes. The top has a pencil edit icon and a three-dot delete menu.

## Features
- **Switching views to look at the schedule**:
  - To find the schedule for a particular day, it is recommended to switch the sidebar to the 「日」 (Day) or 「日程 (Schedule)」 view and scroll along the timeline to read event details and conflicting time slots.
- **Creating a new event**:
  - Tap 「+」 at the bottom right and choose 「事件」 (Event), then type the event title at the top.
  - **Setting the time**: the default is not an all-day event. For an event at a specific time, tap the start and end time wheels to adjust them precisely; for an all-day matter, turn on the 「全天 (All-day)」 switch.
  - **Adding a location and notifications**: type the address under 「添加位置」 (Add location), and set a reminder 10 minutes or 1 day ahead under 「添加通知」 (Add notification).
  - Tap 「保存 (Save)」 at the top right to finish creating the event.
- **Deleting and editing repeating events**:
  - Tap an event card to enter it, then choose 「删除」 (Delete) from the three-dot menu at the top right. For a recurring event, the system pops up a confirmation box: 「仅此活动 (This event only)」, 「此活动及后续所有活动」 (This and all following events) and 「所有活动」 (All events), and you need to pick exactly what the task instruction says.

## Known issues
- **Multiple accounts and hidden calendars**: if several Google accounts are signed in on the phone, events may belong to different work or personal calendars. If a scheduled event is not visible in the main view, expand the side drawer first and make sure the target calendar is checked and shown.
- **Permission prompts**: the first use may request the 「日历读取与写入」 (calendar read and write) system permission.
