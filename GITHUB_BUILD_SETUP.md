# 🚀 GitHub দিয়ে MAYA Build করার পদ্ধতি

এই গাইড অনুসরণ করলে আপনার লোকাল কম্পিউটারে Android Studio/SDK কিছু সেটআপ করার
দরকার নেই — শুধু GitHub-এ push করলেই GitHub Actions নিজে থেকে APK বানিয়ে
দিবে।

## ধাপ ১ — Repo তৈরি/push করা

যদি এখনো GitHub repo না বানানো থাকে:

```bash
cd myra-original-master
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/<আপনার-ইউজারনেম>/<repo-নাম>.git
git push -u origin main
```

আগে থেকেই repo থাকলে, শুধু এই ফোল্ডারের কন্টেন্ট দিয়ে existing repo replace
করে normal `git add` / `commit` / `push` করুন।

## ধাপ ২ — Gemini API Key

এটা app-এর ভেতরেই (Settings স্ক্রিনে) দিতে হয়, GitHub-এ লাগে না — কারণ এটা
runtime-এ ইউজারের ফোনে সেট হয়, build-time-এ লাগে না।

> **Wake-word ("Hey MAYA") নিয়ে কিছু সেটআপ করতে হবে না** — এটা Android-এর
> নিজস্ব বিল্ট-ইন SpeechRecognizer ব্যবহার করে, যা প্রতিটা Android ফোনে
> ফ্রি ভাবেই থাকে। কোনো API key, account, বা external service লাগে না।

## ধাপ ৩ — Build trigger করা

`main` (বা `master`) branch-এ push করলেই build automatically শুরু হয়ে
যাবে। ম্যানুয়ালি trigger করতে চাইলে:

- GitHub repo → **Actions** ট্যাব → **Build & Release APK** workflow →
  **Run workflow** বাটন

## ধাপ ৪ — APK ডাউনলোড করা

Build শেষ হলে (সাধারণত ৩-৬ মিনিট), দুই জায়গায় APK পাবেন:

1. **Actions ট্যাব** → সফল হওয়া run-এ ক্লিক করুন → নিচে **Artifacts**
   সেকশনে `myra-debug-apk` ডাউনলোড করুন
2. **Releases** (repo-র main পেজের ডানপাশে) → সবচেয়ে নতুন release থেকে
   সরাসরি `.apk` ফাইল ডাউনলোড করুন

## Build fail হলে

- **Actions ট্যাব** → fail হওয়া run-এ ক্লিক করুন → কোন স্টেপে fail হয়েছে
  দেখুন, এবং সেই স্টেপের লগ expand করে exact error message দেখুন
- লগ ডাউনলোড করে (run page-এর উপরে ডানপাশে gear icon → "Download log
  archive") শেয়ার করলে এখান থেকেও exact কারণ বের করে দেওয়া সম্ভব
