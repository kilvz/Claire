---
name: passive-recon
display: Public information search
description: Investigate using public data sources only: official sites, public announcements, certificate transparency logs, encyclopedias, news, public databases. Never send a probe request to the target host; every lead must leave a clickable source link.
---

# Public information search

This is a **look-only** investigation workflow: no scanning, no brute force, no login attempts, no crafted attack requests.
It does one thing only — turn information scattered across public places into a ledger that someone else can re-check.

## Entry

- The user asks you to look into a company, a product, a person or a domain, and the information may be scattered across several public sources.
- The user asks "is there a source for this claim" or "help me verify this".
- You have already searched three or four rounds in one investigation and are starting to forget which item came from where.

## Steps

1. **Define the questions first**: write "what needs to be found out" as three to five answerable questions. Say up front what cannot be found out; do not change the goal while investigating.
2. **Use public sources only**, in priority order: official websites and announcements → government/regulator public information → certificate transparency logs (crt.sh) → mainstream news → encyclopedias and public databases → community discussion (leads only, not conclusions).
3. **Enter every lead into the ledger on the spot**: record one entry with `ledger`, and it must have a `source` link. Anything without a link, no matter how plausible, must not be written in.
4. **Cross-validate**: every fact needs at least two sources that **did not copy from the same press release**; when there is only one source, mark it "single source".
5. **Close it out**: group the ledger by question and write "confirmed / single source / doubtful / cannot be found"; write out the ones that cannot be found too.
6. **Deliver**: ledger path + conclusions + the source corresponding to each conclusion.

## Common pitfalls

- **Taking a search snippet for the original text**: snippets drop qualifiers. Click through to the original and then record it.
- **Forgetting that this is "sends no packets"**: as soon as you start scanning ports, trying logins or running directory wordlists, you have already crossed the line. Nothing of that kind here.
- **The source is a retelling**: three websites reposting the same article are still one source.
- **Writing "did not find" as "does not exist"**: the former is an investigation result, the latter is a conclusion, and the two are far apart.
- **Writing the ledger as an essay**: one line per entry, fact + source, that is enough.

## How success is verified

- Every entry in the ledger has a clickable source; `ledger` does not accept entries without a link.
- Conclusions fall into the four grades "confirmed / single source / doubtful / cannot be found", with no inferences mixed into confirmations.
- The user can re-check everything themselves by following the ledger.
- No probe request was sent to the target host throughout.

## When to stop and hand off

- What is being looked into involves personal privacy (home address, phone number, ID card, family members) — only public identity and public positions, and stop here.
- What the user wants is "can we get in" or "is there a vulnerability" — that is a second-tier capability requiring written authorization; state directly that you cannot do it.
- The sources contradict each other and none can be falsified.
- Continuing requires a paid database or internal material.
