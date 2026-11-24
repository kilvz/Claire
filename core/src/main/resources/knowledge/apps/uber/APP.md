---
name: uber
display: Uber
packages: ["com.ubercab"]
aliases: ["Uber", "优步", "uber"]
sites: []
open: Uber
description: Ride types, prices and trips
---
## What it is
Uber is a ride-hailing app, package name `com.ubercab`.

## UI
The bottom entries are Home, Services, Activity and Account. 「Where to?」 on Home fills in the trip; entries such as Later are used to book a trip for a specified time.

## Features
- **Fare estimates**: after filling in the pickup point and destination you get the list of ride types, with ride type name, fare estimate in the local currency, estimated arrival time, capacity and surge pricing. Prices usually require logging in to display.
- **Trips**: Activity has historical and current trips; for a trip already booked you can see the driver, license plate and arrival time.

## Known issues
- The default pickup point is the device GPS, but it can be changed to an address in another city; the interface has a 「Search another city」 entry.
- The address input box has a clear button X; after typing, choose the matching item from the suggestion list.
- For airport addresses it asks follow-up questions such as Which airline, and there is Skip to skip.
- The content of the input box may change while typing; after typing you need to read it again.
