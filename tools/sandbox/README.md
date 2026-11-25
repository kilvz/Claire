# Sandbox native programs: provenance, licences and source releases

Mika's sandbox ships three executables, all in `app/src/main/jniLibs/arm64-v8a/`, installed into `nativeLibraryDir` with the APK.
Mika only runs them as child processes and does not link against them.

| File | What it is | Source | Version | Licence |
|---|---|---|---|---|
| `libproot.so` | PRoot itself, statically linked with talloc | PRoot: the `proot` source package in `sources.lock`, plus `patches/proot-*.patch`; talloc: the `talloc` source package in `sources.lock`, only `talloc.c` compiled, unmodified | PRoot 5.4.0, talloc 2.4.3 | GPL-2.0-or-later (PRoot), LGPL-3.0-or-later (talloc) |
| `libproot_loader.so` | PRoot's loader (static, `-nostdlib`) | the same PRoot source package | 5.4.0 | GPL-2.0-or-later |
| `libmikarun.so` | Mika's sandbox launcher (timeouts, network cut-off, cleanup, pseudo-terminal) | `app/src/main/cpp/mikarun/mikarun.c` | ships with Mika | MIT |

The full licence texts and copyright notices (`licenses/THIRD_PARTY_NOTICES.md`) are all in `licenses/` at the repository root.

## Bundled runtime

The runtime (Alpine 3.22 minimal root filesystem + Python, Node and other packages) ships with the APK, at
`app/src/main/assets/runtime/rootfs.tar.xz`. On the phone's first launch of Mika it is unpacked in the background into `files/sandbox/rootfs`, without network access.

- `runtime/sources.lock`:最小根文件系统的地址与 SHA-256,以及每个软件包的仓库、版本、APKINDEX 校验值、`.apk` 的 SHA-256。
- `runtime/pip-requires.lock`:要烤进 `site-packages` 的 wheel(名称、版本、文件名、下载地址、SHA-256、许可)。构建时逐个核对 SHA-256
  后直接解开,不运行 pip,所以离线和重编都不依赖 PyPI;换 Python 小版本后按 wheel 自己的 tag 重新求。这个文件和 `sources.lock`
  一样是构建的必需输入,缺了构建失败。wheel 也是随 APK 发出去的代码,所以 `license` 是构建的必需字段(记的是 wheel 自己的
  `METADATA` 声明的许可):许可为 GPL/LGPL/MPL 的 wheel,`runtime-sources.py` 会取同一版本的 sdist 放进源码包。
- `build-runtime.py`:按 `sources.lock` 下载、校验后拼成 `rootfs.tar.xz`。全程不运行 apk:按 apk 的布局解开软件包,
  并用软件包签过名的元数据写出 `/lib/apk/db/installed`,所以运行环境里的 apk 看到的数据库是一致的。
  每个 `.apk` 都要核对:锁文件里的 SHA-256、它自己的 RSA 签名(公钥取自钉住的最小根文件系统)、控制段校验值与 APKINDEX 一致、
  数据段与签过名的 `.PKGINFO` 里的 `datahash` 一致。会在手机上执行的安装脚本与触发器必须逐个列进 `SCRIPTS_NOT_RUN`
  (按内容的 SHA-256 钉住,并写明为什么不跑也正确),否则构建失败。
  同时生成 `app/src/main/java/com/wangzan101/mika/cap/sandbox/BundledRuntime.kt`(版本、SHA-256、大小)与
  `app/src/main/assets/runtime/packages.json`(所有软件包、版本、许可证;「开源许可」页从它生成)。
- 产物逐字节可重现:

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
# 运行环境里每个 GPL/LGPL/MPL 软件包:Alpine 的构建配方(钉在软件包记录的 aports 提交)与上游源码,逐个按 SHA-512 核对;
# 烤进 site-packages 的 copyleft wheel(许可写在 runtime/pip-requires.lock)取同一版本的 sdist,按 PyPI 的 SHA-256 核对
python3 tools/sandbox/runtime-sources.py "$OUT/runtime"
cp tools/sandbox/build-runtime.py tools/sandbox/runtime-sources.py app/src/main/assets/runtime/packages.json "$OUT/runtime/"
cp -R tools/sandbox/runtime "$OUT/runtime/lock"
COPYFILE_DISABLE=1 tar -czf "build/gpl-sources/$NAME.tar.gz" -C build/gpl-sources "$NAME"
```

运行后得到 `build/gpl-sources/mika-<版本>-gpl-sources.tar.gz`。上传前用 `tar -tzf` 看一眼,确认里面有两个源码包、`patches/`,
以及 `runtime/` 下每个 GPL/LGPL/MPL 软件包的目录、每个 copyleft wheel 的目录和 `runtime/SOURCES.txt`(gcc 等源码较大,整包约数百 MB)。
