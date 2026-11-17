# Link a phone

Linking authorizes this installation to access your account. Each phone has its own
device token and signing key, so an administrator can revoke one phone independently.

## Scan and confirm

1. On another screen, sign in to the KyDrive web app using your ordinary account.
2. Open **Settings → Phones & apps → Link a phone** and complete the server's step-up
   check. The server may label its device-management section differently in older versions.
3. On Android, tap **Scan the code**. Allow camera access and scan the QR code displayed
   by the server. The app scans QR codes; it does not accept a typed password or manual token.
4. Read the server hostname in **Link this phone?**. Continue only if it is the server
   where you opened the link screen yourself, then tap **Link**.
5. Wait for **Linked as …**. Open Settings to check the server and account.

The code is single-use and expires after two minutes. If it is already used or expired,
show a fresh one and scan again. Do not send the QR code to someone else or attach it
to a bug report: anyone who redeems it in time may link a device.

The app generates a non-exportable signing key before redeeming the code and verifies
that the returned device token belongs to the account the server identified. It then
stores the encrypted session and pins the server certificate's public key on the phone.
A pin included in the QR code must match during the exchange. Scanning a code without confirming and
successfully completing the exchange does not link a phone.

## Linking again

If the app says **This phone was signed out. Link it again**, show a new code on the
same server and for the same account. A signed-out installation retains its account
binding. Linking it directly to a different account or server is refused; first use
[Unlink this phone](unlinking.md#unlink-from-the-phone).

If the server revokes the device, the app erases its local session when it next receives
that result. It must then link again as a new device.

## After linking

The app offers **Lock KyDrive with a PIN?**. Choose **Choose a PIN** to enable it, or
**Not now** and configure it later under Settings → Security. See
[Settings and app lock](settings.md) for the consequences of each setting.
