# rex for Android: privacy policy (draft)

*Last updated: (date of publishing)*

rex for Android runs a media server on your device. It has no account with us, no ads and no
analytics, and it does not send us your library, your viewing or anything about you, except
for the one feature named below.

## What leaves your device, and where it goes

| Where | What | When |
|---|---|---|
| **plex.tv** (Plex, Inc.) | A sign-in code request with a random app identifier and this server's name; then, once you approve it, a one-time request for a claim code | When you choose Sign in to Plex |
| **plex.tv** | The server's name, identifier, version and the addresses the Plex apps can reach it at | While rex runs and is signed in, as any Plex server reports itself |
| **Plex's metadata service** | The ids of the titles your addons add, to fetch posters and descriptions | While rex runs and is signed in |
| **debridmediamanager.com** (rex-direct) | The server's identifier, the server token Plex gave it, its local and public IP addresses and its port | Once signed in, then every 6 hours, so the Plex apps can reach rex over HTTPS away from home |
| **Addon repositories you add** (such as github.com) | A request for the repository's contents | When you add or refresh a repository |
| **Each addon's sites** | What the addon asks for, such as a catalog request to archive.org | When an addon syncs or a title plays |

Your Plex account password never passes through the app: you sign in on Plex's own page. The
account token that sign-in gives the app is used once to claim the server, then discarded. rex
keeps only the server token Plex issues for it.

## What stays on your device

Your libraries, watch history, addon settings (including any keys you type in) and logs. Logs
hide tokens, keys and passwords, and you only share them if you choose Share on the Logs screen.
Uninstalling the app deletes all of it.

## Addons

An addon is code from whoever wrote it, installed from a repository you added. It runs sealed
off from the rest of your device and can only reach the sites it names, which rex shows you
before you install it. Install addons only from people you trust.

## Children

The app is not directed at children.

## Contact

(support email)
