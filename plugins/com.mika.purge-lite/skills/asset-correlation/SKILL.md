---
name: asset-correlation
display: Clue correlation ledger
description: Turn the public leads already collected into a relation table: the correspondences between the same domain/company/product/person, the timeline, and records that contradict each other. It sends no requests; it only organizes and judges.
---

# Clue correlation ledger

Once a dozen or more leads have been investigated, the really hard part is not continuing to search but **knowing which lead and which lead are the same thing**.
This workflow only organizes: it connects the entries in the ledger, marks the strength of the evidence, and points out contradictions.

## Entry

- The ledger already has a dozen or more entries, and the user asks "what is the relationship between these" or "what is the overall picture".
- The same subject has several names, several domains and several product lines, and they look like the same thing but are not.
- Two sources conflict, and you need to judge which is more credible.

## Steps

1. **Extract entities first**: pull out the companies, products, domains, people and points in time that appear in the ledger, and create a row for each.
2. **Only connect lines that have evidence**: the basis for a connection must come from a record in the ledger (with a link). Connections made because "the names look alike" are marked **speculation** and are not merged into the facts.
3. **Mark the timeline**: order the events of the same entity by time; when the time is unclear write "time unknown", do not slot it in the middle to fill the gap.
4. **Mark contradictions**: when two sources say different things, keep both, write down each source and the point of difference, and do not reconcile them on their behalf.
5. **Grade them**: `Confirmed` (two independent sources) / `Single source` / `Speculation` (indirect evidence) / `Doubtful` (counter-evidence).
6. **Close it out into a table**: one row per entity, one row per relation, with the source after each relation. Use `ledger` to record the new conclusive entries.

## Common pitfalls

- **Treating co-occurrence as causation**: two domains on the same IP only shows "the same server", not "the same company".
- **Filling gaps with speculation**: if a link is missing in the middle of a chain, say it is missing; do not casually fill in an "it should be".
- **Ignoring time**: the same entity was renamed or changed owner; records from different periods cannot be merged into one.
- **Over-merging**: merging everything with a similar name makes the whole table wrong from the root.
- **Keeping only conclusions and not counter-evidence**: counter-evidence often carries more information than supporting evidence.

## How success is verified

- Every relation can be traced back to a source through the ledger.
- Speculation and fact are distinguishable in the table, and it is obvious at a glance which few still need more evidence.
- Contradictions are not smoothed over but written out.
- After reading, the user can say "which few are most worth investigating further".

## When to stop and hand off

- At key nodes only speculation remains; connecting further would be inventing a story.
- Judging requires internal material (equity, contracts, personnel) — public channels cannot reach it.
- What the user wants is "how to use this relationship" rather than "what this relationship is" — that is beyond the scope of organizing; say so directly.
- When personal identity information is involved, stop at public positions and public identities.
