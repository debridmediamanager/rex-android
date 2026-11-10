# Data safety form: answers

Play counts as *collected* anything the app sends off the device, even to a third party,
and as *shared* anything handed to a third party other than at the person's own request.

**Does your app collect or share any of the required user data types?** Yes.

**Is all of the user data collected by your app encrypted in transit?** Yes: plex.tv, Plex's
metadata service and debridmediamanager.com are HTTPS. (Addons choose their own sites; the
bundled one uses HTTPS, and repositories are fetched over HTTPS. rex talks to the Plex apps on the home network over plain HTTP as Plex
servers do, but that traffic never leaves the person's own devices.)

**Do you provide a way for users to request that their data is deleted?** Yes: uninstalling
removes everything on the device. rex-direct's record of a server is removed by deleting the
server in Plex (plex.tv, Authorized Devices); say so in the form's deletion URL field if asked
for one, pointing at the privacy policy.

| Data type | Collected | Shared | Purpose | Optional | Why |
|---|---|---|---|---|---|
| Device or other IDs | Yes | No | App functionality | Yes (signing in is skippable) | A random app identifier and the server's identifier go to plex.tv at sign-in, and the server's identifier to rex-direct |
| Approximate location | No | No | | | IP addresses are not a Play data type on their own; rex-direct stores the server's public IP only to point its HTTPS name at it |
| Personal info, Financial, Health, Messages, Photos, Audio, Files, Calendar, Contacts | No | No | | | |
| App activity, Web browsing | No | No | | | Watch history stays on the device and in the person's own Plex account |
| App info and performance (crash logs, diagnostics) | No | No | | | Logs stay on the device unless the person shares them |

Notes for Ben before submitting:
- The Plex account token comes from plex.tv, goes back only to plex.tv (to ask for a claim
  code) and is then discarded. Plex issued it, so it is not listed as data the app collects.
- **rex-direct sends the server token and IPs to debridmediamanager.com.** If that should not
  happen in the store build, set `REX_DIRECT_URL=off` for the app's rex and drop the row above.
