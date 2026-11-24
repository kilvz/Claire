---
name: pinduoduo
display: Pinduoduo
packages: ["com.xunmeng.pinduoduo"]
aliases: ["拼多多", "PDD", "Pinduoduo"]
open: 拼多多
description: Shopping app for product search, group buying, orders and logistics
---
## What it is
The shopping app under Pinduoduo, Android package name `com.xunmeng.pinduoduo`. Products are shown with two prices, the group-buy price and the buy-alone price, and the account holds orders, addresses, favorites and coupons.

## UI
The top of the home page is a search box, below it are channel entries and a product feed; the bottom tabs are usually Home, Categories, Duoduo Video, Chat and Personal center. The search results page shows the product image, title, group-buy price, buy-alone price, number of units already grouped and store name.

## Features
- **Searching products**: type keywords into the home page search box; the results page has sorting and filters such as comprehensive, sales volume and price.
- **Product details**: after tapping a product there is specification selection, the group-buy price, the buy-alone price, the number of units already grouped, reviews, the store entry, and 「发起拼单」 (start a group order) and 「单独购买」 (buy alone).
- **Group buying**: group orders in progress are visible on the product details page; after joining you wait for the group to fill, and it is refunded if it does not.
- **Orders**: 「我的订单」 (My orders) in 「个人中心」 (Personal center) is grouped into pending payment, pending sharing, pending shipment, pending receipt and pending review; the detail page has the order number, actual payment, tracking number and delivery address.
- **Logistics**: 「查看物流」 (View logistics) in the order details shows the carrier and the tracking history entry by entry.
- **Chat**: 「聊天」 (Chat) at the bottom holds the conversation list with merchants.

## Known issues
On the same product page the 「拼单价」 (group-buy price) and 「单独购买价」 (buy-alone price) are two different numbers, and the price changes after switching specifications. Prices in the home feed, search results and product details may differ because of coupons. An order in the pending sharing state has been paid but the group has not filled yet.
