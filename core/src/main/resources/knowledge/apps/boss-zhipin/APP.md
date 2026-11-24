---
name: boss-zhipin
display: BOSS Zhipin
packages: ["com.hpbr.bosszhipin"]
aliases: ["BOSS直聘", "Boss直聘", "boss直聘", "BOSS", "直聘", "bosszhipin"]
sites: ["zhipin.com"]
open: BOSS直聘
pace: careful
description: Job hunting app for searching jobs, viewing details, talking to recruiters directly and sending résumés
---
## What it is
BOSS直聘 (BOSS Zhipin) is a job hunting app, package name `com.hpbr.bosszhipin`. Job seekers search jobs and view job details here, greet and chat with recruiters directly, and let recruiters see their online résumé. The web version is at `zhipin.com`.

## UI
The main structure usually has bottom tabs such as 「职位」 (Jobs), 「消息」 (Messages) and 「我的」 (Me); the exact layout differs slightly by version.
- Jobs page: a search box at the top, below it a list of job cards (one card per job: title, salary, location, experience, education, company). You can tap the filters, or tap a job card to open its details.
- Job details: job title, salary, location, experience, education, company (funding stage, size, industry), recruiter (name, title, whether online), benefits, job tags, job description, company introduction and work address.
- Messages page: the list of conversations with recruiters; after 「立即沟通」 (Chat now), the conversation with that recruiter is here.
- Me: online résumé, job expectations, and 「谁看过我」 (Who viewed me), 「收藏的职位」 (Saved jobs) and so on.

## Features
- **Searching jobs and filtering**: keyword + city, then filter by salary, work experience, education, company size, industry and so on, the same set of conditions as the web version.
- **Job details**: see the salary, requirements, company, recruiter and job description.
- **Chat now**: start a greeting to the recruiter of a job and enter the chat with them.
- **Chat**: send text messages back and forth with the recruiter; the recruiter can see your online résumé. Whether images or file attachments can be sent depends on the version.
- **Online résumé**: maintained under 「我的」 (Me); after 「立即沟通」 (Chat now) or 「感兴趣」 (Interested) the recruiter can see this résumé without a separate file upload.
- **Sign-in**: phone number + SMS verification code, WeChat, or one-tap sign-in.

## Known issues
- When opened while signed out it shows the sign-in page directly: 「微信登录」 (Sign in with WeChat), 「一键登录」 (One-tap sign-in), 「其他方式登录」 (Sign in another way), with a checkbox below for accepting the user agreement and privacy policy. The back arrow at the top left of the sign-in page leads to the phone-number sign-in page (phone number, 「下一步」 (Next), 「接收不到短信」 (Not receiving the SMS), plus other sign-in methods such as WeChat and QQ), and pressing back again exits the app. There is no entry for browsing without signing in.
- Too-frequent access triggers risk control: a captcha pops up, or a notice says 「访问频繁」 (Too frequent) or 「您的环境存在异常」 (Your environment is abnormal), and you have to wait a while or switch networks and try again.
- There is a cap on how many people you can talk to in a day (150 has been seen); once it is used up, tapping 「立即沟通」 pops up 「您已达到沟通上限」 (You have reached the chat limit).

## Common flows
- **Finding a job**: search jobs → filter → view details → 「立即沟通」 (Chat now) to greet → chat and let the recruiter see the résumé. See `references/job-application.md` for the exact steps.
