---
name: amazon.com
display: Amazon
domains: ["amazon.com", "amazon.sg", "amazon.co.uk", "amazon.de", "amazon.co.jp"]
aliases: ["Amazon", "亚马逊"]
apps: ["amazon"]
open: amazon.com
mode: desktop
description: Cross-border e-commerce platform: search products, see prices, variants and stock, and add to cart.
---
## What it is

A global e-commerce platform. Each country site (.com, .sg, .co.uk, .de, .co.jp) is an independent storefront, with different prices, stock, currencies and shipping.

## Pages and URLs

- Search: open `/s?k=<keyword>` on the target country site; the keyword needs URL encoding.
- Product details: a clean link keyed by ASIN. Cards on the search page may be rewritten by ads for redirection.
- When no region is specified, the site naturally lands on some country site based on the visitor's location.

## Features

- **Search**: publicly viewable. Each result has an ASIN, title, price and currency, rating and review count, and ad slots are marked.
- **Product details**: the buybox holds the main price, stock and seller conditions; offers from used and third-party sellers are listed separately. For products with variants (color, size, etc.), the initial price corresponds only to the variant selected by default, and after selecting another variant the price and stock change.
- **Add to cart**: the Add to Cart button on the detail page; after success the page shows an Added to Cart prompt.

## Known issues

- Region redirection can make the page lack a price, or show currencies such as SGD and GBP.
- After adding to cart, the badge number and button color lag and may not update immediately.
- When the page structure is redesigned or a human verification pops up, product information cannot be read.
