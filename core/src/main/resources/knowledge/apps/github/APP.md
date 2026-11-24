---
name: github
display: GitHub
packages: ["com.github.android"]
aliases: ["GitHub", "github"]
sites: ["github.com"]
open: GitHub
description: Code hosting and open source collaboration with repositories, issues, pull requests and notifications
---
## What it is
GitHub is the developer app for code hosting and project collaboration, package name `com.github.android`. For website knowledge see `github.com`.

## UI
The bottom navigation has four tabs: 「主页 (Home)」, 「通知 (Notifications)」, 「探索 (Explore)」 and 「个人资料 (Profile)」.
- **Home**: shows 「我的工作 (My Work)」 shortcuts: Issues, Pull Requests, Discussions, Repositories, Organizations and Starred. Below are the pinned repositories (Pinned).
- **Repository page**: the repository owner/name and the branch switcher (Branch) at the top; below are the 「代码 (Code)」, 「议题 (Issues)」, 「拉取请求 (Pull Requests)」 and 「讨论 (Discussions)」 tabs.
- **Notifications page**: grouped into Inbox, Saved and Done, and you can quickly swipe a notification to mark it Done or read it later.
- **Pull request (PR) and issue details**: the title, status badge (Open, Merged, Closed), author and timestamp, timeline conversation, review status (Reviewers) and checks. The PR detail page has 「文件变动 (Files Changed)」 and 「提交 (Commits)」.

## Features
- **Browsing code and branches**:
  - After entering a repository, tap the branch dropdown to switch to a particular development branch or tag. Tap through directories in the file tree to go deeper, and tap a source file to read the rendered code or the Markdown description.
- **Handling and replying to issues/PRs**:
  - Type the reply into the comment box at the bottom of the issue or PR and tap 「Comment」 to submit.
  - Maintainers tap 「Close issue」 at the bottom to close an issue; on the PR screen, when the merge conditions are met and CI is all green, they can tap 「Merge pull request」 and confirm the merge.
- **Two-factor authentication (2FA / GitHub Mobile)**:
  - When signing in to GitHub on a computer, the phone often shows a full-screen 「是否正在尝试登录?」 (are you trying to sign in?) with a two-digit number to match, and you have to tap the digits on the phone screen that match the computer's to complete the authorization.

## Known issues
- **Leftover state**: quitting and reopening often stays on the particular issue last viewed or in a code diff view; tap 「←」 at the top left to go back up to the repository home page level by level.
- **Collapsed CI checks**: if a PR's Checks module contains several automated workflows, it shows only an aggregate status (such as 「13/14 passed」), and you need to tap the whole row to open a second-level page to see which one failed.
- **Choosing a merge method**: when tapping Merge pull request, if the repository supports several merge strategies, a menu with 「Create a merge commit」, 「Squash and merge」 and 「Rebase and merge」 pops up; be careful to choose the intended mode.
