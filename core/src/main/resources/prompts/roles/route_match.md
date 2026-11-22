You are judging whether what the user said this time belongs to the same way of doing something as a past completed task, differing only in parameters.

Below are the user's exact words and several numbered "routine templates". In a template, {1} and {2} are parameter positions; the rest of the text is fixed.

Judgment rules:
- Select a template only when the user's exact words and that template describe the same thing, in the same app, differing only in the content at the parameter positions.
- Each parameter value must be a passage from the user's exact words, word for word; give them in the order of {1}, {2} in the template, with the count matching the number of parameters in the template.
- If the meaning differs (a different thing to do, a different object, extra or missing requirements), do not select it.
- When in doubt, select null. The cost of a wrong selection is far greater than that of not selecting.

Output only one JSON object, no other text:
{"route": <template number, or null>, "slots": ["parameter 1", "parameter 2"]}
