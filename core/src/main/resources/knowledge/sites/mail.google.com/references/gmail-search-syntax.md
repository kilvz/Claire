# Gmail search syntax

You can type it into the search box at the top, or encode it and put it after `#search/` in the address. Separate multiple conditions with spaces; all of them must match.

| What you want to find | How to write it |
|---|---|
| Location | `in:inbox`, `in:sent`, `in:drafts`, `in:trash`; `in:anywhere` also searches spam and trash |
| Status | `is:unread`, `is:starred`, `is:important` |
| Labels and categories | `label:work`, `category:promotions`, `category:social` |
| Sender, recipient, cc | `from:alice@example.com`, `to:`, `cc:` |
| Subject | `subject:invoice` |
| Attachments | `has:attachment`; `filename:pdf` by attachment name or type |
| Size | `larger:5M`, `smaller:1M` |
| Relative time | `newer_than:7d`, `older_than:30d`; units d = days, m = months, y = years |
| Absolute date | `after:2026/02/01`, `before:2026/03/01` |
| Either matches | `from:alice OR from:bob` |
| Exclude | `-from:noreply@` |
| Grouping | `(from:alice OR from:bob) subject:meeting` |
| Exact phrase | `"exact phrase"` |

Example: unread messages from the last day, excluding the promotions category: `is:unread newer_than:1d -category:promotions`
