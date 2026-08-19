# Shared Storage Oracle

Shared Storage Oracle is a small Android app with no storage access
permission that makes the shared-storage metadata leak visible. Its
file-manager-style **Browse** tab starts in shared-storage `Download` and shows
every immediate child name Android returns. Tap a returned directory to
navigate into it or a returned file to inspect its exact path.

The **Namespace** tab does not use directory enumeration or `Files.walk`.
Instead, it generates valid Unicode-scalar filenames in increasing UTF-8 byte
length and calls `Files.exists` for each exact child path. Matching files and
directories appear as tappable file-manager rows. A small preset exhausts every
valid name through two encoded bytes; a separate preset samples the complete
Android-sized 255-byte component namespace and reports measured throughput.

The **Exact path** tab shows, separately:

- the answer from `java.nio.file.Files.exists` and related type checks;
- basic file attributes, including exact byte size and timestamps;
- the complete `android.system.Os.lstat` result;
- output from `/system/bin/ls -ld` executed by the app process;
- names returned by directory enumeration; and
- whether opening the file and reading one byte is allowed.

The app does not request storage, media, or all-files access. It never creates,
changes, or deletes the path being tested.

## What the result means

A result such as `Files.exists = true` for a file owned by another app is the
existence oracle. A successful stat can additionally disclose the file type,
exact size, timestamps, inode, mode, UID/GID, and allocation information. A
separate `Access blocked` result from `Files.newInputStream` shows that the file
contents are still protected.

An exact-path oracle does not make exhaustive name generation practical. Even
when restricted to Unicode scalar strings encodable in at most 255 UTF-8 bytes,
the filename namespace has hundreds of decimal digits. The Namespace tab makes
that distinction measurable: a short namespace can be fully exhausted, while a
full run reaches its candidate cap and projects completion time from the actual
device rate. Candidate names normally need to be known, predictable, or drawn
from a focused wordlist.

Upstream Android also exposes some shared-storage directory names through
enumeration; GrapheneOS filters that directory-name leak while still permitting
exact-path existence/stat queries. Consequently, Browse can look empty even
when a generated or otherwise guessed child path returns `Files.exists = true`.

## Build

The project requires Android SDK 37, Android 17 (API 37) or newer, and JDK 17
or newer.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The default test path is:

```text
/storage/emulated/0/Download/ExistenceOracleProbe/known-37-bytes.bin
```

Create that file outside the app, then compare it with the **Missing twin**
preset. The app automatically probes the known path when it starts.
