# RESEARCH NOTES — verified quotes (working file)

## §1 Process execution — CORE MECHANISM (all [verified] from AOSP source)

### Enforcement is SELinux, and keyed on targetSdkVersion
`system/sepolicy/private/seapp_contexts` (branch `main`):
```
user=_app minTargetSdkVersion=34 domain=untrusted_app       type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=32 domain=untrusted_app_32    type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=30 domain=untrusted_app_30    type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=29 domain=untrusted_app_29    type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=28 domain=untrusted_app_27    type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=26 domain=untrusted_app_27    type=app_data_file levelFrom=user
user=_app                        domain=untrusted_app_25   type=app_data_file levelFrom=user
```
URL: https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/seapp_contexts

`private/app_neverallows.te` (main) — STILL IN FORCE:
```
# Block calling execve() on files in an apps home directory.
# This is a W^X violation ... For compatibility, allow for targetApi <= 28.
# b/112357170
neverallow {
  all_untrusted_apps
  -untrusted_app_25
  -untrusted_app_27
  -runas_app
} { app_data_file privapp_data_file }:file execute_no_trans;
```
`all_untrusted_apps` = { ephemeral_app isolated_app isolated_app_all isolated_compute_app
mediaprovider mediaprovider_app untrusted_app untrusted_app_25 untrusted_app_27
untrusted_app_29 untrusted_app_30 untrusted_app_all }
=> targetSdk >= 29 (domain untrusted_app_29 / _30 / _32 / untrusted_app) : execve DENIED.
=> targetSdk <= 28 (untrusted_app_25 / untrusted_app_27) : execve ALLOWED.
URL: https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te

Also on main (new, gated on release flag RELEASE_UNLOCKED_STORAGE_API):
```
is_flag_enabled(RELEASE_UNLOCKED_STORAGE_API, `
  neverallow appdomain storage_area_content_file:file execute;
')
```

`private/untrusted_app_all.te` (main):
```
# Some apps ship with shared libraries and binaries that they write out
# to their sandbox directory and then execute.
allow untrusted_app_all privapp_data_file:file { r_file_perms execute };
allow untrusted_app_all app_data_file:file     { r_file_perms execute };
auditallow untrusted_app_all app_data_file:file execute;

# Chrome Crashpad uses the the dynamic linker to load native executables
# from an APK (b/112050209, crbug.com/928422)
allow untrusted_app_all system_linker_exec:file execute_no_trans;
...
# Allow the allocation and use of ptys
# Used by: https://play.google.com/store/apps/details?id=jackpal.androidterm
create_pty(untrusted_app_all)
```
=> **`execute` (mmap PROT_EXEC / dlopen) of app_data_file IS allowed; only `execute_no_trans` (execve) is not.**
=> This RESOLVES the parent's "highest-risk unknown": PRoot's loader can mmap(PROT_EXEC) guest binaries in app_data_file. Allowed.

Commit message (primary, verbatim), commit 0dd738d810532eb41ad8d90520156212ce756648, author Nick Kralevich:
"Enforce execve() restrictions for API > 28 — untrusted_app: Remove the ability to run execve() on files
within an application's home directory. Executing code from a writable /home directory is a W^X violation
... Note: this change does *not* remove the ability to execute code through other mechanisms, such as
mmap(PROT_EXEC) of a file descriptor from the app's home directory. In particular, functionality like
dlopen() on files in an app's home directory continues to work even after this change.
untrusted_app_25 and untrusted_app_27: For backwards compatibility, continue to allow these domains to
execve() files from the application's home directory. seapp_contexts: Bump the minimum API level required
to enter the untrusted_app domain. ... Bug: 112357170"
URL: https://android.googlesource.com/platform/system/sepolicy/+/0dd738d810532eb41ad8d90520156212ce756648

Android 10 behavior changes page (official):
"Removed execute permission for app home directory — Execution of files from the writable app home
directory is a W^X violation. Apps should load only the binary code that's embedded within an app's APK
file. Untrusted apps that target Android 10 cannot invoke execve() directly on files within the app's home
directory. In addition, apps that target Android 10 cannot in-memory modify executable code from files
which have been opened with dlopen() and expect those changes to be written through to disk..."
URL: https://developer.android.com/about/versions/10/behavior-changes-10

### apk_data_file (nativeLibraryDir) IS executable — proof
`public/global_macros` (main):
```
define(`x_file_perms',  `{ getattr execute execute_no_trans map }')
define(`r_file_perms',  `{ getattr open read ioctl lock map watch watch_reads }')
define(`rx_file_perms', `{ r_file_perms x_file_perms }')
```
URL: https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/public/global_macros
`private/app.te` (main):
```
# Allow apps to read/execute installed binaries
allow appdomain apk_data_file:dir { open getattr read search ioctl lock };
allow appdomain apk_data_file:file { getattr open read ioctl lock map x_file_perms };
```
=> `execute_no_trans` present => execve() of /data/app/.../lib/<abi>/* IS permitted.
URL: https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app.te

### Apps CAN exec /system/bin/sh and toybox
`private/app.te` (main):
```
# Execute the shell or other system executables.
allow { appdomain -ephemeral_app -sdk_sandbox_all } shell_exec:file rx_file_perms;
allow { appdomain -ephemeral_app -sdk_sandbox_all } toolbox_exec:file rx_file_perms;
```
`private/file_contexts`: `/system/bin/sh  -- u:object_r:shell_exec:s0` ; `/system/bin/toybox -- u:object_r:toolbox_exec:s0`
No type_transition untrusted_app->shell => the shell STAYS in untrusted_app domain and the app's UID.

### PTY without root — verified
`public/te_macros`:
```
# create_pty(domain)
# Allow domain to create and use a pty, isolated from any other domain ptys.
define(`create_pty', `
type $1_devpts, fs_type;
type_transition $1 devpts:chr_file $1_devpts;
allow $1 $1_devpts:chr_file { open getattr read write ioctl };
allowxperm $1 $1_devpts:chr_file ioctl unpriv_tty_ioctls;
neverallowxperm * $1_devpts:chr_file ioctl TIOCSTI;
# Note: devpts:dir search and ptmx_device:chr_file rw_file_perms allowed to everyone via domain.te.
')
```
`private/domain.te`:
```
allow domain devpts:dir search;
allow domain ptmx_device:chr_file rw_file_perms;
allowxperm domain devpts:chr_file ioctl unpriv_tty_ioctls;
neverallowxperm * devpts:chr_file ioctl TIOCSTI;
```

## §4 Android app seccomp = STRICT ALLOWLIST, default action SIGSYS
`bionic/libc/seccomp/seccomp_policy.cpp`:
```
inline void Allow(filter& f)    { ... SECCOMP_RET_ALLOW ... }
inline void Disallow(filter& f) { ... SECCOMP_RET_TRAP ... }
...
    ExamineSyscall(f);
    for (size_t i = 0; i < p_size; ++i) f.push_back(p[i]);
    Disallow(f);
...
    // This assumes either the current process has CAP_SYS_ADMIN, or PR_SET_NO_NEW_PRIVS bit is set.
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) < 0) { PLOG(FATAL) ...
```
URL: https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/seccomp/seccomp_policy.cpp
Default action for an unmatched syscall is SECCOMP_RET_TRAP => SIGSYS, not ENOSYS.

`SECCOMP_BLOCKLIST_APP.TXT` header (main):
"The final seccomp allowlist is SYSCALLS.TXT - SECCOMP_BLOCKLIST.TXT + SECCOMP_ALLOWLIST.TXT"
Blocked for apps (verbatim list): setgid/setuid/setregid/setreuid/setresgid/setfsgid/setfsuid/setgroups,
adjtimex, clock_adjtime, clock_settime, settimeofday, acct, syslog, chroot,
init_module, delete_module, mount, umount2, swapon, swapoff, setdomainname, sethostname, reboot.
NOTE: setresuid is explicitly NOT blocked (needed by zygote).
URL: https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/SECCOMP_BLOCKLIST_APP.TXT
=> apps can NEVER mount() or chroot(): killed by SIGSYS.

`bionic/libc/SYSCALLS.TXT` (main) — allowlist base (~133 syscalls). Present:
`chroot(...) all` (but blocklisted), `mount(...) all` (blocklisted), `umount2` (blocklisted),
`setns(int,int) all` (allowed!), `unshare(int) all` (allowed!), `__ptrace:ptrace(...) all` (ALLOWED),
`process_vm_readv/writev all`, `prctl(...) all`, `setrlimit(int, const struct rlimit*) lp32` (+ setrlimit64 for lp64),
`bpf(int, union bpf_attr*, unsigned) all` (!), `personality(unsigned long) all`,
socket syscalls via `__socket:socket(int,int,int) arm,lp64` etc.
ABSENT from SYSCALLS.TXT => not in allowlist: fork (lp32 only), posix_spawn (uses clone/vfork),
clone3 (added in SECCOMP_ALLOWLIST_COMMON.TXT), landlock, userfaultfd (added in COMMON allowlist), keyctl, iopl.
`SECCOMP_ALLOWLIST_COMMON.TXT` adds: clone, vfork(arm,x86,x86_64), seccomp(unsigned,unsigned,void*) all
("Needed for a CTS test of seccomp b/34763393"), execveat, clone3, perf_event_open, userfaultfd, membarrier.
=> the `seccomp(2)` syscall AND `prctl(2)` ARE allowed to apps.

## Zygote: no_new_privs is deliberately NOT set for apps
`frameworks/base/core/jni/com_android_internal_os_Zygote.cpp` (main), SpecializeCommon:
```
    // Must be called when the new process still has CAP_SYS_ADMIN, in this case,
    // before changing uid from 0, which clears capabilities.  The other
    // alternative is to call prctl(PR_SET_NO_NEW_PRIVS, 1) afterward, but that
    // breaks SELinux domain transition (see b/71859146).  As the result,
    // privileged syscalls used below still need to be accessible in app process.
    SetUpSeccompFilter(uid, is_child_zygote);
    ...
    if (setresuid(uid, uid, uid) == -1) { ... }
```
```
static void ensureInAppMountNamespace(fail_fn_t fail_fn) {
  if (gInAppMountNamespace) return;
  if (unshare(CLONE_NEWNS) == -1) { fail_fn(CREATE_ERROR("Failed to unshare(): %s", strerror(errno))); }
  gInAppMountNamespace = true;
}
static void SetUpSeccompFilter(uid_t uid, bool is_child_zygote) {
  if (!gIsSecurityEnforced) { ALOGI("seccomp disabled by setenforce 0"); return; }
  if (uid >= AID_APP_START) { if (is_child_zygote) set_app_zygote_seccomp_filter(); else set_app_seccomp_filter(); }
  else set_system_seccomp_filter();
}
```
URL: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/jni/com_android_internal_os_Zygote.cpp
=> (a) every app already runs in its own per-app MOUNT NAMESPACE, created by zygote (privileged), not by the app.
=> (b) app process has NO CAP_SYS_ADMIN and NO no_new_privs at runtime.
=> (c) BUT an app can call prctl(PR_SET_NO_NEW_PRIVS,1) itself (always permitted), then
       prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, ...) succeeds => apps CAN tighten seccomp. [verified mechanism]

`neverallow all_untrusted_apps cgroup:file *;` and `neverallow all_untrusted_apps cgroup_v2:file *;`
  ("Untrusted apps are not allowed to use cgroups.")
`neverallow all_untrusted_apps domain:anon_inode *;`  (blocks userfaultfd, io_uring)
`neverallow all_untrusted_apps selinuxfs:file no_rw_file_perms;` ("SELinux is not an API for untrusted apps to use")
`neverallow all_untrusted_apps file_type:file link;` (no hardlinks at all)

## isolatedProcess (docs, verbatim)
https://developer.android.com/guide/topics/manifest/service-element
"android:isolatedProcess — If set to "true", this service runs under a special process that is isolated
from the rest of the system and has no permissions of its own. The only communication with it is through
the Service API, with binding and starting."

`private/isolated_app.te`:
```
# Allow access to network sockets received over IPC. New socket creation is not permitted.
allow isolated_app { ephemeral_app priv_app untrusted_app_all }:{ tcp_socket udp_socket } { rw_socket_perms_no_ioctl };
allow isolated_app { sdcard_type fuse media_rw_data_file }:file { read write append getattr lock map };
allow isolated_app webviewupdate_service:service_manager find;
```
seapp_contexts: `user=_isolated domain=isolated_app levelFrom=user`
`private/app.te` sensitive domains neverallow includes `isolated_app` (no exec from /data at all).
URL: https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/isolated_app.te
