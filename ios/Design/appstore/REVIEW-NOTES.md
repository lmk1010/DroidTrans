# App Store 审核备注

提交时把「App 审核信息 → 备注」那一栏填上下面英文版的内容。

**为什么必须填**：卓传是配套 App，三条路径（电脑 / 另一台 iPhone / 安卓机）
都需要第二台设备。审核员拿一台 iPhone 打开，点进任何一条都是空雷达。
「无法审核，因为需要我们没有的配套设备」是这类 App 最常见的拒审理由
（审核指南 2.1 App 完整性）。备注里必须把怎么搭环境说清楚。

---

## 英文（直接粘进 App Review Notes）

```
DroidTrans is a companion app: it transfers files between a phone and a
computer over the local Wi-Fi network. Nothing goes through a server.

TO TEST THE MAIN FLOW YOU NEED THE FREE MAC APP:

1. On a Mac, download and open DroidTrans:
   https://droidtrans.mkstore.life/download.html
   (Free, no account, no payment. Apple-notarised .dmg.)
2. Put the Mac and the test iPhone on the same Wi-Fi network.
3. Launch the iOS app and tap "A computer".
4. Allow the Local Network permission prompt when it appears.
   Without it, iOS blocks discovery silently and no computer will be found.
5. The Mac shows a 6-digit pairing code in its window. Enter it on the phone.
6. You are now on the main screen and can send photos, files or text.

IF YOU CANNOT SET UP A MAC:
A 56-second screen recording of the complete flow (Mac app on the left,
iPhone on the right) is here:
   https://droidtrans.mkstore.life/review-demo.mp4
It shows the phone already paired with the Mac, files listed from the
computer, a 3.15 GB video being pulled down with live progress, the
received-files gallery, the transfer history, and the paywall.

IN-APP PURCHASE:
"DroidTrans Pro" is a single non-consumable, one-time purchase. There is
no subscription of any kind.

All transfer features are free and unlimited in count. Pro only lifts two
quotas — the 4 GB per-file size limit and the 1,000-item photo library
export limit — and adds incremental library sync and complete Live Photo
(photo + paired video) export. The comparison table on the paywall lists
exactly what the free tier includes.

To reach the paywall: open the app, tap the account icon in the top right,
then "DroidTrans Pro". No account or login is required to purchase.
There is no external purchase link anywhere in the app.

PERMISSIONS:
- Local Network: required to find the computer. The app does nothing
  without it.
- Photo Library: to pick photos to send, and to save photos received.
- Camera: only to scan the pairing QR code shown on the computer.

PRIVACY:
The app collects nothing. Files move directly between the two devices on
the local network. See https://droidtrans.mkstore.life/privacy.html
```

---

## 提交前逐项核对

代码侧的已经处理好了，打勾的不用再动：

- [x] `PrivacyInfo.xcprivacy`（主 App 和分享扩展各一份）
- [x] `ITSAppUsesNonExemptEncryption: false`（只有 Curve25519 验签，属豁免）
- [x] `TARGETED_DEVICE_FAMILY: "1"`（仅 iPhone，不然要交 iPad 截图）
- [x] 权限描述中英各一份（审核员用英文机器，中文写死会显示中文）
- [x] 付费页有「服务条款 / 隐私政策」链接
- [x] 有「恢复购买」
- [x] **App 内没有任何指向站外购买的链接**（审核指南 3.1.1）
- [x] 6.9″ 截图 1320×2868，中英各三张

App Store Connect 里还得人工做：

- [ ] 建三个商品，ID 与 `Resources/Products.storekit` 一致：
      `…pro.year`、`…pro.years3`（非续期订阅）、`…pro.lifetime`（非消耗型）
- [ ] 填隐私问卷：全选「不收集」，和 `PrivacyInfo.xcprivacy` 保持一致
- [ ] 支持网址 `https://droidtrans.mkstore.life/support.html`
- [ ] 隐私政策网址 `https://droidtrans.mkstore.life/privacy.html`
- [ ] 把上面那段英文粘进「App 审核信息 → 备注」
- [ ] Xcode 登录开发者账号（team 24D88Q3K3S），否则 archive 直接报 No Accounts

## 3.1.1 是这次最容易踩的一条

原来「我的 → 激活码」那一屏底下有一句
「还没有激活码？**去看看定价 →**」，点开是官网定价页 —— 那是可以直接下单的。

> 审核指南 3.1.1：App 不得包含引导用户使用非 App 内购买机制的按钮、
> 外部链接或行为召唤。

已经改成退回上一层的 App 内购页。**以后往 App 里加任何指向官网的链接，
都要先想一遍那个页面能不能买东西。** 现在保留的三个外链是安全的：
支持页、隐私政策、电脑端下载页。
