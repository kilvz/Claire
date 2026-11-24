---
name: xiaoyuzhoufm.com
display: Xiaoyuzhou
domains: ["xiaoyuzhoufm.com"]
aliases: ["小宇宙", "Xiaoyuzhou"]
apps: []
open: xiaoyuzhoufm.com
mode: desktop
description: Chinese podcast platform; episode pages provide the audio URL, title and duration
---
## What it is
A Chinese podcast platform. Each episode has a public episode page, and the page carries the audio URL and metadata.

## Pages and URLs
Episode page `https://www.xiaoyuzhoufm.com/episode/<id>`, where `<id>` is 20 to 32 lowercase hexadecimal digits (`[0-9a-f]{20,32}`).

## API
The full HTML of an episode page is about 300KB, and only three fields need to be extracted; in the sandbox, fetch the page with `wget` and then filter with `grep -oE`:

```sh
wget -qO- 'https://www.xiaoyuzhoufm.com/episode/<id>' | grep -oE '<meta property="og:audio" content="[^"]+"|"duration":[0-9]+|<meta property="og:title" content="[^"]+"'
```

- `og:audio`:the real URL of the audio file, usually under `https://media.xyzcdn.net/`.
- `duration`: the episode duration, in seconds.
- `og:title`: the episode title.

## Known issues
After a page redesign, `og:audio` may be missing, and in that case the audio URL cannot be retrieved from the page.
