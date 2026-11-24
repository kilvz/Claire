# Model groups

A model group is an optional layer on top of "use → one or more concrete models". The members of a group are chosen by policy to answer; when one call fails, the next member in the group can be used instead, without failing the whole task.

Single-model configuration stays as it is. Groups are only used when you want backup models for the main model, the vision model or the light model.

## Configuration

- Settings › Models › Model groups: create, edit, delete.
- Each group has a name, a policy and a member list; members can be enabled/disabled and moved up or down.
- A group can be selected for any of the three uses, shown alongside concrete models on the use selection page.
- The main model group requires all enabled members to support tool calling; the vision group requires at least one member that can see images. The light group has no requirement; members that do not support tools are skipped when tools are needed.

Storage is still in `files/settings/providers.json`:

```json
{
  "groups": [
    {
      "id": "g-...",
      "name": "Main fallback",
      "strategy": "failover",
      "sticky": true,
      "members": [
        {"config": "c-...", "priority": 0, "weight": 1},
        {"config": "c-...", "priority": 1, "weight": 1}
      ]
    }
  ],
  "uses": {
    "Main": {"use": "group", "group": "g-..."}
  }
}
```

Group members reference configuration ids in `providers`. A configuration therefore gains a second lifecycle where it is "referenced by a group"; `ProviderStore.prune` counts group members as "in use".

## Policies

- `failover` (default): ascending by `priority`; the next is used only when the previous one fails.
- `weighted`: a new conversation picks a member by weight and then sticks to it; not round-robin per request.
- `least-latency`: ordered by recent success rate and latency, keeping exploration for members with few samples.
- `cost-first`: by the member's cost tier; there is no UI for filling in cost tiers yet, so it falls back to priority.

`sticky` is on by default: the same conversation stays pinned to one member, protecting the prefix cache. Switching only happens on failure.

## Failover

| Error | Retry same member | Switch member |
| --- | --- | --- |
| Rate limiting, 5xx, network, stream cut off, proxy unreachable | Tried only once when the group has multiple candidates | Yes |
| Auth, quota, model missing/unavailable, tools unsupported, login expired | No | Yes |
| Content moderation refusal | No | No (does not look for a more lenient provider) |
| Context too long | Handed to the compaction flow | No |
| Bad request itself, parse failure, cancel | No | No |

Switching members happens before a request starts. If the stream has already emitted text and a switch is needed, the preview on screen is cleared first; a half-finished answer does not enter history. Tools that have already run are not redone because the model changed.

## Per-member context and private data

`ModelCaller` already stores compaction state and usage anchors per model; groups just let it pick by member within a single call. Once a member is chosen, the compaction window, compaction trigger point and image limit all use that member's own capabilities. The main model group's `vision` capability comes from the frontmost member: if the main member cannot see images, screenshots are not taken, avoiding a situation where the first image enters history and permanently excludes the main member; requests that actually carry images are still sent only to the members that can see them.

Thinking content and tool-call private fields from different providers must not be sent to another provider. `ModelCaller` records which provider gave each assistant message, and when switching members it strips other providers' `Part.Thinking` and `Part.ToolCall.opaque`. One-off calls that do not go through `ModelCaller` (titling, classification, review, image viewing) go through `GroupPool.complete`, and these fields are stripped uniformly only when a failover happens.

## Health and cooldown

`ModelHealthRegistry` records consecutive failures, cooldown until when, and recent latency per "provider + model"; shared within the process and cleared on restart. Rate limiting follows `Retry-After` (30 seconds if absent), auth/quota cooldowns last 5 minutes, and other consecutive failures double up to 10 minutes. After a cooldown ends, one request is let through first; if it succeeds, the member recovers.

Members in cooldown do not take part in selection. When no member is available, `ModelError.GroupUnavailable` is thrown, and the task ends as a model failure with the reason stated.

## Observability

- The usage ledger is unchanged: each member call is recorded separately under "provider × model × use".
- In the run record, `ModelReply` gains `group`; on a member switch a `RunEvent.State("model_route", "<group> <member A> -> <member B> <reason>")` is written. Message contents are not recorded.
- The group edit page shows member enable/disable and policy; the run detail shows which model actually answered.

## Out of scope

- No answer merging or voting.
- No cross-provider tool schema conversion.
- No per-request round-robin (it would break the prefix cache); weighting is assigned per conversation.
- No parallel racing; the `ModelGroup.raceReadOnly` field is left in place, to be implemented in v3.

## Tests

- `core`: `ModelGroupTest`, `ModelCallerGroupTest`, `FailoverPolicyTest`, `StripForeignPrivateTest`.
- `app`: `ProviderStoreGroupTest` (storage round trip, prune, leaving a group, provider deletion, per-member context).
- The existing `ModelRouterTest`, `AgentLoopLightTest` and `PrefixStabilityTest` still pass; single-model behavior is unchanged.

## Related files

- `core/src/main/kotlin/com/wangzan101/mika/core/model/ModelGroup.kt`: groups, members, policies, `ModelNeed`, `MemberKey`.
- `core/src/main/kotlin/com/wangzan101/mika/core/agent/ModelPool.kt`: `ModelPool`, `SingleMemberPool`, `GroupPool`, health/stickiness, `FailoverPolicy`.
- `core/src/main/kotlin/com/wangzan101/mika/core/agent/ModelCaller.kt`: routing by member, trying members one by one, preparing history when switching members.
- `app/src/main/java/com/wangzan101/mika/settings/ProviderStore.kt`: group storage, `RoleChoice.Group`, `modelFor` building pools, prune.
- `app/src/main/java/com/wangzan101/mika/settings/ui/models/ModelGroupScreen.kt`: group list and editing.

## Follow-ups (v2/v3)

This PR only does v1 (failover, stickiness, health cooldown, runtime foundations for weighted/latency). The following are **not implemented** and will go in separate PRs as v2/v3:

**v2**

- Member weight input on the group edit page, turning "weighted" from the current per-conversation rotation into a real distribution by weight.
- Entry points for editing member-level thinking tier / cost tier (the fields exist, there is just no UI).
- Group-level limit enforcement: `GroupLimits`' RPM, concurrency and daily tokens/cost are currently only stored, not enforced.
- Group dimension in the usage ledger: currently it only records "provider × model × use", so the same model cannot be distinguished inside and outside a group.
- Finer latency policies: time to first token, recent window, configurable exploration rate, half-open probing.
- Optional: persist health/cooldown state so known-bad members are still avoided after a restart.

**v3**

- User-entered cost tiers/unit prices, exposing cost-first; no scraping of provider price lists.
- Parallel racing / best-of: only tool-free, read-only requests, explicitly enabled per group and off by default; must warn that the same content will be sent to multiple providers.
- Aggregation/voting: has no well-defined meaning when tool calls are involved, so not recommended; if done, limited to tool-free plain text and off by default.
- Group import/export/templates: export only group structure and member names, no keys; show members and a privacy notice before importing.
- Group-level cost budget: depends on cost data.

**Explicitly not doing**: switching models mid-stream, automatic cross-provider tool schema conversion, treating content moderation refusal as a failover reason, or doing chat-page model switching / thinking tier / Skill / MCP mounting in the model group PR (these are covered by the follow-up PR1/PR2 designs).

On resuming, finish real-device acceptance, upstream review and CI first; v2 is done after #288 is merged and stable, and v3 gets its own design.
