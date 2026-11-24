---
name: reddit
display: Reddit
packages: ["com.reddit.frontpage"]
aliases: ["Reddit", "reddit", "红迪"]
open: Reddit
description: Community forum: subreddits, posting, voting, comment replies and private messages
---
## What it is
Reddit is a global community forum and content aggregation app, package name `com.reddit.frontpage`. Content is organized by subreddit (prefix `r/`), and users build community reputation (Karma) through posting, upvote/downvote voting, comment replies and follow interactions.

## UI
The bottom navigation contains five tabs: 「Home」, 「Communities」, 「+ (create post)」, 「Chat」 and 「Inbox」.
- **Home**: at the top left you can switch between 「Home」 (your subscribed communities), 「Popular」 (trending) and 「Watch」 (video feed); on the right are the search magnifier and your personal avatar. A post card shows the subreddit name (`r/...`), poster, posting time, title, body or image/video, upvote/downvote buttons, total comment count and share.
- **Communities list**: you can view 「Recently Visited」, 「Your Communities」 and 「Custom Feeds」. After entering a specific subreddit, the top has the subreddit banner, description, member count and a 「Join」 button.
- **Post detail page**: tap a card to enter; above is the full post body or full-width media, below is a sort toggle (Best, Top, New, Controversial), and the lower half is a multi-level nested comment tree. Each comment has a collapse line on its left, and below it are the vote buttons, 「Reply」 and share.
- **Post editor**: tap 「+」 in the bottom bar to enter. At the top is the subreddit picker 「Select a community」, below it is the post type selection bar (Text, Images, Video, Link, Poll), in the middle are the title and body boxes, below them are 「Add tags and flair」 plus NSFW and Spoiler toggles, and the publish button 「Next / Post」 is in the top right.

## Features
- **Browsing and voting (Upvote/Downvote)**:
  - Posts and comments both have up and down arrows: tapping the up arrow (Upvote) turns it orange to agree; tapping the down arrow (Downvote) turns it purple to disagree; tapping a highlighted arrow again undoes the vote.
- **The full posting flow (Create a Post)**:
  - Tap 「+」 in the bottom bar, then search in the top 「Select a community」 and choose the target subreddit.
  - Enter the required 「Title」; depending on the selected type, type the body, add images or paste an external link.
  - **Add the required flair**: many subreddits require a flair. If 「Next」 or 「Post」 in the top right is grayed out and unclickable, first tap 「Add tags and flair」, select one in the flair list that slides up from the bottom (such as Discussion, News, etc.) and tap 「Apply」; only then does the button light up.
  - Tap 「Post」 in the top right to publish.
- **Commenting on the main post and threaded replies (Comment & Reply)**:
  - **Commenting on a post**: type in the 「Add a comment」 box that is always at the bottom of the post detail page, and tap the blue paper-plane/arrow on the right to send.
  - **Replying to someone else's comment**: below a specific comment card, tap the 「Reply」 button with an arrow, write in the reply box that pops up and send; this creates a tree-nested reply.
  - **Collapsing the comment tree**: tap the comment body lightly or long-press to collapse that comment and all its sub-replies into one line, so you can quickly skip to the next independent top-level comment.
- **Ways to build up Karma (reputation points)**:
  - Reddit points are divided into post karma and comment karma.
  - For a new account, the first choice for building points is large general subreddits with a low bar for participation (such as `r/AskReddit`, `r/casualiama`, `r/aww`). Write constructive, resonant comments under active discussion posts (sorted by Rising or Hot) to get Upvotes, or share high-quality image and text content in subreddits that allow new users.
- **Search scope**:
  - Search on the home page is global site-wide search; after entering a specific subreddit, the search bar is prefixed with `r/subreddit name` and search is limited to that community's historical posts and comments.

## Known issues
- **Leftover state**: if you quit while on a post or in a deep comment section, reopening goes straight back to that post. Tap 「←」 in the top left to return to the parent subreddit or the home page.
- **NSFW / Spoiler masking**: content that is sensitive or spoilery is gaussian-blurred by default, the card carries an 「18+」 or 「Spoiler」 mark, and you must tap 「Yes / View」 in the middle to confirm before the blur is removed.
- **New accounts and Karma thresholds**: strict subreddits (such as certain trading, question or tech subreddits) have filter rules for account age or minimum Karma. If you don't meet them, your post is automatically removed by the AutoModerator bot, which sends a private message in Inbox explaining why.
- **Rating and notification pop-ups**: in-app prompt cards such as 「Rate Reddit」 and 「Turn on notifications」 pop up frequently; tap 「Not now」 or 「No thanks」 to close them.
