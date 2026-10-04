# Can an Android app run agent-authored code in a real sandbox?

Investigation record. **Nothing here is implemented in the app.** The app ships
file tools, a clock and web search; this is what was learned while working out
whether it could also run code, and it is kept so the question does not have to
be answered twice.

Status: the architecture is **verified feasible on a device**. It is not built.

---

## The requirement that shaped everything

> The sandbox may read and write the conversation directory, and may use the
> network. It must not be able to read **anything else** — not the app's private
> data, not other apps' data, not the filesystem. And this must not depend on a
> prompt or on the interpreter behaving.

Two clauses in that pull in opposite directions and it is worth naming why:

- **Files, yes.** So the sandbox needs real read and write on one directory.
- **Network, yes.** So whatever runs it can send data anywhere.

Together those mean the only thing standing between an agent-authored script
and the user's API key is the boundary between "the conversation directory" and
"everything else". If that boundary is enforced by the interpreter's own
permission system, it is a seat belt. The requirement says seat belts are not
enough.

## What the industry does, in one line each

From [agent-sandbox-comparative-research.md](agent-sandbox-comparative-research.md):

- **Network policy is the control, not the isolation primitive.** gVisor's own
  security model says it defends against host compromise, not against data
  leaving.
- **Anthropic states the invariant plainly:** filesystem and network isolation
  are inseparable. "Without network isolation, a compromised agent could
  exfiltrate sensitive files... without filesystem isolation, [it] could easily
  escape the sandbox and gain network access."
- **Language permission systems are documented as insufficient by their own
  authors.** Node.js: *"This feature does not protect against malicious code...
  It does not provide security guarantees in the presence of malicious code."*
  Deno's is better designed and its own guidance still ends at "use a VM".
- **Detection-based defenses are empirically dead.** Twelve published
  prompt-injection defenses bypassed at >90%; human red-teaming reached 100%.
  A classifier must not sit between the agent and a consequential action.
- **The escape hatch is where sandboxes leak.** Claude Code's default is to run
  **unsandboxed** if the sandbox fails to start, unless explicitly configured
  otherwise.

The through-line: **the boundary has to come from the operating system.**

---

## What Android actually provides

Read from AOSP sepolicy and then **measured on a device**, because the two
disagreed in three places.

### `android:isolatedProcess` is the strongest thing a developer gets

A `<service>` attribute. The platform runs that service in:

- a **throwaway UID** in 99000–99999 (observed: `u0_i9002`, `u0_i9006`…)
- the SELinux domain `isolated_app`, which is *not* part of `untrusted_app_all`
- **every permission check denied**, no data directory of its own
- a small allowlist of reachable Binder services

Its entire data interface is the Binder connection plus whatever file
descriptors the host explicitly passes across it. This is the same mechanism
WebView uses for renderer processes.

### `exec` from the data directory is blocked; `dlopen` is not

Android 10 (API 29) stopped apps executing binaries from their own data
directory, keyed on `targetSdkVersion`, enforced by SELinux rather than by a
permission — there is no opt-out. Termux's own repository still sets
`targetSdkVersion=28` to keep the exemption, and Google has been raising the
floor beneath it.

But the AOSP commit that introduced the restriction says explicitly that it
**does not** remove `mmap(PROT_EXEC)` of a descriptor from the app's home
directory, and that `dlopen()` on such files continues to work. The withheld
permission is `execute_no_trans` (i.e. `execve`), not `execute`.

**Consequence:** an interpreter can be a native library loaded into the process.
It cannot be a separate binary that gets executed.

### Three things the policy files got wrong

All three were found by running a probe, not by reading:

| Claim from the sources | What the device did |
|---|---|
| "isolated processes have no data directory" | `filesDir` returned the host's path; it simply does not exist there. So "path is empty" is **not** a usable sandbox test. |
| "the isolated domain lacks GID 3003, so it cannot create sockets" | Group list was `1065 3009`. `new Socket()` succeeded. Only `connect()` failed. |
| "reading a passed descriptor for `app_data_file` is undecidable from policy" | It works, with **no AVC denial**. |

The mechanism behind the third one is worth stating because the whole design
rests on it: the kernel checks permissions **at `open()`, in the opening
process, and never again**. The host opens the file under its own authority and
passes the description across Binder (`SCM_RIGHTS`). Reads through it are not
re-checked against the receiver's UID or SELinux context. *A file descriptor is
access that has already been granted, and it travels.*

---

## Measured results

Probe: [`probe/FdPassProbe.kt`](probe/FdPassProbe.kt), run on an x86_64 emulator
(API 36), app `targetSdk 36`.

```
pid=19914 uid=99009 isolated=true groups=1065 3009
tcp=DENIED: SocketException: socket failed: EACCES (Permission denied)
libDir=/data/app/~~.../lib/x86_64
list=libdatastore_shared_counter.so,libandroidx.graphics.path.so,libqjssandbox.so
loadLibrary=OK          loadAbs=OK

FDPASS:  SUCCESS, 29 chars: line one / line two / line three
BYPATH:  expected failure: ENOENT
OWNOPEN: expected failure: ENOENT

JS-ARITH: engine=2026-06-04 result={"ok":true,"value":"42"}
JS-MATH:  100-iteration loop + Math.sqrt + JSON.stringify → sum=5050
JS-DATE:  new Date(0).toISOString()
JS-REGEX: 'a1b2c3'.replace(/[0-9]/g,'#') → a#b#c#
JS-HANG:  while(true){} → {"ok":false,"error":"InternalError: interrupted","timeout":true}

AVC denials across the whole run: 0
```

Every one of those is what the design needs:

| Requirement | Result | Enforced by |
|---|---|---|
| Interpreter runs in the throwaway UID | yes | — |
| A runaway script stops | yes, after the 5 s budget | the interrupt handler |
| Reads a host-opened file | yes | — |
| Reads an app-private file by path | ENOENT | **SELinux** |
| Reaches the network | EACCES on `connect()` | **kernel** |

The last two are the point. They do not depend on the interpreter's permission
model, or on the adapter layer being written correctly. A bug in that layer
still gets `EACCES`.

## The architecture this implies

```
┌─ host process (app UID: INTERNET, workspace access) ─────┐
│  the only policy point                                   │
│    run_code                                              │
│      → validate path through SessionWorkspace (exists)   │
│      → open the file, pass the descriptor                │
│      → make the outbound request, log it                 │
│      ← return text, with a quota                         │
└──────────────────── Binder ──────────────────────────────┘
┌─ isolated process (throwaway UID, no permissions) ───────┐
│  libqjssandbox.so  ← System.loadLibrary, never exec      │
│    file API  → virtual FS rooted at the session dir      │
│    net API   → ask the host; socket() would be denied    │
└──────────────────────────────────────────────────────────┘
```

Three capabilities, three sources, none able to exceed its grant:

- **Files** come from the host, path-checked by the code that already exists.
- **Network** comes from the host, because the sandbox cannot open a socket.
- **Everything else** is denied by the platform, not by this code.

## Why the virtual filesystem, rather than path checking

A deny-list has to enumerate: the app's private data, `/proc`, other apps,
shared storage, logcat, the clipboard. It will always be incomplete.

If the interpreter's file API is wired to a virtual filesystem rooted at the
conversation directory **with no fallthrough to real paths**, then everything
outside is not *refused* — it does not exist. That is a construction rather
than a check.

The cost: writes have to be mediated too, because the sandbox cannot open
anything. Mapping the language's file API onto Binder calls is what Pyodide's
MEMFS, WASI preopens and E2B's overlay all do, so it is a well-trodden shape.
The honest caveat is that it cannot be presented as full POSIX.

## Why QuickJS

Measured: **1093 KB** for the engine plus the JNI layer, x86_64.

- Self-contained. No standard-library tree to deliver, which is the specific
  thing that makes Python awkward here — the sandbox cannot see a filesystem, so
  a stdlib would have to arrive through the virtual FS as a second root.
- Models write JavaScript well, which matters more than it sounds: the code the
  agent produces is the user's experience of the feature.
- MIT.

Python (Chaquopy, now MIT and free) would add an estimated 15–40 MB and bring
that stdlib problem. It also cannot install native packages — no numpy, no
pandas — because those need `exec` or nested library paths, so the main reason
to want Python is unavailable anyway.

## Limitations that are real

- **No `exec`, ever.** `mount`, `umount2` and `chroot` are seccomp-blocked and
  die with `SIGSYS`; the app seccomp filter is a strict allowlist with
  `SECCOMP_RET_TRAP` as its default action. No chroot, no bind mount, no cgroups
  (`neverallow all_untrusted_apps cgroup:file *`), no hardlinks.
- **Resource limits are crude.** `setrlimit` works and children inherit it, but
  `RLIMIT_NPROC` is per *real UID* — an app-wide cap, not a per-child one — and
  `RLIMIT_AS` is dangerous against ART's address-space reservation. There is no
  public API to cap a child's CPU or memory. The practical shape is a heap limit
  inside the engine plus a wall-clock interrupt handler, which is what the probe
  uses.
- **Native packages are out of reach.** Covered above.
- **Package installation cannot work as it does elsewhere.** `pip install` and
  `npm install` need either network from the sandbox (denied) or a host-mediated
  mirror like the one OpenAI runs. Pure-source packages could be fetched by the
  host and unpacked into the virtual FS; anything with a compiled extension
  cannot.

## What is *not* verified

Stated plainly so the next attempt does not assume it:

- **ARM64.** Everything above was measured on x86_64. The engine has not been
  cross-compiled to `arm64-v8a`, which is what the phone needs.
- **The Binder protocol under load,** with concurrent calls and cancellation.
- **The virtual filesystem.** It is designed, not written.
- **The network proxy.** Same.
- **Whether a long-running script can be cancelled from the host** while a
  Binder call is in flight.
- **`isolatedProcess` on a real Android 16 phone.** Only an emulator was used;
  some OEM builds differ, and this project has already been bitten once by
  Xiaomi's own restrictions.

## The decision

Not built. The requirement it serves — "the agent can compute and draw" — is
met by ordinary tools for the cases that actually come up, and the remaining
cases are rare enough that a 1 MB engine, a virtual filesystem, a Binder
protocol and a network proxy are not yet worth their weight.

The measurements above are the expensive part and they keep. If the feature is
wanted later, the work starts from "wire up a virtual FS and a proxy", not from
"find out whether any of this is possible".
