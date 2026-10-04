# Rebuilding the sandbox engine

Everything in `out/` is derived. This script reproduces it from `jni/qjs_jni.c`
and a checkout of QuickJS.

Not wired into the build: the app does not compile or ship this. Run it by hand
when the sandbox work resumes.

## What it produces

`out/libqjssandbox-<abi>.so` — QuickJS plus the JNI layer, built for one Android
ABI at a time. Measured sizes:

| ABI | Size |
|---|---|
| `x86_64` (emulator) | 1093 KB |
| `arm64-v8a` (phone) | 1025 KB |

Both were built and the x86_64 one was run on a device. The arm64 build compiles
but has not been executed.

## Prerequisites

- NDK. `28.2.13676358` is what this was verified against:
  `sdkmanager --install "ndk;28.2.13676358"`
- QuickJS source. There is no release archive worth using; clone it:
  `git clone --depth 1 https://github.com/bellard/quickjs.git`

## Build

```powershell
pwsh -File build-qjs.ps1 -Abi x86_64      # emulator
pwsh -File build-qjs.ps1 -Abi arm64-v8a   # phone
```

Then copy the result into `app/src/main/jniLibs/<abi>/libqjssandbox.so`.

## Two things that are easy to get wrong

**`CONFIG_VERSION` is not in the headers.** QuickJS's Makefile passes it as a
`-D` from the `VERSION` file. Without it the engine fails to compile, with an
error pointing at a `fprintf` in `quickjs.c` rather than at anything to do with
versions.

**Six source files, not one.** `quickjs.c` alone does not link: `libregexp.c`,
`libunicode.c`, `cutils.c` and `dtoa.c` are needed, and the Unicode tables are
generated headers checked in beside them.
