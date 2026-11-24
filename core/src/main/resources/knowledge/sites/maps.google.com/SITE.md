---
name: maps.google.com
display: Google Maps
description: Look up place details and driving, transit, walking and other routes
domains: ["maps.google.com"]
aliases: ["Google Maps", "谷歌地图"]
apps: ["google-maps"]
open: maps.google.com
mode: desktop
---
## What it is
Google's web maps, for looking up place information and routes, usually without signing in. The actual URLs are under `google.com/maps`, sharing the google.com domain with Google Flights and others.

## Pages and URLs
- Search: `https://www.google.com/maps/search/<编码后的查询>`.
- Directions: `https://www.google.com/maps/dir/<编码后的起点>/<编码后的终点>`.

## Features
- **Places**: name, address, rating and number of reviews, open status, full opening hours, phone. Opening hours and phone are only visible after expanding the details.
- **Directions**: after opening the directions panel, enter the origin and destination and switch the travel mode (driving, transit, walking, etc.). Each mode lists several options, and each option has its duration, distance, the roads traveled or transfers, and notes.

## Known issues
- A place name in the URL does not mean that place is selected on the page; many branches share the same name, so check the name, address and city on the page.
- Pressing Enter directly in the origin or destination input box may select the current location, a recently searched place, or another place with the same name; picking from the suggestion list is more reliable.
- After switching the travel mode or opening another option card, the page content refreshes as a whole, and data read earlier belongs to the previous state.
