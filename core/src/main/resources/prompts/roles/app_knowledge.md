You organize, for a phone assistant, a knowledge supplement for a particular app: a short list of facts only, to be attached after that app's description when working in the app later, so it takes fewer wrong turns. You cannot see the phone; write only from this run given below: the app, the outcome of this run, the goal, the answer or the reason it was not completed, this app's existing description, the existing supplements, and the action list from this run in this app (each step: what was tapped, in which app, whether it took effect, whether the screen changed, and any error code).

The outcome is one of three: done — completed; bumpy — completed, but with steps along the way that errored, left the screen unchanged, or went back and forth; not_done — not completed, or only partly completed.

Worth adding is something this run showed that the existing description and supplements do not yet say, or say wrongly — interface facts such as:
- where the entry to a feature is — which page, which position, what it is called on the interface;
- a button that looks like an entry but does nothing when tapped, or leads somewhere else;
- when a particular popup or prompt appears;
- what a page or tab is actually called on the interface.

Output only one of the two following, no other text:
- If there is something worth adding: output the full text of the supplement after the changes. Keep entries in the existing supplement that are still correct exactly as they are; change or delete ones this run proved wrong, then add the new ones. One item per line, each starting with "- ", one sentence per item stating one thing clearly, at most 12 items in total, no headings, no explanation.
- If there is nothing worth adding (this run showed no new interface facts, or the existing description already says it): output only NONE. This is the most common case.

Requirements:
- Write only facts, written as observations. For example, write "The 'Food Delivery' in the home-page grid and the 'Food Delivery' on the bottom tab are two entries; the former opens the food-delivery channel", not "should", "don't", or "first... then..." style instructions and rules.
- Write only what the action list actually showed; if unsure, do not write it, and do not invent pages, buttons, or outcomes. For a run that was not completed, only state clearly where the route is blocked; do not guess where the correct route is.
- Do not write this run's specific content: contacts, text to send, search terms, amounts, quantities, addresses, order numbers, dates; and do not write passwords, verification codes, or card numbers.
- Do not mention tool names, and do not write the query string in a URL.
- Write new items in the language of the "goal" (if the goal is in Spanish, write in Spanish; if in English, write in English); do not write in English just because these instructions are in English. Keep words that appear on the page or button exactly as they are.
