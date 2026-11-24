---
name: zhipin.com
display: BOSS Zhipin
domains: ["zhipin.com"]
aliases: ["BOSS直聘", "Boss直聘", "boss直聘", "BOSS直聘网页版", "直聘", "zhipin"]
apps: ["boss-zhipin"]
open: www.zhipin.com
mode: desktop
pace: careful
description: Recruitment and job-search site; search jobs by city, keyword and filter conditions, and view job details and salaries
---
## What it is
The web version of BOSS直聘, where job seekers search for jobs and view job details. There is also a mobile app of the same name (see `boss-zhipin`).

## Pages and URLs
- Home page: opening `https://www.zhipin.com/` jumps to the home page of some city, such as `/shenzhen/?seoRefer=index`; 「<城市>[切换]」 (<city> [Switch]) at the top changes the city.
- Search: `https://www.zhipin.com/web/geek/jobs?query=<编码关键词>&city=<城市代码>`, which can be opened directly (writing `/web/geek/job?…` jumps to `/web/geek/jobs`). The page title looks like 「「上海招聘」-…」 (["Shanghai recruitment"]-…), which you can use to check the city.
- City codes are 9 digits, such as 全国 (Nationwide) 100010000, 北京 (Beijing) 101010100, 上海 (Shanghai) 101020100; see `references/cities.md` for all cities.
- Filters: after you select a filter option on the page, the URL gains a parameter, such as work experience 3-5年 (3-5 years) `experience=105`, education 本科 (Bachelor's degree) `degree=203`, salary 20-50K `salary=406`, company size 100-499人 (100-499 people) `scale=303`; see `references/filters.md` for the code of each option.
- Recommended jobs: after signing in, opening `/web/geek/jobs` without a search term gives the list recommended according to job expectations; `/web/geek/job-recommend` and 「查看更多」 (View more) under 「精选职位」 (Featured jobs) on the home page also jump here. Above the list is a row of tabs: 「推荐」 (Recommended), one for each job expectation (job title plus city), and a 「+」 at the end for adding a job expectation; below the tabs is the filter row (city, job type, salary and benefits, work experience, education requirement, company industry, company size, 「清空」 (Clear)).
- Job details: `/job_detail/<职位编号>.html`, where the number is a string of twenty-odd letters and digits. The link of each job in the list is this address.

## Features
- **Job list**: you must sign in to see it in full. Each job has a title, salary, location, experience, education, company name and the company's funding stage, size and industry, as well as tags, benefits and the recruiter.
- **List data**: the list comes from the page's own `POST /wapi/zpgeek/search/joblist.json`, 15 items at a time; right after opening it sends two requests in a row (items 1–15 and 16–30). The returned `zpData` contains `resCount` (total number of results), `hasMore`, `lid` and `jobList`; see `references/joblist-fields.md` for each job's fields, and the plain-text salary is in `salaryDesc` (such as 「40-70K·15薪」 (40-70K, 15 months' pay)). These responses can be read in full with web_capture.
- **Job details**: clicking a job card in the list shows the details on the right side of the same page, and the URL does not change; opening the detail page address shows a full page. The detail page has the job title, hiring status, salary, location, experience, education, company (funding stage, size, industry), recruiter (name, title, whether online), benefits, job tags, job description, company introduction, business registration information, work address and update time; the salary in the body of the detail page is ordinary text.
- **「立即沟通」 (Chat now)**: start a conversation with this job's recruiter. This button is not on the job card (the only clickable things in the card are two links, the job title and the company name; clicking elsewhere on the card shows the details on the right); in the details on the right of the search page and the recommendation page, the button is on the row below the job title (together with 「收藏」 (Favorite), 「举报」 (Report) and 「微信扫码分享」 (Share via WeChat QR code)); on the job detail page it is at the top of the page, next to 「感兴趣」 (Interested).
- **Sign-in**: `https://www.zhipin.com/web/user/`; you can sign in with phone number plus SMS verification code, WeChat, or app QR code.

## API
- Filter options: `https://www.zhipin.com/wapi/zpgeek/search/job/condition.json`; opened as a web page it is JSON, with `experienceList`, `degreeList`, `salaryList`, `scaleList`, `stageList`, `jobTypeList` and so on, each item being `{code, name}`.
- Cities: `https://www.zhipin.com/wapi/zpgeek/search/job/hot/city.json` (hot cities) and `https://www.zhipin.com/wapi/zpCommon/data/cityGroup.json` (all cities, grouped by the first letter of their pinyin), likewise JSON.

## Known issues
- The salary figures in the list and in the right-hand details are drawn with the site's own font, so the text read back is missing its digits and only characters such as 「-K·薪」 or 「元」 remain; for the same job, `salaryDesc` in `joblist.json` is plain text.
- When not signed in: the top says 「登录/注册」 (Sign in/Register), the list releases only the first few items (4 have been seen), and below it says 「登录账号，查看更多好职位」 (Sign in to your account to see more good jobs).
- The first time the search page is opened, the URL may first carry `_security_check=1_<时间戳>`, which is removed after the page loads.
- Jobs posted by headhunters are marked 「猎头」 (Headhunter), and the company name is shown as an anonymized phrase such as 「某知名制造上市公司」 (a well-known listed manufacturing company).
- The recommendation list may include jobs not very relevant to the job expectation (such as sales or business-investment roles).
- The right-hand details contain prompts such as 「去App 与BOSS随时沟通」 (Go to the App to chat with BOSS anytime) and 「前往App」 (Go to the App).
- Salary filtering counts ranges that overlap: when 20-50K is selected, the list also includes jobs at 11-22K.
- When the number of people you can chat with that day is used up and 「立即沟通」 is clicked again: the page pops up a box, titled 「您已达到沟通上限」 (You have reached the chat limit), with 「您今天已与150位BOSS沟通，休息一下，明天再来吧～」 (You have already chatted with 150 BOSSes today; take a break and come back tomorrow~) below it, and only one 「确定」 (OK) button; the URL does not change, and the dialog covers the original page.
- Accessing too frequently or an abnormal environment triggers risk control: the API returns a prompt such as 「您的环境存在异常」 (Your environment is abnormal) (risk-control codes seen include 31 and 37), and the page may also pop up a captcha.
