---
name: douyin.com
display: Douyin
description: Products, price APIs and lucky bags in Douyin web live rooms; mall entry on the mobile app
domains: ["douyin.com", "jinritemai.com"]
aliases: ["抖音", "抖音商城", "抖音电商", "douyin", "douyinshop"]
apps: ["douyin"]
open: douyin.com
mode: desktop
---
## What it is
A short video platform that also has a mall and live-stream commerce. Short videos, the mall and live streaming are three different functions. On mobile the counterpart is the Douyin app; web live streams are at `live.douyin.com`, and e-commerce products and shops are at `jinritemai.com`.

## Pages and URLs
Web live rooms are under `live.douyin.com`.

## Features
- **Live room products**: product data is returned through APIs, and the explanation cards on the page are not always shown.
  - A `promotions/pop` response appears when the product changes; the complete product table is in the `promotions/page` response, which is refreshed by tapping 「全部商品」 (All products) on the live room page. Tapping a single product card leaves the live room.
  - Each product has two ids: `promotion_id` is the number of a particular listing in this session, and `product_id` is the product itself. The same product may be listed and delisted repeatedly.
  - The unit of the price field `min_price` is cents; a value of -1, 0 or a missing field all mean no price was given, not 0 yuan. The price of the same product may differ between the pop and page responses.
  - The product link is subject to the redirect address given in the response.
- **Live room lucky bags**: requires login.
  - The lucky bag widget is in the top left of the player and only shows a countdown, without the word 「福袋」 (Lucky bag); the countdown is constantly repainting, the widget position changes, and it needs to be located by page structure.
  - The lucky bag detail API is requested only once when entering the live room and is not sent again when the page is refreshed; the appearance and drawing of lucky bags are pushed by the live room.
  - Whether the participation conditions have been met depends on the user's own state in the response; the `status` field that comes with a condition item does not indicate completion. The password for a password condition is in the response, and the original text prevails. Some conditions cost diamonds.
  - The draw result is shown on the page only at the moment of the draw and cannot be looked up afterwards.

## Known issues
- An empty API response may be risk control or may mean it has not been captured yet; it does not mean the live room has no products.
- The product serial number on the screen cannot be used alone to identify a product; when the screen is blurry or occluded, the serial number is easily mismatched.
