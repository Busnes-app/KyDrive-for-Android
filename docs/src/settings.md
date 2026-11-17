# Settings and app lock

Open **Settings** from the main screen. Linked installations show the server, account
and device name under **Account**, followed by the security options.

## Appearance and navigation

**Appearance → Mode** offers **System**, **Light** and **Dark**. System follows Android;
the other choices override it for this app. On a large screen, choose **Left** or
**Right** for **Navigation rail on large screens**. The initial rail side is Right.
These preferences are stored on this installation.

**About** shows the app version and licence information. Include the version and full
source commit, if known, when reporting a development-build problem.

## Enable a PIN

1. Under **Security**, turn on **Lock KyDrive with a PIN**.
2. Enter a new PIN of 8–12 digits, then enter it again.
3. Complete setup while the app remains on screen. If it leaves the screen during
   setup or checking, the lock may require you to unlock again before showing files.

Very common PINs, repeated digits and simple ascending/descending runs are refused.
Do not use your account password as the PIN. A PIN protects this app's user interface;
it is not your server password and cannot recover a server account.

## Lock and erase settings

| Setting | Choices | Initial value |
|---|---|---|
| Lock after leaving the app | At once, 30 s, 5 min | 30 s |
| Erase KyDrive from this phone after wrong PINs | 10, 20, 30, Never | 30 |
| Unlock with fingerprint or face | Strong biometric when supported | Off until enabled |

The third wrong PIN introduces a 30-second delay. Later failed attempts increase the
delay through one minute, five minutes, 15 minutes and 30 minutes to an hour. Failed
attempts are persisted; restarting the app is not a way to gain fresh guesses.

Reaching the selected erase threshold removes the app's local keys and session. Server
files and separately saved copies remain. **Never** disables that automatic erase
threshold; it does not disable the delays.

Turning the lock off, lengthening its grace period or increasing/disabling its erase
threshold requires the current PIN. **Change PIN** asks for the current PIN first.
Strengthening the settings does not require weakening the existing protection.

## Biometric unlock

If Android supports a strong fingerprint or face authenticator, enable **Unlock with
fingerprint or face** and confirm your PIN. The option may be absent when the device
does not support a sufficiently strong authenticator.

Adding or changing biometric enrollment invalidates the app's biometric key. Use the
PIN, then authorize biometrics again. Biometric availability and screen-unlock settings
are also controlled by Android.

## If you forget the PIN

There is no PIN recovery or remote unlock. You can remove the app's local data through
Android settings or reinstall it and link again. That removes this installation's
session; revoke the old phone entry in the server's device settings as well. Server
files remain available through another authorized session.

Locking also stops further file reads through KyDrive's open-file stream. It cannot
recall bytes that another app already read. Background transfers may continue while
the screen is locked; see [Privacy and security](security.md).
