## ADDED Requirements

### Requirement: Keys that fail authentication are permanently marked exhausted

A pool key that gets HTTP 401/403 (invalid key, banned) is marked as exhausted (Exhausted); it no longer revives automatically after the rate-limit cooldown expires and repeatedly rejoins rotation the way a rate-limited key does.

#### Scenario: A dead key no longer revives and poisons rotation
- **WHEN** the pool holds keyA (dead, requests return 401) and keyB (valid), and the task keeps running longer than the rate-limit cooldown (60 seconds)
- **THEN** keyA is marked exhausted right after its first 401, and from then on rotation only picks keyB; the "keyA cooldown expires and revives → selected → 401 → cooled down again" loop no longer occurs

#### Scenario: The 401 callback carries the status code so the wiring point can route
- **WHEN** a model request returns any of 401/403/429
- **THEN** the model callback hands the HTTP status code, together with the key and Retry-After, to the key-pool wiring point, which picks "rate-limit cooldown (429)" or "mark as exhausted (401/403)" according to the status

### Requirement: Rate-limit cooldown behaviour is unchanged

A key that gets HTTP 429 is still temporarily avoided according to Retry-After (or the default cooldown), and rejoins rotation automatically when the cooldown expires.

#### Scenario: 429 still means cooldown only
- **WHEN** a key in the pool gets 429 (with or without Retry-After)
- **THEN** that key is marked as rate-limited with a cooldown duration, and after the cooldown expires it is automatically unfrozen and rejoins rotation; a key whose cooldown has not expired is not selected

#### Scenario: A single-key pool still fails honestly on 401
- **WHEN** the only key in the pool gets 401
- **THEN** that key is marked exhausted, and this request throws the authentication error along the original path (it does not swallow the error or loop forever because there is no key to switch to)
