/// English copy. Structure is pinned to zh.ts by the Dict type —
/// miss a field and the build fails.

import {
  IconUpload, IconDownload, IconFiles, IconLink, IconResume,
  IconShield, IconHotspot, IconShare,
} from '../components/Icons'
import type { Dict } from './zh'

export const en: Dict = {
  code: 'en',
  htmlLang: 'en',
  label: 'English',
  switchTo: { href: '', label: '中文', title: '切换到中文' },

  nav: { features: 'Features', changelog: 'Changelog', pricing: 'Pricing', support: 'Support', download: 'Download' },

  hero: {
    eyebrow: 'Direct, local, never uploaded',
    title: ['Move anything between', 'your phone and computer'],
    lead: ['Both directions, any file, any brand. Over your own Wi-Fi\u00A0— ', 'straight', ' from your phone to your computer, never through a server.'],
    primary: 'Download free',
    secondary: 'See how it works',
    meta: (v: string, d: string) => `Version ${v} · ${d} · Available for macOS and Android`,
  },

  features: {
    eyebrow: 'Features',
    title: 'Everything you actually need',
    desc: 'Not a pile of checkboxes — the job of moving things between your phone and your computer, done properly.',
    items: [
      { icon: IconUpload, title: 'Phone → Computer', body: 'Send over Wi-Fi, or plug in a USB cable and let the computer pull your whole camera roll.' },
      { icon: IconDownload, title: 'Computer → Phone', body: 'Drop files or a whole folder onto the desktop window. Grab them on your phone in one tap, folder structure intact.' },
      { icon: IconFiles, title: 'Any file', body: 'Not just photos and video. Documents, archives, installers — up to 4 GB per file on the free plan.' },
      { icon: IconLink, title: 'Text and links', body: 'Both directions. Anything sent from your phone lands in your computer’s clipboard, ready to paste.' },
      { icon: IconResume, title: 'Resumable transfers', body: 'Both directions. Pick up where it stopped — nothing already transferred gets sent twice.' },
      { icon: IconShield, title: 'Pairing required', body: 'Scan a QR code once for a long-lived token. Nobody else on the network can connect.' },
      { icon: IconHotspot, title: 'No router needed', body: 'Turn on your phone’s hotspot, connect the computer, keep transferring. Works fine in a hotel room.' },
      { icon: IconShare, title: 'System share sheet', body: 'Share → DroidTrans from any app. Straight to your computer, no saving a copy first.' },
    ],
  },

  steps: {
    eyebrow: 'Getting started',
    title: 'Pair once, then just open it',
    desc: 'No account. No sign-up. Nothing to log into.',
    items: [
      { title: 'Open DroidTrans on your computer', body: 'It shows a QR code and a local address. Keep both devices on the same Wi-Fi.' },
      { title: 'Scan it with your phone', body: 'That’s the whole pairing step — no typing. Once per computer.' },
      { title: 'Start moving files', body: 'Send files across, or collect whatever is waiting on the computer in one tap.' },
    ],
  },

  cta: { title: 'Get it on your devices', desc: 'The desktop and mobile apps work together. Both free.', button: 'Go to downloads' },

  download: {
    eyebrow: 'Download',
    title: 'Pick your device',
    desc: 'The two halves work together: install one on your computer, one on your phone, then scan to pair.',
    soon: 'Coming soon',
    getDmg: 'Download DMG',
    getApk: 'Download APK',
    pendingIos: 'Coming to the App Store',
    footnote:
      'The macOS build is signed and notarized by Apple. Drag it to Applications, double-click, and it opens — no extra steps.',
    platforms: [
      {
        id: 'macos', name: 'macOS', tagline: 'The desktop half',
        requirement: 'macOS 12 or later　·　Apple Silicon',
        points: [
          { text: 'Drop files in the window to send' },
          { text: 'Pull the camera roll over USB' },
          { text: 'Text from your phone lands in the clipboard' },
          { text: 'A single app — no background service' },
        ],
      },
      {
        id: 'android', name: 'Android', tagline: 'The mobile half',
        requirement: 'Android 7.0 or later',
        points: [
          { text: 'Photos, any file, text and links' },
          { text: 'USB and Wi-Fi, both available' },
          { text: 'Straight from the share sheet' },
          { text: 'Resumable transfers' },
        ],
      },
      {
        id: 'ios', name: 'iPhone / iPad', tagline: 'The mobile half',
        requirement: 'iOS 15.5 or later',
        points: [
          { text: 'Photos, files, text and links' },
          { text: 'Straight from the share sheet' },
          { text: 'Resumable transfers' },
          { text: 'USB transfer (not permitted on iOS)', off: true },
        ],
        pending: true,
      },
    ],
  },

  compat: {
    eyebrow: 'Compatibility',
    title: 'What differs between platforms',
    desc: 'These are limits of the operating systems. Better to know before you install.',
    head: { feature: 'Feature', android: 'Android', ios: 'iOS' },
    rows: [
      { name: 'Two-way transfer over Wi-Fi', android: true, ios: true },
      { name: 'Any file type', android: true, ios: 'Via the Files app' },
      { name: 'Text and link transfer', android: true, ios: true },
      { name: 'Resumable transfers', android: true, ios: true },
      { name: 'System share sheet', android: true, ios: true },
      { name: 'QR code pairing', android: true, ios: true },
      { name: 'Pull camera roll over USB', android: true, ios: 'Not permitted' },
      { name: 'Direct hotspot connection', android: true, ios: 'Turn hotspot on manually' },
    ],
  },

  pricing: {
    eyebrow: 'Pricing',
    title: 'The basics stay free. Always.',
    desc: 'Local transfer, resumable uploads, pairing, share sheet — all of it, with no limits. Paying buys you convenience, not access.',
    freeTitle: 'Free',
    freeNote: 'No account, no payment',
    free: [
      'Two-way Wi-Fi transfer, up to 4 GB per file',
      'Resumable — pick up where it stopped',
      'QR pairing; nobody else on the network gets in',
      'Text and link transfer',
      'Straight from the system share sheet',
      'Send photos over USB, manually',
      'Photos library export, 1000 items per run',
    ],
    proTitle: 'Pro',
    proNote: 'One code covers macOS, Android and iOS',
    pro: [
      'No file size limit',
      'Unlimited Photos library export',
      'Incremental library sync — only what’s new',
      'Complete Live Photo export — the still and its video',
      'Whole-album USB import',
      'Deduplicate across transfers',
      'Every Pro feature added later',
    ],
    periods: { year: '1 year', years3: '3 years', lifetime: 'Lifetime' },
    hint: { year: '', years3: 'Two years, third one free', lifetime: 'Pay once, never again' },
    buy: 'Buy',
    buying: 'Redirecting…',
    failed: 'Checkout failed, please try again',
    devices: 'One code activates on up to 5 devices',
    refund: 'Refundable if it’s not for you',
    haveCode: 'Already bought? Enter your code in the desktop app’s settings.',
  },

  thanks: {
    eyebrow: 'Payment received',
    title: 'Here is your activation code',
    titlePlain: 'Thank you for your purchase',
    desc: 'Keep it somewhere safe. Enter it under Settings → License in the desktop app to unlock the Pro features.',
    descPlain: 'If your code isn’t shown here, send us the email address you paid with and we’ll get it to you right away.',
    codeLabel: 'Activation code',
    waiting: 'Issuing your code…',
    copy: 'Copy',
    copied: 'Copied',
    failed: 'We couldn’t retrieve your code just now. Your payment did go through — get in touch with the email address you paid with and we’ll sort it out immediately.',
    steps: [
      'Open DroidTrans on your computer',
      'Go to Settings → License',
      'Paste the code and activate',
    ],
    note: 'One code activates on up to 5 devices, across macOS, Android and iOS.',
    back: 'Back to home',
    support: 'Get in touch',
  },

  footer: {
    home: 'Home', download: 'Download', changelog: 'Changelog', pricing: 'Pricing', support: 'Support', privacy: 'Privacy',
    tagline: 'Files move directly between your devices. They never touch our servers.',
  },

  meta: {
    index: {
      title: 'DroidTrans — Move files between phone and computer',
      desc: 'Two-way transfer between phone and computer: any file, any brand, over your local Wi-Fi. Files never touch a server. For Android, iOS and macOS.',
    },
    download: {
      title: 'Download — DroidTrans',
      desc: 'Get DroidTrans: macOS desktop app, Android APK. iOS coming to the App Store.',
    },
    privacy: { title: 'Privacy — DroidTrans', desc: 'DroidTrans collects nothing, uploads nothing, and stores nothing about you.' },
    support: { title: 'Support — DroidTrans', desc: 'Common questions and how to reach us.' },
    pricing: { title: 'Pricing — DroidTrans', desc: 'The basics stay free. Pro is one code for all three platforms: $1.99/year, $3.99/3 years, $9.99 lifetime.' },
    thanks: { title: 'Thank you — DroidTrans', desc: 'Your DroidTrans Pro activation code.' },
    changelog: { title: 'Changelog — DroidTrans', desc: 'What changed in each release of DroidTrans.' },
  },
}
