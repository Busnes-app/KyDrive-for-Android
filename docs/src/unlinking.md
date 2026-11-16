# Sign-out, revocation and unlinking

These actions have different effects. Copies you saved outside KyDrive are not part of
the app's local session and are not removed by any of them.

| Event | What the phone does | What remains on the server |
|---|---|---|
| Ordinary invalid/expired token | Shows signed out and asks to link again; retains the account binding | Server files and the server's device record |
| Server revokes this device | Erases local keys and session when the next request receives `device_revoked` | Server files; server manages the revoked record |
| Unlink this phone | Tries to revoke the device, then erases local keys/session even when unreachable | Server files; remove a stale device record manually if the request failed |
| Wrong-PIN erase threshold reached | Erases the app's local keys/session | Server files; manage the old device record in the web app |

## Link again after sign-out

Generate a fresh code for the same account and server, then [link again](linking.md).
The app refuses a replacement link to another account or origin until you unlink.

## Unlink from the phone

1. Open **Settings → Account → Unlink this phone**.
2. Read the confirmation naming the server, then choose **Unlink this phone**.
3. The app attempts revocation and wipes its local session regardless of the result.
4. If it says the server could not be reached, remove that phone under **Settings →
   Phones & apps** in the web app too.

Unlinking deletes keys before removing the stored session, making encrypted session
data unreadable. An interrupted wipe is retried when the app next loads its session.
It does not delete your server workspace or files you saved through Android's picker.

## Revoke a lost phone

Use an authorized browser session to revoke the phone in the server's device settings.
Future server requests using that credential are refused. A phone that is offline
cannot receive the revocation until it contacts the server; revocation is not a
remote guarantee that every previously delivered byte has vanished.

Also use Android's own lost-device controls as appropriate. Protect separately saved
copies and copies already delivered to other apps using their own controls.
