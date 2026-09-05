# 回复 Guideline 2.1 – Information Needed（新账号例行问询）

这一封不是功能被判违规，是新开发者账号首次提交的例行「补材料」。
把下面英文原样贴两处即可：

1. **App Store Connect → 该提交的「消息」里回复 Apple**（并把 `site/public/review-demo.mp4`
   作为附件一起传上去，别只给链接）。
2. **App 信息 → App 审核信息 → 备注**，覆盖原有内容（Apple 明确要求也写进备注，供以后每次提交复用）。

然后点「重新提交至 App 审核」。不需要新 build。

⚠️ 提交前确认一件事：Apple 要的是**真机**录屏。如果 `review-demo.mp4` 是模拟器录的，
必须用两台真机重录一遍（开头必须是从桌面点开 App 的那一下）。

---

## 英文回复（直接粘贴）

```
Thank you for the review. DroidTrans has no user accounts, no user-generated
content, and no server-side backend — files move directly between two devices
on the same local network. Answers to each item below.

--------------------------------------------------------------------
1. SCREEN RECORDING
--------------------------------------------------------------------
A screen recording made on physical iPhones running the latest iOS is
attached to this message, and is also available at:

    https://droidtrans.mkstore.life/review-demo.mp4

It starts with launching the app from the Home screen and shows the typical
user flow end to end:

    0:00–0:56  iPhone and Mac side by side: the Local Network permission
               prompt, pairing with the 6-digit code shown by the Mac app,
               browsing files on the computer, pulling down a 3.15 GB video
               with live progress, the received-files gallery, the transfer
               history, and the In-App Purchase page.
    0:56–1:30  iPhone to iPhone, no computer involved: one phone makes
               itself visible, the other finds it, the first approves the
               connection, and content is sent across.

Regarding the specific flows you listed:
  • Account registration / login / account deletion: NOT APPLICABLE. The app
    has no accounts of any kind. There is nothing to register, log into, or
    delete. Nothing is sent to us and nothing is stored by us.
  • User-generated content: NOT APPLICABLE. There is no feed, no sharing with
    strangers, no public or shared content. A transfer only happens between
    two devices the user physically controls, after the receiving side
    explicitly approves it, so there is no third-party content to report or
    block.
  • Paid features: shown in the recording (the In-App Purchase page and the
    free-vs-Pro comparison table).

--------------------------------------------------------------------
2. PURPOSE AND TARGET AUDIENCE
--------------------------------------------------------------------
DroidTrans moves files between an iPhone and a computer (macOS or Windows),
or between two phones, over the local Wi-Fi network.

Problem it solves: moving a large video or a folder of photos off an iPhone
onto a non-Apple computer, or onto an Android phone, is still awkward. The
usual workarounds are cloud uploads (slow, size-capped, and they put private
files on someone else's server) or cables plus desktop software. DroidTrans
does it over the local network at LAN speed, with no cloud, no account, and
no file leaving the two devices involved. Transfers resume after an
interruption rather than starting over.

Target audience: general consumers who own an iPhone together with a Windows
or Android device — a very common mixed-device household — plus anyone moving
large media files who does not want them passing through a cloud service.
Rated 4+; nothing in the app is age-sensitive.

--------------------------------------------------------------------
3. SETUP AND ACCESS TO MAIN FEATURES
--------------------------------------------------------------------
NO LOGIN CREDENTIALS ARE NEEDED — the app has no accounts. Every feature is
reachable immediately after launch. It is, however, a companion app, so a
second device is required to see a transfer complete.

To test with a Mac (recommended):
  1. On a Mac, download and open the free desktop app:
         https://droidtrans.mkstore.life/download.html
     It is free, requires no account and no payment, and the .dmg is signed
     and notarised by Apple.
  2. Put the Mac and the test iPhone on the same Wi-Fi network.
  3. Launch the iOS app and tap "A computer".
  4. ALLOW the Local Network permission prompt. This is essential — if it is
     denied, iOS blocks discovery silently and no computer will ever appear.
  5. The Mac window shows a 6-digit pairing code. Enter it on the phone (or
     scan the QR code shown next to it).
  6. The main screen is now active: send photos, files or text in either
     direction, browse and pull files from the computer, view history.

To test with two iPhones (no computer):
  On the receiving phone tap "Receive" to make it visible; on the sending
  phone tap "Send", pick the phone that appears, and approve the connection
  on the receiving phone.

If a second device cannot be set up, the recording in item 1 shows both
paths in full.

Sample files: none are needed — any photo or video already in the test
device's Photos library, or any file in the Files app, can be sent.

--------------------------------------------------------------------
4. EXTERNAL SERVICES, TOOLS AND PLATFORMS
--------------------------------------------------------------------
Core functionality uses NO external service. There is no backend, no data
provider, no authentication service, no analytics, no advertising, no AI
service, and no third-party SDK of any kind. The app is written in Swift and
SwiftUI against Apple frameworks only (Network.framework / Bonjour, PhotoKit,
StoreKit). File transfer is a direct device-to-device connection on the local
network; the data never reaches any server of ours or anyone else's.

The only two network endpoints outside the local network are:
  • Apple's StoreKit / App Store — the only payment path in the app.
  • https://droidtrans.mkstore.life — our own static website, for the support
    page, the privacy policy and the Mac download page; plus one optional
    endpoint that redeems an activation code purchased on our macOS or
    Android versions. That endpoint is only ever contacted if the user
    chooses to enter such a code; it is not part of, or required for, any
    transfer feature. Purchases made on iOS always go through In-App
    Purchase — the app contains no link to any external purchase page.

--------------------------------------------------------------------
5. REGIONAL DIFFERENCES
--------------------------------------------------------------------
None. The app behaves identically in every region and on every network. The
feature set, the pricing tier and the In-App Purchase are the same
everywhere. The only regional variation is language: the interface is
localised in English and Simplified Chinese and follows the device language,
with English as the default for all other languages. No content, feature or
purchase is gated by region.

--------------------------------------------------------------------
6. REGULATED INDUSTRY / THIRD-PARTY MATERIAL
--------------------------------------------------------------------
Not applicable. The app is not in a regulated industry (no finance, health,
gambling, medical or similar). It contains no third-party or licensed
content: all code, artwork, the icon and all copy are our own original work.
The app only ever handles the user's own files on their own devices.

--------------------------------------------------------------------
7. IN-APP PURCHASE OVERVIEW
--------------------------------------------------------------------
There is exactly ONE In-App Purchase:

    DroidTrans Pro · Lifetime — US$14.99 — Non-Consumable, one-time.

There is no subscription of any kind and no auto-renewal.

How to reach it: launch the app → tap the account icon in the top-right
corner → tap "DroidTrans Pro". The paywall opens with a table comparing the
free tier to Pro, followed by the purchase button and a "Restore Purchases"
button. No account or login is required to purchase. This is also shown in
the recording in item 1.

What the purchase unlocks: all transfer features are free and unlimited in
number of transfers. Pro lifts two quotas and adds two features:
  • per-file size limit raised from 4 GB to unlimited;
  • photo-library export raised from 1,000 items per run to unlimited;
  • incremental photo-library sync (only what is new since last time);
  • complete Live Photo export (still image plus its paired video; the free
    tier exports the still image only).

The paywall also links to the Terms of Use (Apple's standard EULA) and to our
privacy policy, as required.

--------------------------------------------------------------------
PERMISSIONS AND PRIVACY
--------------------------------------------------------------------
  • Local Network — required to discover the computer or the other phone.
    The app cannot do anything without it.
  • Photo Library — to pick photos to send, and to save photos received.
  • Camera — only to scan the pairing QR code displayed by the computer.

The app collects no personal data whatsoever; this matches the privacy
questionnaire and the bundled privacy manifest. Privacy policy:
https://droidtrans.mkstore.life/privacy.html
Support: https://droidtrans.mkstore.life/support.html

Please let us know if anything else would help the review.
```
