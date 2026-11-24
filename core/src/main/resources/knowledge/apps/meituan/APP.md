---
name: meituan
display: Meituan
packages: ["com.sankuai.meituan"]
aliases: ["美团", "Meituan"]
sites: ["meituan.com"]
open: 美团
description: Food delivery, in-store group deals, hotels and travel, and other local-life services
---
## What it is
The local-life services app under Meituan, Android package name `com.sankuai.meituan`. The home page splits into channels such as food delivery, in-store, hotels and travel, movies and medicine, and the account holds orders, addresses, coupons and membership information.

## UI
The top of the home page is the city and search box, the middle has service entries such as food delivery, food, hotels and movies, and below is a recommended feed; the bottom tabs are usually Home, Orders and Me. The food delivery channel shows a merchant list, and tapping a merchant opens the menu page and the cart.

## Features
- **Food delivery**: the food delivery channel ranks merchants by distance, rating and sales volume; the merchant page has the menu, minimum order price, delivery fee, estimated delivery time and spend-and-save offers.
- **Orders**: in 「订单」 (Orders) at the bottom, food delivery orders and in-store group deal orders are listed separately; a food delivery order's details have the order number, dishes, actual payment, delivery address and rider information.
- **Group deals and in-store**: package deals and vouchers on merchant pages generate a voucher code after purchase, grouped into unused and used under 「订单」 (Orders).
- **Hotels and travel**: the channel searches hotels by city, date and location, and the list shows the price, rating and room types.
- **Search**: the home page search box can search merchant names, dish names and categories.

## Known issues
「外卖」 (food delivery) in the home page grid and 「外卖」 on the bottom or channel page may be different paths. On a food delivery merchant page, the delivery fee and estimated delivery time change with the address. The same merchant has two sets of orders, group deals and food delivery, which never appear in the same list.
