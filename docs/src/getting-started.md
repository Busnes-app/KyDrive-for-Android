# Getting started

## Requirements

| Requirement | What you need |
|---|---|
| Phone or tablet | Android 12 (API 31) or newer |
| Account | An active account on a KyDrive server with phone QR linking and device request signing |
| Network | The phone must reach the server's HTTPS hostname; connect to the organization's LAN or VPN if required |
| Server certificate | A valid certificate for that hostname, chaining to an Android system CA |
| Camera | Needed to scan the link code; permit camera access when Android asks |
| File viewer | An installed app that can open the file type you choose |

Google Play Services, Firebase, a push distributor and a separate KyAuth app are not
required. KyDrive Android contains no analytics or crash-reporting SDK.

The app refuses cleartext HTTP, user-installed certificate authorities and redirects.
An administrator must configure the final HTTPS origin directly. Installing a private
CA on the phone does not make that CA acceptable to this app.

## Install a development build

No production download is currently published. Developers can build and install a
debug APK for testing; follow [Development and testing](development.md#build-and-install).
Use a test account and a disposable device or emulator when evaluating a development
build. An unsigned release APK is a build check, not an installable published release.

When updating an existing development installation, use a build signed with the same
key. Android rejects an update signed with a different key. Uninstalling to switch
keys removes the app's local session and requires linking again; copies saved outside
KyDrive and files on the server remain separate.

## First run

1. Open KyDrive. An unlinked app shows **Link this phone** and **Scan the code**.
2. [Link the phone](linking.md) from a browser session on a second screen.
3. Check the displayed account, then choose whether to set an app PIN when offered.
4. Open **Files** for your personal workspace or **Shared** for shared workspaces and
   directly shared items.

Settings remains available before linking for appearance preferences and version
information. Account and security controls appear after a phone is linked.
