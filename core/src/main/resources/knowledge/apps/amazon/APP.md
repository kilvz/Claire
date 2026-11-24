---
name: amazon
display: Amazon
packages: ["com.amazon.mShop.android.shopping"]
aliases: ["Amazon", "亚马逊", "amazon"]
sites: ["amazon.com"]
open: Amazon
description: Global e-commerce shopping, product search, price comparison, cart and order tracking
---
## What it is
Amazon is a global cross-border e-commerce shopping app, package name `com.amazon.mShop.android.shopping`. For website knowledge see `amazon.com`. It supports searching products, placing orders and checking out, and tracking shipments on sites in different countries.

## UI
The bottom navigation has four tabs: 「首页」 (Home), 「我的账号 (You)」 (Your account), 「购物车 (Cart)」 (Cart) and 「菜单 (三条横线)」 (Menu, three horizontal lines).
- **Home**: a full-width search bar at the top, with a camera scan (recognizes barcodes or physical items) and a microphone icon; a delivery address pill 「配送至 (Deliver to)...」 sits permanently below the search bar; the body presents promotional flash deals, recommendations based on browsing history and category navigation.
- **Search results page**: filters at the top (Filters: brand, Prime membership exclusive, price range, rating, seller qualifications) and sorting (Featured, Price low to high, Avg. customer review); the product stream mixes in promotional cards marked 「Sponsored (赞助广告)」 (sponsored ads).
- **Product detail page**: a multi-image carousel at the top, the full product name, star rating and review count, price and discount in the middle, and below them a variant selection area (size, color, capacity), shipping and seller (Ships from / Sold by), a yellow 「加入购物车 (Add to Cart)」 button and an orange 「立即购买 (Buy Now)」 button.
- **Your account page**: 「你的订单 (Your Orders)」, 「再次购买 (Buy Again)」, 「你的账户 (Your Account)」 and 「你的心愿单 (Your Lists)」 are permanently at the top.

## Features
- **Searching and filtering out ads**:
  - Type the full product name or model into the top search bar. In the results list, prefer naturally ranked products and watch out for ad slots carrying the small 「Sponsored」 label.
- **Adding to the cart and comparing prices safely**:
  - After tapping a product, you must first confirm that the right color or size variant is selected and check the stock status (In Stock / 仅剩 X 件, only X left).
  - When doing a procurement price comparison, prefer the yellow 「加入购物车 (Add to Cart)」 button; **never tap the orange 「立即购买 (Buy Now)」 lightly**, because the latter may place a one-click order directly and charge the default credit card.
- **Order tracking**:
  - Go to the 「我的账号」 (Your account) tab at the bottom and tap 「你的订单 (Your Orders)」 to see recent order details and live delivery progress (Track Package).

## Known issues
- **Country and site switching affects the shelf**: Amazon sites around the world are independent. If the currency unit or product stock looks wrong, check the country/region setting (Country & Language) in the menu; different countries have different delivery addresses and shipping fees.
- **One-click purchase trap**: once 1-Click ordering is enabled, tapping 「Buy Now」 completes payment directly and creates an order, so be very careful when running automation tasks.
- **Membership offer pop-ups**: at checkout or while browsing, a Prime monthly subscription or free trial offer often pops up, and it must be declined by tapping 「不,谢谢 / No, thanks」.
