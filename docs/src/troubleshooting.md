# Troubleshooting

## Linking and connection errors

| Message or symptom | What to check |
|---|---|
| That is not a KyDrive link code | Scan the server's phone-link QR code, not a browser sign-in, desktop link or file-share QR code |
| Code was already used or expired | Generate a fresh code and scan within two minutes |
| Phone holds another account's files | Unlink in Settings before switching account or server |
| Could not reach the server | Check Wi-Fi/mobile data, LAN/VPN requirements, DNS and the exact HTTPS hostname |
| Phone does not trust the certificate | Ask the operator to check validity dates, hostname and complete certificate chain to Android system CAs; the app also enforces its certificate-strength policy |
| Server could not prove it is the same server | Check the hostname and contact the operator; certificate rollover needs the server identity key and a fresh signed statement. Link again only after confirming the server |
| Server refused to link this phone | Check server compatibility and its device quota; provide the error number to the operator |
| Camera cannot scan | Grant camera permission in Android settings, improve lighting and show a fresh unobstructed code |

The app has no "ignore certificate errors" switch. User-installed CAs are intentionally
not trusted. Fix the server certificate or use an appropriately trusted HTTPS endpoint.
Android's clock must be accurate for both certificate validity and signed-request time
checks. Older servers may require an upgrade to support phone linking and device signing.

## Files and workspace access

| Message or symptom | Next step |
|---|---|
| Shared is empty | Ask the workspace owner to verify your group membership or direct share |
| You don't have permission | Refresh; ask the owner about current access |
| That is no longer there | Refresh; the item may have moved, been trashed or become inaccessible |
| Changed on the server/name taken | Refresh and use a different name where appropriate |
| Not enough space | Ask the workspace manager about quota, trash and retained versions |
| No app opens this kind of file | Install a compatible viewer, or save a copy using the file's Actions menu |
| Can't upload: name/size unknown | Choose a normal file whose document provider exposes a valid name and size |
| File changed or arrived damaged | Refresh the listing and retry; remove any partial destination if its provider refused cleanup |

Write controls depend on the current workspace and role. They are absent in read-only
destinations and trash, and the server independently checks every request.

## Lock problems

For **Too many wrong PINs**, wait for the displayed delay. Restarting the app does not
clear failed attempts. If biometrics stopped after enrollment changed, unlock with
the PIN and enable biometrics again. If you forgot the PIN, see
[If you forget the PIN](settings.md#if-you-forget-the-pin).

A blank recent-app card or blocked screenshot is intentional screen protection in
production builds. It is not a rendering failure.

## Report a bug

Use [GitHub Issues](https://github.com/Busnes-app/KyDrive-for-Android/issues) for ordinary
bugs. Include the app version/source commit, Android version and device type, the
server version, reproduction steps, expected result, actual result and displayed
error number. Say whether it happens on Wi-Fi, mobile data or a VPN.

Do not include device tokens, PINs, QR codes, private filenames or file contents.
Report suspected vulnerabilities privately through
[SECURITY.md](https://github.com/Busnes-app/KyDrive-for-Android/blob/main/SECURITY.md).
