---
name: github.com
display: GitHub
description: Code hosting and collaboration site for browsing repositories, code, issues and pull requests
domains: ["github.com"]
aliases: ["GitHub", "github"]
apps: ["github"]
open: github.com
mode: desktop
---
## What it is
A code hosting and collaboration site; a repository contains code, documentation, issues and pull requests.

## Pages and URLs
- Repository: `https://github.com/<owner>/<repo-name>`.
- A repository's issues and pull requests are at `/issues` and `/pulls` after the repository address respectively.
- Search: `https://github.com/search?q=<encoded term>&type=repositories`; you can also search types such as code and issues.

## Features
- **Repositories**: public repositories usually require no login to view files, README, commits, branches, issues and pull requests; private repositories need an account with permission.
- **Star**: the repository page has a Star button in the top right, for bookmarking and showing support; it requires login. When the current account has already starred, the button shows Starred; the button state distinguishes whether it has been starred.
- **Account**: the content and operations visible after logging in depend on account permissions; the interface button text changes with the interface language.

## Known issues
- When a repository does not exist, is private, or the current account has no access, the page may only show a page-not-found.
- The Star count may use abbreviations such as `1.2k`.
