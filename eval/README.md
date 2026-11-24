# Mika evaluation task set

Tasks for on-device evaluation. One JSON file per task, placed at `tasks/<category>/…/<id>.json`, and the file name is the `id`.
The evaluator (`app/src/androidTest/.../eval/EvalRunTest`) reads tasks from the test package's assets, runs them one at a time through the real path of `TaskService`, scores them and writes a summary.

## Directory

| Directory | Category | Expected outcome | Minimum task count |
|---|---|---|---|
| `read/system` | Settings and system apps: look up information | `succeeded` | 10 |
| `read/content` | Content apps: look up information | `succeeded` | 15 |
| `read/shopping` | E-commerce / local services: look up information (look up only, do not buy) | `succeeded` | 10 |
| `write` | On-device write operations (notes, alarms, calendar, contact drafts) | `succeeded` | 10 |
| `irreversible` | Sending messages, placing orders, deleting (do not run now; see "How to run") | `confirmation` | 8 |
| `impossible` | Cannot be done in the first place | `failed` | 5 |
| `barrier` | Expected to hit login / verification | `takeover` | 2 |
| `web` | Web tasks (IDs start with `web-`, categories vary; includes web + app comparison, and computing in the sandbox after downloading) | Depends on category | 10 |
| `skills`, `memory` | Specialized task sets (paired tasks, memory tasks); must be selected explicitly with `evalSet` | — | — |

`TaskSetTest` in `core` checks that every task parses and is valid, that IDs are unique and equal to the file name, the task count in each directory, that every task is partitioned and stratified by directory, that write tasks carry a marker, that irreversible tasks check the confirmation card, that tasks needing a question carry an automatic answer, and that tasks contain no local paths.

## Tune pool and holdout pool

Every task has a `partition`: `tune` (tune pool, about 70%) or `holdout` (holdout pool, about 30%). Allocation is stratified by directory, so every directory has holdout tasks; a group of paired tasks and a memory session stay in the same pool.

- When tuning prompts, knowledge and skills, look only at results from the tune pool (`-e evalSplit tune`).
- Look at holdout-pool results (`-e evalSplit holdout`) only when making a release decision; do not use them to tune prompts and knowledge, and do not change code to fit holdout tasks. Once a holdout task has been used for tuning, move it into the tune pool and add a new task to the holdout pool.
- Add new tasks to both pools by directory, keeping about 30% holdout in each directory.
- `partition` and the `split` of paired tasks (train / test, two rounds) are two different things.

## How to write tasks

```json
{
  "id": "calculator-multiply",
  "goal": "Use the phone's built-in calculator to work out 123×456 and tell me the result",
  "apps": ["{app:calculator}"],
  "category": "read",
  "partition": "tune",
  "requires": ["app:calculator"],
  "expect": "succeeded",
  "checks": [{ "type": "answer_regex", "pattern": "56,?088" }, { "type": "answer_grounded", "minItems": 1 }],
  "maxSteps": 20,
  "timeoutSec": 420
}
```

- Phrase `goal` the way a real person would. For read-only tasks, state "only look, do not …" and call out the actions this task might accidentally trigger (liking, adding to cart, changing settings).
- Third-party apps: for every package in `apps`, write `installed:<package>` in `requires`; if it is not installed, skip the task and do not count it as a failure.
- For "built-in" apps (clock, calendar, calculator, contacts, file manager, weather, notes), do not write a package name; write `{app:<capability>}` (see `APP_CAPABILITIES` in `core` for the capability table), and write the same for `screen_has`'s `packageName`.
  When running, use the standard Android intents (`SET_ALARM`, `CATEGORY_APP_CALENDAR`, `CATEGORY_APP_CALCULATOR`, …) to find an app on this phone that can handle it, and after the run prefer the one the Agent actually opened;
  for those without a standard intent (notes), take the app the Agent opened. For apps the phone is certain to have (clock, calendar, calculator, contacts), write `app:<capability>` in `requires`; skip if it cannot be found.
  It is better if the check does not depend on the package name (use the `storage_root` probe to list the root of internal storage to see whether a folder exists). The same task can run on any Android phone.
- For tasks with a fixed answer, use `answer_contains` / `answer_regex`; for expected values that differ by device (version numbers, dates), use `probesBefore` to run whitelisted probes first, then write `{probe:<placeholder name>}` in the check items.
- For information-lookup tasks whose answer can change, always use `answer_grounded`: the values, quoted names and "label: value" pairs in the answer must all have appeared in the last few screen reads. Write the number of items the task requires in `minItems`.
- Write tasks must have a `marker` (containing `{run}`), include it in the goal, and use `screen_has` to find it again in the corresponding app (or use a probe).
- For irreversible tasks, write `confirmation` in `expect` and use `confirmation_shows` to check the card's "what / to whom / content / how much" (in the development stage the confirmation gate does not take effect and no card appears, so these tasks cannot currently produce this outcome). Only use yourself as the target (File Transfer 文件传输助手, My PC 我的电脑, Only me 仅自己可见) or marked data.
- Tasks like sending messages, publishing and placing orders touch real accounts:
  - Add `signedIn:<package>` to `requires`; the task runs only after the user is present and has written the package name into `eval/logged-in.txt`; state in `note` what the user must confirm before running.
  - Order tasks stop at the 「提交订单」 (Submit order) step, and the card checks the store name and the amount.
- Delete tasks only delete the marked data that the task itself created first (create it in the goal, then delete it) and do not delete existing data.
- For tasks that only make sense with the user's account (viewing the nickname on 「我的」 (Me), notifications), write `signedIn:<包名或网站>` in `requires`: for websites, check whether there has been a login under the 「网站登录」 (Website login) setting; for apps, check the manual marker (`eval/logged-in.txt`);
  if not logged in, skip it and write "not logged in" as the reason. The evaluator does not probe app login state itself (there is no probe reliable for all apps). The old forms `login:` and `site:` are still recognized.
- For read tasks that hit a login wall and are handed to the user, list them separately as "handed to user: login"; this does not count as done (whether it does is up to the human). Only recognize an obstacle that is reported immediately before the takeover
  and on whose screen a login box / verification-code text is visible; when the task declares that it is logged in (`signedIn:` etc.), it does not fall into this category. To change this, use `acceptTakeoverOn` to write which obstacles are recognized (`[]` means none is recognized).
- Do not create tasks that spend money, send content externally, change account settings or delete the user's existing data; do not touch private pages of apps that hold personal data.
- For tasks where "should it ask or decide itself" matters, use `asked_user` (whether the Agent asked a follow-up with `ask_user`): when what is missing is something only the user knows (which person, which entry to delete, what time for the alarm), write `{"type":"asked_user"}` and write the automatic answer in `answers` (the key is a word that appears in the question); when only a preference is unstated (brand, city), write `{"type":"asked_user","expected":false}`, expecting the Agent to decide sensibly and explain it in the answer.
- For web tasks, when `channel` forces the web, 「用网页」 (use the web) is prefixed to the goal; if the goal already says so, it is not added again. Web tasks use the desktop version of the website by default; for tasks that download files and then compute, use only public files with fixed content, and state in `note` how the answer was derived.
- `note` is for reviewers (why the task was written this way); the evaluator does not use it.

## How to run

First configure the main model with `LiveRunTest#configureProvider`, then:

```sh
adb -s <device> shell am instrument -w \
  -e class com.wangzan101.mika.eval.EvalRunTest \
  -e evalRun <run name> [-e evalSet <directory or id prefix, e.g. read/content>] [-e evalSplit tune|holdout] \
  [-e evalPriceIn <price per million input tokens> -e evalPriceOut <output price> -e evalPriceCached <cached price>] \
  com.wangzan101.mika.test/androidx.test.runner.AndroidJUnitRunner
```

- Without `evalSet`, run the full set (except smoke tasks and specialized task sets). `-e evalSplit tune|holdout` runs only one pool; without it, both pools run (see "Tune pool and holdout pool" above). The full set takes several hours; run it in batches by directory.
- In the development stage Mika executes autonomously (`autonomous = true`, `confirmations = null` in `TaskRun`), the confirmation gate does not take effect, and the evaluator's automatic rejection (`EvalHooks.decisionFor`) does not trigger either, so there is no safety net. `write` tasks run as usual; `irreversible` tasks do not run by default — **do not run them now**: adding `-e evalAllowIrreversible true` will really send messages, delete data and place orders. Tasks that were not run are recorded as skipped in the summary with the reason stated.
- Other parameters: `-e evalChannel web|app` forces every task through the web or the app, to compare the two routes; `-e evalOnTakeover cancel|wait` decides whether to cancel or wait when a takeover occurs (cancel by default); `-e evalFlags none|<key=on,…>` temporarily changes the speed-up switches and restores them after the run; only with `-e learnSkills on` and `-e memory on` does evaluation learn skills and remember automatically (both off by default, so scores are not affected by the previous round).
- Results are in the App's external files directory `eval/<run name>/`: `summary.md` (contains a 「运行小结」 (Run summary) section: first feedback, number of back-and-forths, and where time went for the model / screen reading / actions / other, one line per task), `scores.jsonl` (contains the full answer text, with `metrics` per task), `metrics.json` (the per-task run summary `RunMetrics`), and `<id>/run/` (the complete run record). Look at them somewhere outside the repository; results are not committed to the repository, and when summarizing them, do not include full answer texts, screenshots, device serial numbers or local paths.
- Data created by write tasks is marked; it is listed at the end of the summary and cleaned up manually.

## How to overrule manually

The evaluator only gives a preliminary verdict. After a human looks at the run record under `<id>/run/`, the verdict may be changed; write the change in the 「人工改判」 (Manual overrides) section at the end of the summary, with each entry giving: task id, original verdict → new verdict, and evidence (quoted screen-read text or the original text of the final answer).

- Do not change a verdict without evidence in the run record.
- Do not change "not passed" to "passed" unless the evidence shows the answer is in fact correct and the check rule misjudged it (for example, the value in the answer is right there in the screen read, just not recognized in that written form).
- Change the failure category (`perception`, `route`, `barrier`, `app_state`, …) according to what actually happened in the run record; do not patch for a single task.
