# Brand logos for MCP services

Date: 2026-11-22

## Intent

The MCP service catalog / list / management pages currently use generic category icons (10 major categories) and a uniform Link icon, so you cannot tell which service is which. Change to each service's real brand outline + official brand color, recognizable at a glance.

## Goals / non-goals

- Goal: branded remote services show the brand outline + official brand color; unbranded stdio packages and custom servers keep using the existing icon fallback.
- Non-goal: do not chase official press kits; do not fetch images over the network at runtime; do not touch connection logic or secrets; do not add new user-visible copy (no 12-language translation burden).

## Constraints

- CLAUDE.md: brand marks belong to their respective brands, with attribution in `licenses/THIRD_PARTY_NOTICES.md`; the icon set's source is not written into code / docs / commit messages (only into notices).
- Data stays local: logos are bundled into assets and not fetched at runtime (works offline, does not leak browsing history).
- The existing UI icon slot is `ImageVector`; the project has no image loading library, so do not introduce one.

## Approach (selected: A + official brand color)

- Source: a CC0 brand icon set, version pinned. `tools/mcp/build_logos.py` fetches once and generates `app/src/main/assets/mcp/mcp-logos.json` (`id → {d, color}`), separate from `catalog.json` (the catalog only handles connection; logos are a presentation layer).
- Rendering: `McpLogos` uses Compose `PathParser` to turn the path into an `ImageVector` and caches it; the icon slot in list rows is still `ImageVector`, and the change is confined to the call sites.
- Color / dark mode: official brand colors are used as-is. Dark brands (GitHub black, Slack purple, Notion black, etc.) would be invisible in dark mode, so the logo sits on a **fixed light tile** (light in both modes) with the brand color as the foreground — the common approach on the "connected accounts" pages of various apps.
- Fallback rules: custom servers (no `presetId`) → Link; unbranded stdio packages → category icon.

## Left to the implementer

- A curated mapping table of 212 `presetId → brand slug` entries (most map directly, a few need manual configuration, about 22 stdio packages have no slug).
- PathParser → ImageVector build details, caching, and the token for the light tile.
- Tests: `mcp-logos.json` parsing, slug coverage, fallback paths.
