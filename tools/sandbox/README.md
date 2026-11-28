# Sandbox native programs: provenance, licences and source releases

Claire's sandbox ships three executables, all in `app/src/main/jniLibs/arm64-v8a/`, installed into `nativeLibraryDir` with the APK.
Claire only runs them as child processes and does not link against them.

| File | What it is | Source | Version | Licence |
|---|---|---|---|---|
| `libproot.so` | PRoot itself, statically linked with talloc | PRoot: the `proot` source package in `sources.lock`, plus `patches/proot-*.patch`; talloc: the `talloc` source package in `sources.lock`, only `talloc.c` compiled, unmodified | PRoot 5.4.0, talloc 2.4.3 | GPL-2.0-or-later (PRoot), LGPL-3.0-or-later (talloc) |
| `libproot_loader.so` | PRoot's loader (static, `-nostdlib`) | the same PRoot source package | 5.4.0 | GPL-2.0-or-later |
| `libmikarun.so` | Claire's sandbox launcher (timeouts, network cut-off, cleanup, pseudo-terminal) | `app/src/main/cpp/mikarun/mikarun.c` | ships with Claire | MIT |

The full licence texts and copyright notices (`licenses/THIRD_PARTY_NOTICES.md`) are all in `licenses/` at the repository root.

## Bundled runtime

The runtime (Alpine 3.22 minimal root filesystem + Python, Node and other packages) ships with the APK, at
`app/src/main/assets/runtime/rootfs.tar.xz`. On the phone's first launch of Claire it is unpacked in the background into `files/sandbox/rootfs`, without network access.

- `runtime/sources.lock`: the address and SHA-256 of the minimal root filesystem, plus each package's repository, version, APKINDEX checksum and `.apk` SHA-256.
- `runtime/pip-requires.lock`: the wheels to bake into `site-packages` (name, version, file name, download URL, SHA-256, licence). At build time each SHA-256 is verified
  and then the wheel is unpacked directly, without running pip, so neither offline builds nor rebuilds depend on PyPI; after changing the Python minor version, re-resolve according to the wheel's own tags. Like `sources.lock`,
  this file is a required build input; without it the build fails. Wheels are also code shipped with the APK, so `license` is a required
  build field (recording the licence declared by the wheel's own `METADATA`): for wheels under GPL/LGPL/MPL, `runtime-sources.py` fetches
  the sdist of the same version into the source package.
- `build-runtime.py`: downloads and verifies according to `sources.lock`, then assembles `rootfs.tar.xz`. It never runs apk at all: it unpacks the packages following apk's layout
  and writes `/lib/apk/db/installed` from the package-signed metadata, so the database that apk inside the runtime sees is consistent.
  Every `.apk` must be verified: the SHA-256 in the lock file, its own RSA signature (the public key taken from the pinned minimal root filesystem), the control section checksum matching APKINDEX,
  and the data section matching the `datahash` in the signed `.PKGINFO`. Install scripts and triggers that would run on the phone must each be listed in `SCRIPTS_NOT_RUN`
  (pinned by content SHA-256, with a note on why not running them is still correct), otherwise the build fails.
  It also generates `app/src/main/java/com/wangzan101/mika/cap/sandbox/BundledRuntime.kt` (version, SHA-256, size) and
  `app/src/main/assets/runtime/packages.json` (all packages, versions, licences; the "Open-source licences" page is generated from it).
- Artifacts are byte-for-byte reproducible:

```bash
python3 tools/sandbox/build-runtime.py --resolve   # re-resolve dependencies after changing the Alpine minor version or a package, rewriting runtime/sources.lock
python3 tools/sandbox/build-runtime.py             # generate rootfs.tar.xz, BundledRuntime.kt, packages.json
python3 tools/sandbox/build-runtime.py --check     # regenerate and compare with the artifacts in the repo; exit 1 if they differ or an artifact is missing
```

`WANT` in `build-runtime.py` must match core's `SANDBOX_PACKAGES` (checked by `BundledRuntimeTest`).

`--resolve` resolves the dependency closure by branch index. When the minimal root filesystem is older than the index, that package remains the root filesystem's version
in the runtime (an `apk` entry in the lock file cannot replace a package bundled with the root filesystem), so when a package requires that exact version `--resolve` fails outright
and points out which minirootfs version to switch to.

The runtime includes `tzdata`. Each time a command starts, `ProcRunner.env` writes the phone's current IANA time zone into `TZ`,
so the local time of the command line, Python and Node matches the phone, preserving daylight-saving and historical offset rules; after the phone changes time zone, the next command updates accordingly.

## Files

- `sources.lock`: one line per entry, `name version download-URL SHA-256`. Both compilation and source packaging use only the source packages listed here, and the SHA-256 is verified after download.
- `patches/`: modifications to the upstream source, named `<name>-<serial>-<description>.patch`, applied in file-name order with `patch -p1`. Patches follow the licence of the component they modify.
  - `proot-0001-exitkill.patch`: adds `PTRACE_O_EXITKILL` to PRoot's tracing options. This way, when PRoot exits (even if killed by SIGKILL), the kernel kills all traced processes, and commands in the sandbox do not keep running in the background.
  - `proot-0002-seccomp-trap-enosys.patch`: the seccomp policy that Android installs for the app sends SIGSYS for new system calls (io_uring, clone3, faccessat2, epoll_pwait2, rseq and so on), and it takes priority over any filter installed later, so a program in the sandbox is killed as soon as it probes one. At the signal stop for this kind of SIGSYS (`si_code == SYS_SECCOMP`), PRoot rewrites the return value to `-ENOSYS` and cancels the signal, and the program takes its own fallback path; on arm64, faccessat2 with only `AT_EACCESS` is redirected to faccessat (the app process's real and effective identity are always the same, so the two are equivalent), and the permission decision still comes from the kernel.
  - `proot-0003-arm64-new-path-syscalls.patch`: PRoot 5.4.0's arm64 call-number table only goes up to statx (291); calls added later are not recognized by PRoot, and when the platform allows them they reach the kernel with untranslated paths and execute against host paths (for example, musl's `faccessat(…, AT_EACCESS)` tries faccessat2 first and checks the wrong file). Add these numbers and add them to PRoot's seccomp trace list: faccessat2 and fchmodat2 translate paths like faccessat and fchmodat; execveat (which would bypass execve handling), openat2 (`RESOLVE_*` rules against the directory tree inside the sandbox), io_uring_setup (path operations bypass system calls), the new mount interfaces (open_tree, move_mount, fspick, mount_setattr, open_tree_attr) and setxattrat and other *xattrat, file_getattr/file_setattr all return `-ENOSYS`, and callers fall back to the old calls, which PRoot translates normally. Also fix the bounds check when looking up the table (`index > length` changed to `>=`).
  - `proot-0004-link2symlink-fstat-nonfile.patch`: the link2symlink extension, when fstat returns, lstats the descriptor's `/proc` link as a path; for pipes, sockets and the like (`pipe:[…]`), after lstat fails the original code goes on to read an uninitialized stat structure, and depending on leftover stack values it sometimes turns a successful fstat into -1 (EPERM). Programs that check the standard descriptors at startup (node aborts outright) therefore fail at random. Change it so that a failed lstat means no processing; check the `" (deleted)"` suffix only after reading the link; and when the link target has disappeared return -errno instead of -1.
- `build-native.sh`: downloads the source from `sources.lock`, verifies the SHA-256, applies the patches, and cross-compiles the three programs with NDK 28.2.13676358 (API 30).

## Reproducing the build

```bash
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.2.13676358
tools/sandbox/build-native.sh           # compile and overwrite the artifacts in app/src/main/jniLibs/arm64-v8a/
tools/sandbox/build-native.sh --check   # only rebuild and compare SHA-256 with the artifacts in the repo; exit 1 if they differ
```

The build does path-prefix mapping and does not write a build-id, so artifacts built with the same NDK are byte-for-byte identical. Use `--check` to confirm that the binaries in the repo really come from this source.

## Releasing the source package together with the APK

Every time an APK is released, package the source packages listed in `sources.lock`, `patches/`, the build scripts and the licences into `mika-<version>-gpl-sources.tar.gz`, and put it on the same release page as the APK. Run from the repository root:

```bash
set -euo pipefail   # stop if any step fails, so no package with missing sources is produced
VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
NAME=mika-$VERSION-gpl-sources
OUT=build/gpl-sources/$NAME
rm -rf "$OUT" && mkdir -p "$OUT"
while read -r name version url sum; do
  case "$name" in ''|'#'*) continue ;; esac
  curl -fsSL "$url" -o "$OUT/$name-$version.tar.gz"
  echo "$sum  $OUT/$name-$version.tar.gz" | shasum -a 256 -c -
done < tools/sandbox/sources.lock
cp -R tools/sandbox/patches tools/sandbox/sources.lock tools/sandbox/build-native.sh tools/sandbox/README.md "$OUT/"
cp licenses/GPL-2.0.txt licenses/GPL-3.0.txt licenses/LGPL-3.0.txt "$OUT/"
# every GPL/LGPL/MPL package in the runtime: Alpine's build recipe (pinned to the aports commit in the package record) and the upstream source, each verified by SHA-512;
# copyleft wheels baked into site-packages (licence recorded in runtime/pip-requires.lock) take the sdist of the same version, verified by PyPI's SHA-256
python3 tools/sandbox/runtime-sources.py "$OUT/runtime"
cp tools/sandbox/build-runtime.py tools/sandbox/runtime-sources.py app/src/main/assets/runtime/packages.json "$OUT/runtime/"
cp -R tools/sandbox/runtime "$OUT/runtime/lock"
COPYFILE_DISABLE=1 tar -czf "build/gpl-sources/$NAME.tar.gz" -C build/gpl-sources "$NAME"
```

After running you get `build/gpl-sources/mika-<version>-gpl-sources.tar.gz`. Before uploading, take a look with `tar -tzf` to confirm that it contains the two source packages, `patches/`,
and, under `runtime/`, a directory for each GPL/LGPL/MPL package, a directory for each copyleft wheel, plus `runtime/SOURCES.txt` (sources such as gcc are large; the whole package is a few hundred MB).
