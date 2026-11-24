---
name: taobao
display: Taobao
packages: ["com.taobao.taobao"]
aliases: ["淘宝", "Taobao", "手机淘宝"]
sites: ["taobao.com"]
open: 淘宝
description: Shopping app for product search, shops, cart, orders and logistics
---
## What it is
A shopping app under Alibaba, Android package name `com.taobao.taobao`. Products come from Taobao shops and Tmall shops, and the account has a cart, favorites, orders, addresses and coupons.

## UI
At the top of the home page is a search box, below it are category entries and a recommendation feed; the bottom tabs are usually 首页 (Home), 逛逛 (Browse), 消息 (Messages), 购物车 (Cart) and 我的淘宝 (My Taobao). On the search results page you can switch at the top between sorting by 综合 (Relevance), 销量 (Sales) and 价格 (Price), and also filter by price range, shipping location and so on.

## Features
- **Searching products**: enter keywords in the home page search box; the results page shows product images, titles, prices, sales, shop names and shipping locations.
- **Product details**: after tapping into a product there are spec selection, price, number of reviews, a shop entry, 「加入购物车」 (Add to cart) and 「立即购买」 (Buy now).
- **Cart**: the bottom 「购物车」 (Cart) is grouped by shop; you can check products, change quantities, see the total amount, and choose an address at checkout.
- **Orders**: 「我的订单」 (My orders) in 「我的淘宝」 (My Taobao) is grouped into 待付款 (Pending payment), 待发货 (Pending shipment), 待收货 (Pending receipt) and 待评价 (Pending review); the detail page has the order number, amount, tracking number and shipping address.
- **Logistics**: 「查看物流」 (View logistics) in the order details shows the carrier, waybill number and each tracking event.
- **Favorites**: products and shops can be favorited separately, with two entries in 「我的淘宝」.

## Known issues
Products with the same name have links from many different shops, with large price differences. Product cards in the home feed and search result cards look similar, but lead to different paths when tapped. Invalid products in the cart are automatically grouped together and are not included in the total.
