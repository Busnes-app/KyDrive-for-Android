# Uploads and downloads

## Upload files

1. Open the writable workspace and folder where the files should go.
2. Choose **Add → Upload files**.
3. Select one or more files in Android's file picker.
4. Watch for **Uploading …**, followed by **Uploaded …** or an error.

The selected provider must expose a valid filename and a known size. If either is
missing or unsupported, the app says **Can't upload …: its name or size is unknown or
not allowed**. Save the item as an ordinary file in a provider that supplies both,
then select that copy.

Uploads run in the app process and may continue after you leave the screen. They are
not scheduled work that is guaranteed to survive Android killing the process. The
wire layer uses 8 MiB chunks and asks the server for the accepted offset after a failed
chunk, with at most five failures for that chunk. There is no user-facing persistent
transfer queue or restart/resume button yet. Refresh after an uncertain result before
starting another upload.

## Save a copy

1. Open a file's **Actions** menu and choose **Save to this phone**.
2. In Android's save picker, choose a destination and filename, then confirm.
3. Wait for **Saved …** before relying on the copy.

The destination may be local storage or another document provider. The downloaded
bytes must match the size and SHA-256 digest the server listed. A changed or damaged
file fails with **The file changed or arrived damaged, so nothing was kept. Try again**.
The app attempts to remove the incomplete destination; a provider that refuses removal
may leave a partial file, which you should delete using that provider's own controls.

## Opening and saving are different

**Open** lets another app read a stream while KyDrive remains linked, unlocked and able
to reach the server. It does not create an offline copy for you.

**Save to this phone** writes a separate copy to your chosen destination. That copy is
outside KyDrive's encrypted session storage and remains after unlinking or revocation.
Protect and delete it using the destination app or Android's file manager. Edits to a
saved copy do not synchronize automatically; upload a file explicitly if you need it
stored on the server.

## Failed transfers

- **Not enough space:** the workspace quota is full. Ask its manager about quota and
  retained content. Android cannot increase a quota or empty server trash permanently.
- **Permission denied:** refresh and check your workspace access with its owner.
- **Changed on the server/name taken:** refresh; do not assume a retry may overwrite
  someone else's version.
- **Network lost or app stopped:** reconnect, refresh and verify the server/destination
  before repeating the transfer.

See [Troubleshooting](troubleshooting.md) for connection and account errors.
