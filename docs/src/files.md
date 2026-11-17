# Files and workspaces

## Navigate

**Files** opens **My files**, your private personal workspace. The server creates that
workspace on first use if necessary. **Shared** lists organization workspaces you may
access and files or folders shared directly with you.

Tap a workspace or folder to enter it. The Up button and Android Back move through the
current tab's folder history. Files and Shared keep separate histories during the
current session. Workspaces and folders appear before files, sorted by name; file rows
show their size and modification date when available.

Use **More options → Refresh** to reload the current listing. An empty personal folder
says **This folder is empty**. An empty Shared list means there are no visible shared
destinations for this account; ask the workspace owner about membership.

## Permissions

| Label | What Android exposes |
|---|---|
| Can view | Browse, open and save permitted files |
| Can edit | View actions, uploads, new folders, rename, trash and restore |
| Can manage | The same file actions; permission management uses the web app |

The server remains authoritative. A permission can change while a screen is open, and
the server can refuse an action even when it was previously visible. A direct share
does not grant access to the rest of its workspace. People and group membership are
managed through the suite's directory and web administration, not the Android app.

## Open a file

Tap the file, or use its **Actions → Open** menu. Android starts a compatible installed
viewer. KyDrive grants that viewer read access to a stream of the selected revision;
it does not make a persistent offline copy. If the file changes on the server, the
old stream cannot silently switch to the new revision.

If no viewer is installed, the app says **No app on this phone opens this kind of
file**. Install a suitable viewer, or use [Save to this phone](transfers.md#save-a-copy).
Changes made in another app are not automatically uploaded to KyDrive.

## New folder and rename

1. Open a writable workspace and the destination folder.
2. For a new folder, choose **Add → New folder**. To rename an item, open the **Actions**
   button beside its name and choose **Rename**.
3. Enter the name and tap **OK**. Cancel leaves the item unchanged.

Names must occupy 1–255 UTF-8 bytes, without slashes, backslashes, control/format
characters or leading/trailing whitespace; `.` and `..` are not accepted. Some
non-English characters occupy several bytes. The server can also reject a conflicting
name. Refresh the listing before retrying a conflict.

## Trash and restore

Choose **Actions → Move to trash** to trash an item. The confirmation message offers
**Undo**, which asks the server to restore it. To restore later:

1. Open its workspace and choose **More options → Trash**.
2. Find the item, open **Actions**, and choose **Restore**.
3. Return to the live listing and refresh if needed.

Trash is a flat list for the workspace. Restore requires write permission. Android
does not expose permanent deletion or trash-retention controls. Trashed content and
retained versions may still count against the server's storage quota.
