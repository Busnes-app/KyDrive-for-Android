# KyDrive for Android

KyDrive is the Android client for your organization's self-hosted
[KyDrive server](https://github.com/Busnes-app/kydrive-server). Use it to browse your
personal files and shared workspaces, open files in another Android app, upload files,
save copies to your phone, create folders, rename items and restore items from trash.

The server holds the files and decides who may access them. Your phone links to your
account by scanning a short-lived QR code from the web app. You do not type your suite
password into the Android app.

This manual follows the chapter-based mdBook format used by
[KyPost's documentation](https://www.kypost.org/docs/book/index.html). The chapter menu,
search button and print button help you find a task or save a reading copy. Markdown
sources are also readable directly on GitHub.

## Start here

- New user: [requirements and installation](getting-started.md), then [link your phone](linking.md).
- Everyday work: [files and workspaces](files.md) and [uploads and downloads](transfers.md).
- Protect or remove a link: [app lock](settings.md) and [unlinking](unlinking.md).
- Something went wrong: [troubleshooting](troubleshooting.md).
- Working on the app: [development and testing](development.md).

## Current scope

The app is pre-release software. This book describes implemented behavior, rather than
the full product roadmap. There are no published production releases or store listings
offered by this repository yet.

Automatic photo backup, persistent offline copies, two-way folder synchronization,
in-app document editing and a system DocumentsProvider are not available in this app.
Opening a file uses another installed app. Receiving an existing share is supported;
creating a new share or guest link uses the web app. The separate
[desktop client](https://github.com/Busnes-app/kydrive-desktop) provides two-way folder sync.

The approved roadmap and owner decisions are in
[DESIGN.md](https://github.com/Busnes-app/KyDrive-for-Android/blob/main/docs/DESIGN.md).
An item in that design document is not a claim that the feature has shipped.
