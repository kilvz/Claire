# Structure of cards and links

Article cards are in `.news-list li` on the page (falling back to `ul li` when that cannot be retrieved); each card contains the official-account name and a redirect href.

In a batch of results, if there are at least two non-empty and mutually distinct links, the links can serve as the entry for telling the cards apart; if there are duplicates, or the whole batch has only one, the links cannot distinguish the cards, and in that case you can only locate a result by the search page address, the keyword and the card text.

The redirect href is encrypted and short-lived, and the same address may be reused by several entries. The account name is a self-chosen name and does not represent the real-name entity.
