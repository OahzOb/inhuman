# Confining agent-authored code: a comparative research report

**Scope.** How established agent products and research systems isolate LLM-authored code, and what that implies for an Android-hosted chat agent that wants to execute model-written code.

**Evidence policy.** Every substantive claim is marked:
- **[verified]** — I fetched the primary source (official docs, team engineering blog, repo file, paper, vendor security policy) and the claim is stated there.
- **[uncertain]** — secondary source, forum post, community measurement, or my inference from primary material. Not safe to design against without re-checking.

**Known evidence gaps in this report** (deliberate, not oversights): OpenAI's `platform.openai.com` / `developers.openai.com` docs returned HTTP 403 to this agent, and `platform.claude.com` docs pages could not be fetched (cross-origin redirect loop). Where those are the only source, I say so and mark the claim **[uncertain]**, even when the content is very likely correct. Anything I could not find at all is marked "not documented publicly" rather than guessed.

---

## 1. OpenAI Code Interpreter / Advanced Data Analysis

The product surface is two things: the ChatGPT Code Interpreter, and the API `code_interpreter` tool backed by a *container* object (`/v1/containers`, used by both Assistants and Responses).

### Isolation

- Each Code Interpreter session runs in a sandboxed container that can write and run Python code iteratively. The product framing ("sandboxed execution environment") is consistent across OpenAI and Azure's mirror of the API. [verified] — [Azure OpenAI Code Interpreter docs](https://learn.microsoft.com/en-us/previous-versions/azure/foundry-classic/openai/how-to/code-interpreter)
- **The container runtime and OS are not documented publicly.** I could not find any OpenAI statement naming the isolation primitive (gVisor, Firecracker, runc, or other), the base image, or the kernel. Treat "it's a container" as the only defensible claim. [uncertain]
- Azure's hosted variant documents a session model: two concurrent Code Interpreter threads create **two** sessions; each session is active for **1 hour** with a **30-minute idle timeout**. This is Microsoft's implementation, not necessarily OpenAI's, but it is the closest documented sibling. [verified] — [Azure docs](https://learn.microsoft.com/en-us/previous-versions/azure/foundry-classic/openai/how-to/code-interpreter)

### Network access

The strongest evidence is an independent measurement of a live ChatGPT container, not vendor documentation.

- General-purpose outbound networking from user code is **blocked**. `urllib.request.urlopen(...)` fails immediately with `URLError: <urlopen error [Errno 101] Network is unreachable>`. [uncertain — third-party measurement] — [simonw/research: chatgpt-container-environment](https://github.com/simonw/research/blob/main/chatgpt-container-environment/README.md)
- Package installation **works**, but through an internal mirror rather than the public internet. Observed environment variables include `PIP_INDEX_URL=https://reader:****@packages.applied-caas-gateway1.internal.api.openai.org/.../pypi-public/simple`, `NPM_CONFIG_REGISTRY=.../npm-public`, `UV_INDEX_URL`, plus a policy variable `NETWORK=caas_packages_only`. So `pip install numpy` succeeding is **not** evidence of internet access. [uncertain — third-party measurement] — same source
- The same environment advertises internal registries for Go, Maven/Gradle, Cargo and Docker (`CAAS_ARTIFACTORY_*`). Docker images are reachable as registry artifacts even though no Docker CLI is installed. [uncertain] — same source
- `container.download` **can** fetch some public URLs, i.e. there is a privileged fetch lane separate from user-code egress; GitHub archive URLs that redirect to `codeload.github.com` are blocked. [uncertain] — same source
- Design consequence worth copying: **"no internet" is a spectrum, not a boolean.** The useful primitives are (a) direct egress from user code, (b) an allowlisted package gateway, (c) a host-mediated fetch tool the *harness* controls. OpenAI's sandbox has (b) and (c) and lacks (a).

### Filesystem and persistence

- Containers are explicitly documented as **ephemeral**: "We highly recommend you treat containers as ephemeral and store all data related to the use of this tool on your own systems." [verified — quoted verbatim in a developer-forum post from the then-current docs page] — [OpenAI Developer Community](https://community.openai.com/t/what-is-the-best-practice-for-keeping-containers-alive/1321557)
- **A container expires if it is not used for 20 minutes.** On expiry, using the container in `/v1/responses` fails; metadata snapshot remains visible but "all data associated with the container will be discarded from our systems and not recoverable." Any container operation — retrieving it, adding or deleting files — refreshes `last_active_at`. An expired container cannot be returned to active; you must create a new one and re-upload files, and "State in memory (e.g., Python objects) will be lost." [verified — verbatim quote of the docs page from a forum post] — same source
- `expires_after.minutes` could not be set above 20 by a developer, so they relied on `anchor: "last_active_at"`. [uncertain — forum report] — [Container Expiring during o3 Deep Research](https://community.openai.com/t/container-expiring-last-active-at-60-sec-during-o3-deep-research-background-mode/1367337)
- There is a documented **reported** (not confirmed-by-OpenAI) failure mode where containers expired even with `last_active_at` under 60 seconds old; OpenAI Support asked for request IDs to debug. This is a real operational hazard: **idle-timeout semantics implemented as "activity refreshes a timer" can still expire under load.** [uncertain] — same thread
- Filesystem **size quota** and memory/CPU limits: **not documented publicly** in anything I could reach. [uncertain]

### Output size and long-running processes

- **Not documented publicly** for either ChatGPT's Code Interpreter or the API container tool. There is a separate 20-minute container idle policy and a separate undocumented per-invocation execution ceiling. [uncertain]

### Language support

- Python is the first-class runtime. The supported-*file-type* list is broader than the supported-*language* list: `.c`, `.cpp`, `.csv`, `.docx`, `.html`, `.java`, `.json`, `.md`, `.pdf`, `.php`, `.pptx`, `.py`, `.rb`, `.tex`, `.txt`, `.css`, `.js`, `.ts`, `.xml`, `.xlsx`, `.zip`, images, `.tar`. [verified] — [Azure docs](https://learn.microsoft.com/en-us/previous-versions/azure/foundry-classic/openai/how-to/code-interpreter)
- The presence of `.java`/`.rb`/`.php`/`.js` in that list is a **file-ingestion** allowlist, not a claim that those runtimes execute. Reading it as language support would be a mistake. [uncertain — my reading]

### Documented abuse of this specific surface

- ChatGPT Code Interpreter has been the subject of repeated, publicly disclosed **prompt-injection → data-exfiltration** research. A representative case: ChatGPT's `url_safe` allow-listing mechanism permitted `*.blob.core.windows.net`, and because anyone can create an Azure storage bucket there with logging enabled, Markdown images rendered by ChatGPT became an exfiltration channel for chat history and memories. [uncertain — secondary summary of the primary disclosure] — [Simon Willison, "The Summer of Johann"](https://simonwillison.net/2025/Aug/15/the-summer-of-johann/) citing [embracethered.com](https://embracethered.com/blog/posts/2025/chatgpt-chat-history-data-exfiltration/)
- Root cause pattern: **the sandbox held, but the rendering/fetch layer outside it did not.** The code sandbox was not escaped; a harness-level allowlist was too permissive.

---

## 2. Anthropic: code execution tool, computer use, Claude Code sandboxing

### Code execution tool (API)

- Anthropic ships a server-side **code execution tool** with a documented failure mode: an invocation that exceeds the maximum execution time returns an `execution_time_exceeded` error, and a long-running turn can return a `pause_turn` stop reason. [uncertain — search-engine index of the docs page; I could not fetch `platform.claude.com` directly, which redirects cross-origin] — [Code execution tool](https://platform.claude.com/docs/en/agents-and-tools/tool-use/code-execution-tool)
- **Supported languages, memory/CPU/disk limits, network policy, and filesystem persistence for the code execution tool: not verified in this research.** I could not retrieve the page body. Anyone designing against this must read the current page themselves. [uncertain]

### Claude Code bash sandbox — the best-documented Anthropic mechanism

This is worth studying closely because it is an **in-process, OS-primitive** sandbox rather than a container, which is structurally the closest analogue to a sandbox embedded in a mobile app.

- Two boundaries are enforced together, at the OS level: **filesystem isolation** and **network isolation**. Anthropic's stated rationale is explicit and worth quoting: *"effective sandboxing requires both filesystem and network isolation. Without network isolation, a compromised agent could exfiltrate sensitive files like SSH keys; without filesystem isolation, a compromised agent could easily escape the sandbox and gain network access."* [verified] — [Anthropic Engineering, "Beyond permission prompts"](https://www.anthropic.com/engineering/claude-code-sandboxing)
- Primitives: **macOS Seatbelt** (built in) and **Linux/WSL2 bubblewrap** (`bwrap`), with `socat` used to relay traffic to the proxy. Built on the open-source `@anthropic-ai/sandbox-runtime` package. [verified] — [Configure the sandboxed Bash tool](https://code.claude.com/docs/en/sandboxing.md)
- Network model: the sandboxed process has **no direct route out**. All connections go through a proxy running **outside** the sandbox that checks each host against an **allowed-domains list which starts empty**. On macOS the proxy is reached over a Unix domain socket. [verified] — same page
- Filesystem defaults: **writes** limited to the working directory, a per-user temp directory, and explicitly added directories; **reads** cover "most of the machine, including credential files such as `~/.ssh` and `~/.aws/credentials`". Protecting credentials requires opting in via `credentials` / `filesystem.denyRead`. [verified] — same page
- The sandbox **wraps shell commands only**. Built-in file/web tools, hooks, local MCP servers, LSP servers, status-line helpers and `apiKeyHelper` **run outside it** with full access, and a `denyRead` rule does not reach the Read tool. [verified] — same page
- Documented limitations Anthropic itself calls out: the proxy "does not terminate or inspect TLS on outbound traffic", so **domain fronting can defeat the allowlist**; broad Unix-socket grants (e.g. `/var/run/docker.sock`) are an effective sandbox bypass; write access to `$PATH` directories or shell rc files is a privilege-escalation route; and `enableWeakerNestedSandbox` "considerably weakens security". [verified] — same page, "Limitations" section
- The sandbox is **off by default**, and on native Windows commands run unsandboxed (WSL2 required). Also: if the sandbox cannot start because a dependency is missing, Claude Code **runs commands unsandboxed** unless `sandbox.failIfUnavailable` is set. [verified] — same page
- Claude Code on the web takes the stronger route: each session runs in an isolated cloud sandbox, and **sensitive credentials are deliberately kept out of the sandbox**. Git operations are handled by a custom proxy that validates the scoped credential and the contents of the interaction (e.g. only allowing pushes to the configured branch) before attaching the real GitHub token. [verified] — [Anthropic Engineering](https://www.anthropic.com/engineering/claude-code-sandboxing)

### Computer use

- Anthropic's computer-use reference guidance includes operating computer use "in a dedicated Virtual Machine or container with minimal privileges to prevent direct system attacks or accidents", and limiting network access. [uncertain — surfaced via indexed AWS Bedrock documentation of Anthropic's computer-use guidance, which I could not open directly] — [AWS Bedrock user guide PDF reference](https://docs.aws.amazon.com/bedrock/latest/userguide/)
- **Direct verification of Anthropic's computer-use isolation wording was not possible in this session.** Treat the exact quote as unverified. [uncertain]

### Anthropic's published prompt-injection guidance

- Anthropic's docs include a page titled **"Mitigate jailbreaks and prompt injections"** under Strengthen guardrails. [uncertain — index entry only; page body unreachable] — [Mitigate jailbreaks and prompt injections](https://platform.claude.com/docs/en/test-and-evaluate/strengthen-guardrails/mitigate-jailbreaks)
- The strongest primary statement I verified from Anthropic is the engineering-blog framing that sandboxing exists specifically so that "even a successful prompt injection is fully isolated, and cannot impact overall user security." [verified] — [Anthropic Engineering](https://www.anthropic.com/engineering/claude-code-sandboxing)
- Anthropic reports **84% fewer permission prompts** with sandboxing in internal usage — relevant because approval fatigue is itself a security control failure, and Anthropic's own design goal was to replace per-action prompts with a boundary. [verified] — same page

---

## 3. E2B

### Isolation primitive: Firecracker microVM, stated plainly

- "Every sandbox is a [Firecracker](https://firecracker-microvm.github.io/) microVM, **not a container**. Each one boots its own kernel and is isolated from every other sandbox and from the host by the hypervisor[.]" [verified] — [E2B docs, security and compliance FAQ](https://docs.e2b.dev/faq/security-and-compliance.md)
- Rationale given in E2B's own words: hypervisor isolation "is what makes it safe to run code an LLM wrote or a user supplied." [verified] — same page
- **gVisor is not part of E2B's stated architecture.** I found no E2B document claiming gVisor. Third-party pages sometimes describe a layered or historical Docker/Firecracker/gVisor stack; I could not verify any of that. If your design depends on E2B's exact primitive, take the current docs at face value: Firecracker. [uncertain on any historical claim]
- The architecture has consequences worth noting: E2B's own kernel is a *custom* kernel, and at least one documented difference is that it "does not ship the `xt_owner` module", so `iptables --uid-owner` fails and you must use `nftables`. That is the kind of paper cut a microVM-with-own-kernel model creates. [verified] — [E2B FAQ: iptables xt_owner](https://docs.e2b.dev/faq/iptables-xt-owner.md)
- **No boot-time or snapshot-latency numbers were verifiable from E2B's own docs in this session.** Pause costs "approximately 4 seconds per 1 GiB of RAM"; resume costs "approximately 1 second". [verified] — [E2B docs, sandbox persistence](https://docs.e2b.dev/sandbox/persistence.md)

### Network: on by default, with a real policy engine

- "**Every sandbox has outbound access to the internet by default.**" [verified] — [E2B docs, internet access](https://docs.e2b.dev/network/internet-access.md)
- Disabling is a single flag: `allowInternetAccess: false` / `allow_internet_access=False`, documented as equivalent to `network.denyOut: ['0.0.0.0/0']`. [verified] — same page
- Fine-grained control: `allowOut` / `denyOut` accept IPs, CIDR blocks, and domain names; domain entries support wildcards like `*.mydomain.com`. **When domains are used, all other traffic must be denied in `denyOut`, or creation returns HTTP 400.** [verified] — same page
- Critical mechanical detail: **domain filtering only works on HTTP port 80 (Host header inspection) and TLS port 443 (SNI inspection).** Traffic on other ports falls back to CIDR filtering, and UDP protocols like QUIC/HTTP3 are not supported for domain filtering. When any domain is allowed, the default nameserver `8.8.8.8` is automatically allowed so DNS works — i.e. **DNS is an unavoidable egress channel for a domain-filtered sandbox.** [verified] — same page
- Allow rules take precedence over deny rules. [verified] — same page
- E2B documents a genuinely surprising behavior: **blocked TCP connections may appear successful from inside the sandbox.** The firewall must accept the connection before deciding, so a socket can report open with no packets reaching the destination; verify reachability at the application layer instead. [verified] — same page
- E2B's own warning about allowlisting a shared CDN/object-storage hostname: "allowing one hostname can also permit access to other destinations on the same endpoint." [verified] — same page
- Secrets are **not** injected into the sandbox: they are stored with E2B and substituted by the egress proxy outside the sandbox on matching HTTPS requests. Per-host transform rules are keyed by exact DNS name or a single leading wildcard label, exact keys win over wildcards, most-specific wildcard wins, and only one rule applies per request. Limits: 10 domains per sandbox, 1 transform rule per domain, 20 headers per rule. [verified] — same page; [E2B secrets docs](https://docs.e2b.dev/secrets.md)
- Network policy **can be replaced on a running sandbox** via `updateNetwork` / `update_network`, which *replaces* rather than merges rules; established connections no longer permitted are terminated. [verified] — [internet access page](https://docs.e2b.dev/network/internet-access.md)
- E2B does not publish fixed egress CIDRs: "Sandbox outbound traffic leaves from ephemeral, rotating IPs." [verified] — [E2B FAQ: egress IP ranges](https://docs.e2b.dev/faq/egress-ip-ranges.md)

### Persistence: pause/resume with memory, retained indefinitely

- Pause saves **filesystem and memory**, so running processes, loaded variables and data survive. Resume restores that state. [verified] — [E2B docs, persistence](https://docs.e2b.dev/sandbox/persistence.md)
- **A paused sandbox is kept indefinitely. There is no TTL and no automatic deletion,** and E2B "never kills a paused sandbox on its own"; it is removed only by explicit `kill`. There is currently no configurable "auto-kill after N days". [verified] — same page; [FAQ: sandbox lifetime](https://docs.e2b.dev/faq/sandbox-lifetime.md)
- `lifecycle.onTimeout` defaults to `"kill"`; set it to `"pause"` for auto-pause on timeout. Filesystem-only snapshots are available via `keepMemory: false`, which cold-boots on resume. Auto-pause has a graceful-degradation path: if the node keeps refusing a memory pause for ~2 minutes past the timeout, E2B pauses **filesystem-only** so the disk survives and memory is lost. [verified] — same page
- Paused sandboxes are **not billed** and do **not** count against the concurrency limit. [verified] — [E2B FAQ: paused sandboxes and concurrency](https://docs.e2b.dev/faq/paused-sandboxes-concurrency.md); [billing](https://docs.e2b.dev/billing.md)

### Resource limits

| Limit | Hobby | Pro | Enterprise |
|---|---|---|---|
| Max vCPUs | 8 | 8+ | Custom |
| Max memory | 8 GiB | 8+ GiB | Custom |
| Disk | 10 GiB | 20+ GiB | Custom |
| Max continuous runtime | 1 hour | 24 hours | Custom |
| Concurrent sandboxes | 20 | 100–1,100 | 1,100+ |
| Sandbox creation rate | 1/sec | 5/sec | Custom |
| Paused retention | Unlimited | Unlimited | Unlimited |

[verified] — [E2B docs, billing & limits](https://docs.e2b.dev/billing.md)

- Default resources are 2 vCPU / 512 MiB RAM; CPU and RAM are set at template build time via `cpuCount` / `memoryMB`. **GPUs are not available at all** — CPU-only, "no GPU option in the SDKs, CLI, API, or a template definition", and BYOC does not change this. [verified] — [billing](https://docs.e2b.dev/billing.md); [FAQ: GPU support](https://docs.e2b.dev/faq/gpu-support.md)
- Continuous runtime "is the time a sandbox can run **without being paused**, not a cap on how long a sandbox can exist"; pause/resume resets it. [verified] — [billing](https://docs.e2b.dev/billing.md)
- Exceeding an API rate limit returns HTTP `429` with a `Retry-After` header; SDKs ≥2.49.1 auto-retry. [verified] — same page
- **A concrete resource-limit interaction worth stealing:** building a template that installs large packages fails because `pip` caches wheels in **RAM-backed `/tmp` capped at ~3.9 GB** ("No space left on device", or `MemoryError`). Fix is `TMPDIR` to a disk-backed path plus `--no-cache-dir`. This is the classic "RAM-backed temp dir silently caps your workload" failure. [verified] — [E2B FAQ: pip install error](https://docs.e2b.dev/faq/pip-install-error.md)

### Long-running processes and output

- Background commands: pass `background: true`; the command keeps running in the sandbox even after the SDK disconnects, and a **different process** can later `Sandbox.connect(sandboxId)` + `commands.connect(pid)` to wait for it. `timeoutMs: 0` disables the SDK command timeout. [verified] — [E2B docs, background commands](https://docs.e2b.dev/commands/background.md)
- Crucially: "The streamed `stdout`/`stderr` from a background command is only delivered to the process that started it." E2B's own guidance is to redirect output to a file for durable retrieval. [verified] — same page
- Streaming: `onStdout` / `onStderr` callbacks; **the SDK sends a keepalive ping every 50 seconds on every stream**, so a stream normally stays open over an hour, but no single connection is guaranteed that long and clients should reconnect. [verified] — [E2B docs, streaming](https://docs.e2b.dev/commands/streaming.md)
- E2B explicitly warns against shell-interpolating untrusted input: pass prompts via `envs` and reference `"$PROMPT"`, because string interpolation would let `$(...)` or backticks execute inside the sandbox. [verified] — [background commands](https://docs.e2b.dev/commands/background.md)
- **Output size caps are not documented.** [uncertain]

### Languages

- Out of the box: **Python, JavaScript/TypeScript, R, Java, Bash**. Any other runtime is possible via a custom template. [verified] — [E2B docs, supported languages](https://docs.e2b.dev/code-interpreting/supported-languages.md)

### Compliance posture

- E2B has a **SOC 2 Type II** report plus a DPA template, penetration test report and bridge letter, served from `trust.e2b.dev`. Managed sandboxes run on **Google Cloud**; storage uses GCP default encryption at rest and E2B "adds no encryption layer and holds no key material of its own". Their ToS prohibit probing or testing the service without prior written consent. [verified] — [E2B security and compliance FAQ](https://docs.e2b.dev/faq/security-and-compliance.md)

---

## 4. Modal, Fly.io, Cloudflare

### 4.1 Modal — gVisor by default, full VM on request

- Modal Sandboxes run on **one of two runtimes**: **gVisor** ("a container runtime developed by Google that implements the Linux system call interface in userspace… giving stronger isolation than normal container runtimes") and **VMs** ("its own virtual machine with its own Linux kernel, isolated by the CPU's hardware virtualization through the Linux KVM hypervisor"). You switch with the `runtime` parameter — `runtime="vm"` or `runtime="gvisor"` — or leave it unset and Modal chooses. [verified] — [Modal docs, networking and security](https://modal.com/docs/guide/sandbox-networking)
- The VM runtime exists for workloads needing a **full Linux environment**: running Docker inside the sandbox, mounting FUSE filesystems, nested cgroups, nested virtualization (Team/Enterprise). **GPU Sandboxes are only supported with `runtime="gvisor"`.** [verified] — [Modal docs, sandboxes](https://modal.com/docs/guide/sandboxes); [sandbox resources](https://modal.com/docs/guide/sandbox-resources)
- This is an unusually explicit statement of the gVisor-vs-VM tradeoff as a *product* decision: default to the cheaper userspace kernel, escalate to a real kernel only when compatibility demands it.
- Default posture: "a default Sandbox has no ability to accept incoming network connections or access your Modal resources." Sandboxes are **not** authorized to reach other Modal workspace resources the way Modal Functions are by default, so "the blast radius of any malicious code will be limited to the Sandbox itself." [verified] — [Modal docs, sandbox networking](https://modal.com/docs/guide/sandbox-networking)
- **Outbound is open by default**: "By default, Sandboxes can make outbound connections to any public IP address." Three restriction levels: [verified] — same page

| Level | Parameter | Controls |
|---|---|---|
| Full block | `block_network=True` | Drops all outbound traffic |
| IP-range allowlist | `outbound_cidr_allowlist` | Only listed CIDR ranges, any protocol |
| Domain allowlist (Beta) | `outbound_domain_allowlist` | Only TLS (port 443) to listed domains |

- Domain filtering mechanics: matched against the **TLS SNI**, and Modal resolves the hostname itself "rather than trusting the destination IP the Sandbox picked". TLS is **not decrypted** — Host header, URL path and body are never inspected. Non-TLS traffic (HTTP, raw TCP, UDP) to non-allowlisted IPs is **blocked** whenever a domain allowlist is set. Entries prefixed `*.` match the parent domain and any subdomain. **ECH is not supported**, so an ECH connection is matched against the outer public name. [verified] — same page
- Modal states the domain-fronting caveat outright: "A Sandbox can reach a non-allowlisted domain there by sending an allowlisted SNI with the other name in the `Host` header… the allowlist itself does not prevent the mismatch." Their recommended mitigation is routing through a Sidecar proxy. [verified] — same page
- **Runtime network-policy mutation** exists in Alpha: start permissive while installing dependencies, then narrow to only what a tool needs. Applying a new policy is immediate and **established connections no longer permitted are terminated**. Limitations: each allowlist type must be set at creation time to be mutable later, and `block_network=True` is incompatible. [verified] — same page
- Persistence: three snapshot kinds with different retention — **Filesystem Snapshot 30 days, Directory Snapshot 30 days, Memory Snapshot 7 days**, all defaulting to expiry (a breaking change in v1.5/v0.8.0 which made filesystem snapshots expire where they previously persisted indefinitely). Snapshots can opt out of expiry with an explicit TTL. [verified] — [Modal docs, snapshots](https://modal.com/docs/guide/sandbox-snapshots)
- Memory snapshots are Alpha, capture memory **and** filesystem, and carry a list of real limitations: open TCP connections are closed at snapshot time; **snapshotting a Sandbox currently terminates it**; background processes launched via `Sandbox.exec` "will not be properly restored after a snapshot"; a snapshot can only be restored on the exact same instance type; snapshot chains inherit the original expiry. [verified] — same page
- Persisting state across sessions requires **explicit application logic**: snapshot from outside the sandbox, store the `object_id` keyed by session ID, and restore via that ID later. Modal shows exactly this pattern. [verified] — same page
- Timeouts: **default maximum lifetime 5 minutes**, configurable up to **24 hours**. Beyond 24 hours Modal recommends Filesystem Snapshots. An `idle_timeout` can auto-terminate; a sandbox counts as active if a command is running, stdin is being written, or a tunnel has an open TCP connection. Readiness probes run for a maximum of 5 minutes and raise `modal.exception.TimeoutError` without terminating the sandbox. [verified] — [Modal docs, sandboxes](https://modal.com/docs/guide/sandboxes)
- Return codes are Unix-style; `sandbox.terminate()` sends SIGKILL, code 137, and OOM is surfaced as an exit reason. [verified] — same page
- Resources: `cpu` in **physical cores (1 core = 2 vCPU)**, `memory` in MiB; billing is `max(request, actual)` because sandboxes **burst** above requests when host capacity allows. Upper limits cap bursting — Modal calls this out as specifically valuable "when an AI agent controls what runs inside the Sandbox, as it prevents misbehaving or adversarial workloads from consuming unbounded resources" (`cpu=(0.5, 4.0)`, `memory=(512, 2048)`). Disk size **cannot be increased**; on the VM runtime the root filesystem is a fixed 512 GiB. [verified] — [Modal docs, sandbox resources](https://modal.com/docs/guide/sandbox-resources)

### 4.2 Fly.io — Firecracker microVMs ("Fly Machines")

- Fly's platform primitive is the **Fly Machine**: a Firecracker-based microVM with its own kernel, startable/stoppable via API and `flyctl`. [verified for the existence and shape of Machines] — [Fly.io docs, Fly Machines](https://docs.fly.io/machines/overview/)
- **Fly's own architecture page and a Fly learn article titled "Firecracker vs gVisor" both failed to load in this session** (fetch errors / redirects). I therefore cannot quote Fly's own stated rationale for Firecracker, nor their published cold-start figures. [uncertain]
- Fly documents a **Sprites** product (`docs.fly.io/sprites`, `Sprites API`) surfaced in their docs navigation, distinct from Machines. Its isolation model was not accessible. [uncertain]
- **Fly's default network and filesystem policy, resource ceilings, and persistence semantics for agent workloads were not verified.** Volume semantics (single-writer, region-pinned) are widely reported but I did not confirm them from Fly's docs here. Do not rely on this section for design decisions. [uncertain]

### 4.3 Cloudflare — V8 isolates by default, Containers when you need a real filesystem

- The Workers runtime uses the **V8 engine** and runs **isolates**: "lightweight contexts that provide your code with variables it can access and a safe environment to be executed within. You could even consider an isolate a sandbox for your function to run in." Each isolate's memory is "completely isolated", and isolates start roughly "a hundred times faster than a Node process on a container or virtual machine" while consuming "an order of magnitude less memory" at startup. [verified] — [Cloudflare docs, How Workers works](https://developers.cloudflare.com/workers/reference/how-workers-works/index.md)
- **Isolates are explicitly evictable, including for security reasons:** "An isolate may be spun down and evicted for a number of reasons: Resource limitations on the machine. **A suspicious script — anything seen as trying to break out of the isolate sandbox.** Individual resource limits." Cloudflare therefore advises against storing mutable state in global scope. [verified] — same page
- This is a design pattern worth noting: **eviction as a mitigation**, not just a resource policy. A runtime that treats "looks like an escape attempt" as a reason to kill the isolate gets a free containment action that containers generally don't take.
- Limits (Workers, Free / Paid): [verified] — [Cloudflare docs, Limits](https://developers.cloudflare.com/workers/platform/limits/index.md)

| Limit | Free | Paid |
|---|---|---|
| CPU time per HTTP request | 10 ms | 5 min (default 30 s) |
| Memory per isolate | 128 MB | 128 MB |
| Subrequests per invocation | 50 | 10,000 (up to 10M) |
| Simultaneous outgoing connections awaiting headers | 6 | 6 |
| Worker size (uncompressed) | 64 MiB | 64 MiB |
| Worker startup time | 1 s | 1 s |
| Log data per request | 256 KB | 256 KB |

- **Duration vs CPU time is the key distinction.** CPU time measures time the CPU spends executing your code; waiting on network requests does not count. Wall-clock duration for **HTTP-triggered** Workers has **no hard limit** as long as the client stays connected; Cron Triggers, Queue consumers and Durable Object alarm handlers are capped at **15 minutes**; `ctx.waitUntil()` extends execution up to 30 seconds after the response or disconnect. [verified] — same page
- Exceeding CPU or memory returns **Error 1102, "Worker exceeded resource limits"**, with invocation outcomes `exceededCpu` / `exceededMemory`. On memory overflow the runtime "lets in-flight requests complete and creates a new isolate for subsequent requests", and under extreme load "may cancel some incoming requests to maintain stability". [verified] — same page
- **Memory is not configurable for isolates** — the only runtime limit you can raise in `wrangler` config is `cpu_ms` (max 300,000 ms = 5 minutes) and `subrequests`. [verified] — [Cloudflare docs, Wrangler configuration](https://developers.cloudflare.com/workers/wrangler/configuration/index.md)
- Containers exist for what isolates cannot do: "Resource-intensive applications that require CPU cores running in parallel, large amounts of memory or disk space; applications and libraries that require a full filesystem, specific runtime, or Linux-like environment; existing applications and tools distributed as container images." A Container is controlled from a **Durable Object** via `this.ctx.container` and started/health-checked from Worker code. [verified] — [Cloudflare docs, Containers](https://developers.cloudflare.com/containers/index.md)
- Container instance types run from `lite` (1/16 vCPU, 256 MiB, 2 GB disk) to `standard-4` (4 vCPU, 12 GiB, 20 GB). Custom instance types: min 1 vCPU, max 4 vCPU, max 12 GiB memory, max 20 GB disk, **minimum 3 GiB memory per vCPU**. Account ceilings: 6 TiB concurrent memory, 1,500 concurrent vCPU, 30 TB concurrent disk. Container snapshots max 20 GB with **30-day retention, refreshed on restore**. [verified] — [Cloudflare docs, Containers limits](https://developers.cloudflare.com/containers/platform/limits/index.md)
- The natural reading of Cloudflare's architecture for an agent: **V8 isolate = the cheap default for JS/WASM-only code with a hard 128 MB ceiling; Container = the escape hatch when you need a real filesystem and arbitrary languages.** That two-tier structure (cheap-and-limited default, heavy-and-general opt-in) recurs across the systems in this report. [uncertain — my synthesis]

---

## 5. gVisor and Google's Agent Sandbox

### What gVisor actually intercepts

- **Sentry**: gVisor's userspace kernel. "the application's direct interactions with the host System API are **intercepted by the Sentry, which implements the System API instead**." The second principle: "the System API accessible to the Sentry itself is minimized to a safer, restricted set" — to limit exploitation of a buggy or already-exploited Sentry. [verified] — [gVisor Security Model](https://gvisor.dev/docs/architecture_guide/security/)
- **No syscall is passed straight through.** "No system call is passed through directly to the host. Every supported call has an independent implementation in the Sentry, that is unlikely to suffer from identical vulnerabilities that may appear in the host. This has the consequence that **all kernel features used by applications require an implementation within the Sentry**." [verified] — same page
- **Gofer**: the companion process that manages container filesystems and hands file descriptors to the sandbox. The sandbox runs in an **empty mount namespace**, and can optionally be tuned with `directfs` to deny all filesystem access, in which case the Gofer performs all operations on the sandbox's behalf. [verified] — same page; [gVisor Resource Model](https://gvisor.dev/docs/architecture_guide/resources/)
- The sandbox's own host interactions are limited to three things: (1) a connected socket to the Gofer, (2) "a minimal set of host system calls… duplication and closing of file descriptors, synchronization, timers and signal management" — **not** socket creation or file opening unless host networking or `directfs` is enabled, and (3) reading/writing packets to a virtual ethernet device, unnecessary if host networking is enabled or networking is disabled. [verified] — Security Model
- Platforms: **systrap** uses seccomp's `SECCOMP_RET_TRAP` so the kernel sends `SIGSYS` to hand control to gVisor; **KVM** has the Sentry act as both guest OS and VMM; **ptrace** uses `PTRACE_SYSEMU`. **`systrap` replaced `ptrace` as the default platform in mid-2023**, and ptrace "is no longer supported and is expected to eventually be removed entirely." [verified] — [gVisor Platform Guide](https://gvisor.dev/docs/architecture_guide/platforms/)
- Answering "is this just a ptrace sandbox?" gVisor says no: in a ptrace sandbox "vulnerable system calls may be authorized by the sandbox, as the application still has direct access to some System API", and TOCTOU races are unavoidable without disabling multi-threading. In gVisor, "the stubs that are traced are **never allowed to continue execution into the host kernel**" — all syscalls are interpreted by the Sentry, which reflects register state back before continuing in userspace. [verified] — Security Model

### The threat model gVisor explicitly does *not* cover

This section is the most transferable part of gVisor's documentation:

- "An application in a gVisor sandbox is permitted to do most things a standard container can do: for example, applications can read and write files mapped within the container, **make network connections**, etc." [verified] — Security Model
- "gVisor relies on the host resource mechanisms (**cgroups**) for defense against resource exhaustion and denial of service attacks. **Network policy controls should be applied at the container level** to ensure appropriate network policy enforcement." [verified] — Security Model
- "In general, gVisor does not provide protection against **hardware side channels**." [verified] — Security Model FAQ
- "**A sandbox is not a substitute for a secure architecture.**" — stated in the context that an attacker "need not escalate privileges within a container if there's an exploitable network-accessible service on the host or some other API path." [verified] — Security Model
- And on the VM comparison, deliberately non-committal: "one should not assume that the mere use of virtualization hardware makes a system more or less secure[.]" [verified] — Security Model FAQ

**Translated to an agent sandbox:** gVisor is a *host-compromise* control, not a *capability* control. It does not stop exfiltration, does not limit resources, and does not scope filesystem access. Every one of those must come from somewhere else. If your threat model is "the model writes code that reads the user's data and phones home," gVisor is close to irrelevant and your network policy is doing all the work.

### What gVisor costs

**Structural costs vs implementation costs** — gVisor draws this line itself:

- **Structural** (won't be optimized away): extra memory for the Sentry; syscalls "must traverse additional layers of software"; the language choice for the Sentry trades raw performance for security. [verified] — [gVisor Performance Guide](https://gvisor.dev/docs/architecture_guide/performance/)
- **Implementation** (improving over time): subsystems "not as optimized as more mature implementations", explicitly naming the network stack, which "does not support all the advanced recovery mechanisms offered by other stacks and is **less CPU efficient**", and an internal VFS that "needs improvement". [verified] — same page
- **No cost for raw CPU or memory access**: "gVisor does not perform emulation or otherwise interfere with the raw execution of CPU instructions"; "gVisor does not introduce any additional costs with respect to raw memory accesses." A TensorFlow training workload shows minimal overhead. **Data processing and ML workloads are therefore nearly free under gVisor.** [verified] — same page
- **The cost lands on syscall-bound workloads.** Redis-style workloads "perform relatively little work in userspace": "small operations impose a large overhead, while larger operations… have a smaller relative overhead." The same pattern hits template-rendering node/ruby services. [verified] — same page
- Filesystem: "a small fixed overhead for data that transitions across the sandbox boundary" because operations route through the Gofer. [verified] — same page
- Start-up: the benchmark is dominated by Docker itself, and gVisor notes you can avoid that with `runsc do` mode or by invoking the OCI runtime directly. [verified] — same page
- Platforms differ sharply: the ptrace platform "suffers from the highest structural costs by far" and is used in the guide only to illustrate the performance model; "users should use Systrap for best performance in most cases." KVM "runs poorly with nested virtualization." [verified] — same page

**Compatibility gaps that would break an agent sandbox** (all verified, [gVisor Applications/compatibility](https://gvisor.dev/docs/user_guide/compatibility/)):

- "While in-sandbox cgroups (CPU, memory) exist and can be used for resource **accounting**, resource **limits** are not enforced within the sandbox… gVisor cannot currently enforce resource limits between competing processes within the *same* sandbox." **This is a significant limitation for agent code: an agent that forks a process bomb inside its own sandbox is not bounded by in-sandbox cgroups — only by the host cgroup wrapping the whole sandbox.**
- Block device filesystems (`fat32`, `ext3`, `ext4`) are not natively supported; you cannot mount block devices from inside the sandbox.
- `iptables` only partially supported (goal: enough for Docker-in-gVisor).
- `io_uring` is **disabled by default**; when enabled it is limited to basic I/O. Same for `nftables` rule support.
- Custom hardware device files are unsupported except NVIDIA GPU and TPU.
- **KVM from within the sandbox is not supported** (independent of whether gVisor itself uses the KVM platform).
- gVisor frames the compatibility story as better than it looks: "looking through the list of supported syscalls is not necessarily a good measure of how widely compatible gVisor is in practice", because most runtimes probe for a working I/O syscall variant. Releases are regression-tested against Python, Java, Node.js, PHP and Go runtimes. [verified] — same page

### gVisor's security track record and CVE policy

- Published aggregate: **96% of high-impact Linux kernel CVEs tracked from GKE security bulletins are "Defended"**, 3% "Patched", 1% "Mitigatable", 0% "Vulnerable", across 2016–2026 (~34 in 2025). [verified for the published figures] — [gVisor Security Record](https://gvisor.dev/security-track-record/)
- Notable entries in that record: CVE-2016-5195 (Dirty COW) Defended; CVE-2017-5123 (`waitid`, sandbox escape) Defended; **CVE-2021-30465 (runc symlink-exchange race) marked "Patched" not "Defended" with the mitigation gap stated plainly: "`runsc` (gVisor) also handles mount paths similar to `runc` to prepare the container's rootfs"**, and "VM Runtime Prevented: No"; CVE-2022-29900 (Retbleed) "External mitigation required". [verified] — same page
- gVisor's **two-layer** framing is important: "gVisor is a **two-layer sandbox**… gVisor Sentry, then Linux security measures including seccomp/`pivot_root`/namespaces/etc. As such, escaping the sandbox usually requires chaining multiple exploits[.]" A Sentry exploit alone "is a notable bug, but by itself insufficient to escape out to the host by design." [verified] — [gVisor Security and Vulnerability Reporting](https://gvisor.dev/security/)
- gVisor's published CVE policy is unusually crisp and directly answers "what counts as an escape": a CVE requires that the issue "generally **cross the sandbox boundary**", occurs where "the **attacker does not initially control the sandbox configuration**", and is "**gVisor-specific**". [verified] — same page
- Their severity taxonomy is a ready-made vocabulary for an agent sandbox threat model: `Escape` (arbitrary code on host), `HostLeak` (read arbitrary host files), `Exfil` ("sending data outside of the sandbox in ways that the sandbox configuration was meant to protect against" — **explicitly including "outbound network connections when sandbox networking is disabled"**), `Lateral` (sandbox-to-sandbox), `HostDoS`, `PeerDoS`; then the confined tier: `InternalEsc`, `InternalRead`, `SelfDoS`, `Integrity`. [verified] — same page
- Attacker-prerequisite levels, least to most privileged: `Remote`, `SandboxUser`, `SandboxRoot`, `SandboxImage`, `SandboxSpec`, `RuntimeFlags`, `HostRoot`. The CVE table makes a point every agent-sandbox designer should internalize: **if the attacker controls the configuration (`SandboxSpec` or above), nothing is a vulnerability.** [verified] — same page
- Concrete worked examples from that policy that map onto agent deployments: exposing `/var/run/docker.sock` inside a sandbox and escaping via it is classified `SandboxConf / Escape` with **no CVE**, because "secure deployments of gVisor do not expose [it]"; and a `SandboxUser / InternalEsc` (unprivileged → in-sandbox root) gets no CVE because "the attack is still confined to the sandbox." [verified] — same page

### Google's Agent Sandbox (kubernetes-sigs)

- It is an orchestrator, not an isolation mechanism, and says so: "**Agent Sandbox is a _sandbox orchestrator_. It delegates low-level container isolation to secure 'Sandbox Runtimes' (like gVisor or Kata Containers) by managing Pods configured to use these runtimes (via `RuntimeClass`).**" [verified] — [Agent Sandbox overview](https://agent-sandbox.sigs.k8s.io/docs/getting_started/overview/)
- Core `Sandbox` CRD gives a single stateful pod with stable identity (stable hostname), persistent storage surviving restarts, and lifecycle management including creation, scheduled deletion, **pausing and resuming**. Extensions add `SandboxTemplate`, `SandboxClaim`, and `SandboxWarmPool` ("pre-warmed Sandboxes that can be quickly allocated… reducing the time it takes to get a new Sandbox up and running"). A pod-per-session model with a warm pool is the shape it encodes. [verified] — same page
- Stated use cases include, verbatim: "**AI Agent Runtimes:** Isolated environments for executing untrusted, LLM-generated code" and "**Reinforcement Learning (RL) & Evaluation:** High-throughput, isolated execution sandboxes for training and evaluation loops (e.g., SWE-bench, R2E-Gym, Ray/RLlib) with low-latency warm-pool claims." [verified] — same page
- Installable manifests and SDKs (`kubectl apply` of `sandbox-with-extensions.yaml`, Go SDK `sigs.k8s.io/agent-sandbox/clients/go/sandbox`, Python `pip install k8s-agent-sandbox`), Apache-2.0, under SIG Apps. [verified] — same page
- Desired characteristics are explicitly vendor-neutral: "Supporting different runtimes like gVisor or Kata Containers to provide enhanced security and isolation between the sandbox and the host, **including both kernel and network isolation. This is crucial for running untrusted code or multi-tenant scenarios.**" Other goals: deep hibernation, automatic resume on network connection, and even "memory sharing across sandboxes" (with the caveat that "users should select a runtime that aligns with their security and performance requirements"). [verified] — same page

### Agent Sandbox's threat model — the most directly reusable artifact I found

[verified throughout] — [agent-sandbox threat model](https://github.com/kubernetes-sigs/agent-sandbox/blob/main/docs/security/threat_model.md)

Five trust boundaries: user→K8s API; system control plane→workloads (controller and router are trusted, **pods are untrusted**); cross-tenant; **workload→host/node**; **workload→control plane**.

| Threat | Mitigating invariant | Mitigation |
|---|---|---|
| Container escape to host node | Workloads must run in strongly isolated environments | **Recommendation:** use gVisor or Kata via `RuntimeClass`. "**Agent Sandbox itself does not implement isolation but supports configuring these runtimes.**" Enforce via `SandboxTemplate` with a preconfigured `runtimeClassName` |
| Cross-tenant network attack | Traffic between sandboxes must be blocked by default | Managed NetworkPolicy mode (**default**): ingress restricted to the Sandbox Router, **egress restricted to the public Internet — blocking internal RFC1918 networks and cloud metadata endpoints** |
| K8s API abuse via service-account token | Sandbox pods must not have API access by default | `automountServiceAccountToken` defaults to `false` when omitted; `true` is an explicit opt-in security exception |
| Resource-exhaustion DoS | Workloads must be resource-constrained | `resources.limits`/`requests` in the PodTemplate; admins should enforce LimitRanges and defaults in the `SandboxTemplate` |

- The default egress policy is a specific and copyable idea: **allow the public internet, deny private ranges and the cloud metadata endpoint (`169.254.169.254`)**. That is a different posture from "allow nothing" and from "allow everything" — it keeps the agent useful while removing the credential-theft path.
- Documented residual weaknesses, stated by the project itself: the Sandbox Router's **"default authorizer is `AllowAll`** (for Python client compatibility)", so the IP-class check is "currently the *only* SSRF defense", and operators are *recommended* to configure a custom authorizer. The Go router "does not currently implement connection-level limits or timeouts for upgraded connections (like WebSockets); these are roadmap items" — so Router DoS mitigation must be pushed to the Ingress/Gateway/Envoy tier. [verified] — same doc
- A concrete, non-obvious multi-tenant vulnerability class documented here: **reserved-key injection for traffic hijacking.** A tenant supplying `spec.podTemplate.metadata.labels` could set `agents.x-k8s.io/sandbox-name-hash` to another sandbox's hash so its pod matches the victim's Service selector — "a network-isolation bypass / traffic-hijack primitive." Mitigation: the controller treats every key under `agents.x-k8s.io/` and `extensions.agents.x-k8s.io/` as system-reserved, filters them out of the user template on both the create and adoption paths, and assigns the selector label **after** merging user labels so it cannot be overridden. [verified] — same doc
- **The general lesson:** if your sandbox metadata is user- or model-influenced, reserved namespace filtering must happen on every write path including reconciliation of pre-existing objects, not just at creation.

### Vertex AI / Gemini code execution

- **Not verified in this session.** Google's Vertex AI code-execution documentation (available libraries, network policy, timeouts, file persistence) was not retrieved. Do not treat this report as covering it. [uncertain]

### Google's published agent-security guidance

Summarized from Simon Willison's detailed reading of Google's paper (I did not read the paper directly): [uncertain — secondary source] — [Simon Willison, "An Introduction to Google's Approach to AI Agent Security"](https://simonwillison.net/2025/Jun/15/ai-agent-security/), reviewing [research.google](https://research.google/pubs/an-introduction-to-googles-approach-for-secure-ai-agents/)

- Two key risks: **rogue actions** ("unintended, harmful, or policy-violating actions") and **sensitive data disclosure**. "A fundamental tension exists: increased agent autonomy and power, which drive utility, correlate directly with increased risk."
- Three principles: (1) agents must have **well-defined human controllers**, with explicit human confirmation for critical or irreversible actions; (2) **agent powers must have limitations**, dynamically confined at runtime to what the task needs; (3) **agent actions and planning must be observable and auditable**, with action metadata characterizing read-only vs state-changing vs sensitive-data-handling.
- Google's own acknowledgement of the core impossibility: "**current LLM architectures do not provide rigorous separation between constituent parts of a prompt** (in particular, system and user instructions versus external, untrustworthy inputs)". Also flags memory as a persistence vector: malicious data stored as a "fact" "could influence the agent's behavior in future, unrelated interactions."
- Defence-in-depth is described as two layers: **Layer 1, traditional deterministic runtime policy enforcement** — a policy engine intercepts tool calls and evaluates risk (irreversible? involves money? did the agent recently process untrusted data?) and can allow, block, or require user confirmation; **Layer 2, reasoning-based defenses** (guard models, adversarial training) which Google concedes "are non-deterministic and cannot provide absolute guarantees… They must work in concert with deterministic controls."
- Willison's critique is worth carrying: asking for confirmation on everything produces "prompt fatigue" where users click yes; a risk-tiered policy engine avoids that. He is also explicitly skeptical of layer 2.

---

## 6. Deno's permission model

Deno is the closest existing precedent to what an in-process, capability-scoped sandbox for an Android agent would look like: deny-by-default, resource-scoped grants, interactive escalation, runtime revocation, and an inspectable audit trail.

### The model

- "Deno is secure by default. Unless you specifically enable it, a program run with Deno has **no access to sensitive APIs**, such as file system access, network connectivity, or environment access." [verified] — [Deno docs, Security and permissions](https://docs.deno.com/runtime/fundamentals/security/)
- "**No access to I/O by default**: Code executing in a Deno runtime has no access to read or write arbitrary files on the file system, to make network requests or open network listeners, to access environment variables, or to spawn subprocesses." [verified] — same page
- Refusal raises a **`NotCapable`** error (catchable as `Deno.errors.NotCapable`), not a crash. At the CLI with no flag and a TTY, Deno *pauses and prompts* instead. [verified] — same page
- `--deny-*` flags override `--allow-*` counterparts, so you can grant broadly and carve out: `--allow-read --deny-read=/etc`. `-A` / `--allow-all` "disables the security sandbox entirely" and "has the same security properties as running a script in Node.js (ie none)". [verified] — [Deno docs, Permissions reference](https://docs.deno.com/runtime/reference/permissions/); [Security](https://docs.deno.com/runtime/fundamentals/security/)

### Flag reference

| Flag (alias) | Scope argument | Example | Notes |
|---|---|---|---|
| `--allow-read` / `-R` | comma-separated paths | `--allow-read=foo.txt,bar.txt`; `--allow-read=node_modules` (dir + subdirs) | Literal comma in a path must be doubled |
| `--allow-write` / `-W` | comma-separated paths | `--allow-write=./ --deny-write=./secrets` | |
| `--allow-net` / `-N` | `host`, `host:port`, IP, `[IPv6]`, `*.sub.domain` | `--allow-net="*.example.com"`; `--allow-net=[2606:4700:4700::1111]` | **Subdomains are not implied** by a bare hostname; `*` wildcard required |
| `--allow-env` / `-E` | variable names, suffix wildcards (v2.1+) | `--allow-env="AWS_*"` | `--ignore-env` returns `undefined` instead of erroring |
| `--allow-sys` / `-S` | interface names | `--allow-sys="systemMemoryInfo,osRelease"` | Accepted names include `hostname`, `osRelease`, `osUptime`, `loadavg`, `networkInterfaces`, `systemMemoryInfo`, `uid`, `gid`, `username`, `cpus`, `homedir`. Also gates `node:os` / `node:process` equivalents |
| `--allow-run` | executable names | `--allow-run="curl,whoami"` | **Bypasses the sandbox** — see below |
| `--allow-ffi` | paths to shared libraries | `--allow-ffi=./libfoo.so` | **Bypasses the sandbox** — see below |
| `--allow-import` | hosts | `--allow-import=example.com` | Dynamic `import()` / `new Worker()` from `https:`; HTTPS only |
| `--deny-{read,write,net,env,run,sys,ffi,import}` | same shapes | `--deny-net=github.com,jsr.io` | Take precedence over allow |
| `--no-prompt` | — | | Suppresses interactive prompts (also implied when stdout/stderr is not a TTY) |
| `-A` / `--allow-all` | — | | Disables the sandbox |

[verified] — [Deno Permissions reference](https://docs.deno.com/runtime/reference/permissions/)

### Runtime grants — the part most relevant to an agent

- **Interactive grant at runtime is a first-class feature.** "When stdout is a terminal and you have not passed a flag, **Deno pauses and asks instead of failing**, so you can grant access interactively as it is requested," with a prompt offering `y` / `n` / `A` (allow all for that permission). [verified] — [Security](https://docs.deno.com/runtime/fundamentals/security/)
- Prompts are suppressed when stdout/stderr is not a TTY, or under `--no-prompt`. **This is the critical constraint for a chat agent: the sandbox's escalation channel must be an explicit part of your UI, because the runtime's default is "no prompt, hard failure".** [verified] — [Permissions reference](https://docs.deno.com/runtime/reference/permissions/)
- Programmatic API — `Deno.permissions` (modeled on the web Permissions API, with server-side extensions): [verified] — [Deno API: Permissions](https://docs.deno.com/api/deno/permissions/)

| Member | Purpose |
|---|---|
| `Deno.permissions.query(desc)` / `querySync` | Report current status without prompting |
| `Deno.permissions.request(desc)` / `requestSync` | Prompt for a permission on demand |
| `Deno.permissions.revoke(desc)` / `revokeSync` | **Downgrade a granted permission back to the prompt state** |
| `Deno.PermissionStatus.state` | `PermissionState` |
| `Deno.PermissionStatus.partial` | `true` when only partially granted (e.g. read of `/foo` granted but `/foo/bar` denied) |
| `Deno.PermissionStatus.onchange` | `EventTarget` — subscribe to state changes |

- Descriptor shapes confirm the scoping model: `ReadPermissionDescriptor`/`WritePermissionDescriptor` take `path` ("and if the path is a directory any sub paths"); `NetPermissionDescriptor` takes `host` of the form `"<hostname>[:<port>]"`; `EnvPermissionDescriptor` takes `variable`; `RunPermissionDescriptor` takes `command`; `SysPermissionDescriptor` takes `kind`; `FfiPermissionDescriptor` takes `path`. [verified] — same page
- **Least-authority is an idiom Deno actively recommends and demonstrates**: "Read a config file at startup, then give up read access" — `await Deno.permissions.revoke({ name: "read" })` — "so a later read now has to prompt again, and fails outright under `--no-prompt`. Dropping permissions you no longer need after startup is a simple way to shrink the attack surface available to the rest of your code, including your dependencies." [verified] — [Security](https://docs.deno.com/runtime/fundamentals/security/)
- **Worker scoping**: a Web Worker can be given a reduced permission set via `Deno.PermissionOptionsObject` with per-permission values `"inherit"` / `true` / `false` / an explicit array. Deno's own untrusted-code guidance recommends isolating untrusted code in a worker with a reduced permission set "so it cannot inherit everything the main program was granted." [verified] — [Security](https://docs.deno.com/runtime/fundamentals/security/); [Permissions API](https://docs.deno.com/api/deno/permissions/)
- **Permission broker** — the mechanism with the most direct relevance to a hosted agent. Setting `DENO_PERMISSION_BROKER_PATH` delegates *every* permission check to an external process over a Unix domain socket (Windows: named pipe). When active: all `--allow-*`/`--deny-*` flags are ignored, interactive prompts are not shown, and every check is sent to the broker. **"If anything goes wrong during brokering… Deno immediately terminates the process to preserve integrity and prevent permission escalation."** Message shapes are versioned JSON Schemas (`permission-broker-request.v1.json`, `permission-broker-response.v1.json`); requests carry `v`, `pid`, a monotonic `id`, `datetime`, `permission`, and optional `value`; responses echo `id` and return `allow` or `deny` with an optional human-readable `reason`. [verified] — [Security](https://docs.deno.com/runtime/fundamentals/security/)
- **This is the architectural template for a policy-driven agent sandbox**: the runtime asks an external authority for every capability use, the authority has full context (which session, which user, what has already happened), and broker failure is fail-closed by termination rather than fail-open.
- **Auditability** exists at two levels. `DENO_AUDIT_PERMISSIONS=<path>` writes JSONL records (`v`, `datetime`, `permission`, `value`) for **all** accesses "regardless of whether the access was allowed or denied", with schema `permission-audit.v1.json`; `DENO_TRACE_PERMISSIONS=1` adds a `stack` array of frames; `DENO_AUDIT_PERMISSIONS=otel` emits OpenTelemetry log records with `deno.permission.type`, `deno.permission.value`, `deno.permission.stack`. [verified] — [Permissions reference](https://docs.deno.com/runtime/reference/permissions/)

### What the permission model does *not* protect against — Deno says so directly

- **`--allow-run` breaks the sandbox.** "A subprocess runs as a separate program with its own permissions, not the restricted set you granted the Deno process, so whatever it does happens outside the sandbox." Specifically: "**This is why `--allow-run=deno` is especially dangerous**: a script that can start a new `deno` process can start it with `--allow-all`, inheriting none of the parent's limits and **escaping the sandbox entirely**." [verified] — [Security](https://docs.deno.com/runtime/fundamentals/security/) and [Permissions reference](https://docs.deno.com/runtime/reference/permissions/)
- **`--allow-ffi` breaks the sandbox.** "Deno enforces permissions in the JavaScript layer, but a native library runs as compiled machine code in the same process and can issue system calls directly. Once loaded, it can read files, open sockets, or do anything the operating system lets the process do, **regardless of which `--allow-*` flags you passed**." [verified] — [Security](https://docs.deno.com/runtime/fundamentals/security/)
- **A write + run combination is an escalation composition.** "Write access to a directory that contains an allowed executable (or to the executable itself) lets a program overwrite that binary, so the next subprocess it spawns runs attacker-controlled code with the host's privileges." [verified] — same page
- Deno's blunt summary: "**Treat both as equivalent to `--allow-all`** when deciding whether to trust the code you are running." [verified] — same page
- **No intra-thread privilege separation.** "All code executing on the same thread shares the same privilege level. **It is not possible for different modules to have different privilege levels within the same thread.**" [verified] — same page
- **Code evaluation is unbounded at a given privilege level.** "Deno sets no limits on the execution of code at the same privilege level," so `eval`, `new Function`, dynamic import and workers can execute arbitrary code with the caller's privileges. [verified] — same page
- **The static module graph is exempt from the permission system.** All modules in the initial static import graph (local files, npm, jsr, remote URLs) "are loaded by the runtime **without consulting the permission system**. No `--allow-read` is required to load local files, and no `--allow-net` is required to fetch remote modules." The exemption applies only to *loading*; `import(someVariable)` is checked at runtime. Seven registry hosts (`deno.land`, `jsr.io`, `esm.sh`, `raw.esm.sh`, `cdn.jsdelivr.net`, `raw.githubusercontent.com`, `gist.githubusercontent.com`) are importable by default, and any `npm:` specifier is allowed — justified as "trusted 'public good' registries that are not expected to enable data exfiltration through URL paths." [verified] — [Security](https://docs.deno.com/runtime/fundamentals/security/); [Permissions reference](https://docs.deno.com/runtime/reference/permissions/)
- **Filesystem-adjacent APIs consume disk without needing read/write permission**: `localStorage`, Deno KV, `caches`, `Blob`. Also, Deno uses the filesystem as a module cache, so storage can be consumed with no read/write grants. **Permission models leak resources they didn't model.** [verified] — [Permissions reference](https://docs.deno.com/runtime/reference/permissions/)
- **Symlink semantics are a deliberate compromise**: permissions are checked "based on the symlink's location, not the target it points to", so `--allow-read=/app` lets you read through `/app/link` even if it points outside `/app`. Escalation is then blocked by special-casing sensitive targets: symlinks resolving into `/proc`, `/dev`, `/sys` on Linux **require `--allow-all`**; `/proc/**/environ` requires `--allow-env`; `/dev/null`, `/dev/zero`, `/dev/random`, `/dev/urandom` are always accessible. Creating a symlink requires **unscoped** `--allow-read` and `--allow-write`. [verified] — same page
- **Node.js's copy of this idea disclaims the security property outright.** Node's permission model is "a 'seat belt' approach, which prevents trusted code from unintentionally changing files or using resources that access has not explicitly been granted to. **It does not provide security guarantees in the presence of malicious code. Malicious code can bypass the permission model and execute arbitrary code without the restrictions imposed by the permission model.**" Node's Security Policy states Node "trusts any code it is asked to run." [verified] — [Node.js Permissions docs](https://nodejs.org/api/permissions.html)
- Node's model also has a documented **cross-process escalation**: `process._debugProcess(pid)` "is not gated by the `kInspector` scope or any other Permission Model scope", so a fully-restricted sandboxed process can force *another* Node.js process on the same host under the same OS user to open its V8 Inspector. Node's response: "restricting it is the responsibility of the operator (for example, using OS-level process isolation, separate OS users per process, or seccomp/AppArmor profiles)." [verified] — same page
- Node coverage for comparison: `--permission` restricts file system access via `fs`, network, child processes, `worker_threads`, native addons, WASI, FFI and the inspector. `--allow-fs-read`/`--allow-fs-write` accept `*`, relative and absolute paths, with wildcard support that **truncates at the first `*`** (`/home/*.js` behaves like `/home/*`). Node has `permission.has()` and an **irreversible** `permission.drop()`; drop only affects future checks and "does not close or revoke access to resources that are already open, such as file descriptors, network sockets, child processes, or worker threads." Node adds an **audit mode** (`--permission-audit`) that publishes violations to `node:diagnostics_channel` channels (`node:permission-model:fs`, `:net`, `:child`, `:worker`, `:inspector`, `:wasi`, `:addon`, `:ffi`) without denying. [verified] — same page
- Node documents a sharper caveat than Deno on symlinks: "**Symbolic links will be followed even to locations outside of the set of paths that access has been granted to. Relative symbolic links may allow access to arbitrary files and directories.**" It also notes that fd-based `fs` operations (`fsync`, `fchmod`, `fchown`) cannot be tied to a path grant and are disabled under the model, and that `node:sqlite` is not covered. [verified] — same page

### Deno's own guidance for running untrusted code

Deno explicitly warns and then gives a defence-in-depth recipe: "While Deno provides security features… **untrusted code is still scary**. When executing untrusted code, it is important to have **more than one layer of defense.**" The recommended stack, verbatim: [verified] — [Security](https://docs.deno.com/runtime/fundamentals/security/)

1. Run `deno` with limited permissions, determine upfront what code needs to run, and prevent more code being loaded with `--frozen` and `--cached-only`.
2. Isolate the untrusted part in a **Web Worker with a reduced permission set**, so it cannot inherit everything the main program was granted.
3. Use OS-provided sandboxing: **`chroot`, `cgroups`, `seccomp`**.
4. **"Use a sandboxed environment like a VM or MicroVM (gVisor, Firecracker, etc.)."**

**That is the single most transferable paragraph in this report for your use case: the language runtime's permission model is layer 2 of 4, not the sandbox.**

---

## 7. WebAssembly + WASI

### Capability model

- "**WASI applications run in a capability-based sandbox: a Wasm module or component starts with no ambient authority and can only do what the host explicitly grants.**" [verified] — [WASI.dev introduction](https://wasi.dev/)
- "Code running under WASI has **no ambient authority**… This contrasts with the ambient-authority model used by operating systems like Linux or Windows, where a process inherits its user's broad permissions." WASI "is deny-by-default (or more accurately: **no capability handle by default**)." [verified] — [WASI.dev Security](https://wasi.dev/security)
- Three milestones to date: **WASI 0.1, 0.2, 0.3** (a.k.a. Preview 1/2/3). 0.3 adds native async to the Component Model and refactors interfaces onto `stream<T>` / `future<T>`. [verified] — [WASI.dev introduction](https://wasi.dev/)

**How capabilities are granted differs by version — this is the key mechanism detail:**

- **WASI 0.1 modules** import `wasi_snapshot_preview1` functions and "operate only on the resources the runtime hands them at startup, **chiefly preopened file descriptors**. A module can call `fd_read` but only against the descriptors it has been given, and **there is no way to ask for a new one**. Capability granting happens at runtime invocation, not in the module binary." [verified] — [WASI.dev Security](https://wasi.dev/security)
- **WASI 0.2 components** express capabilities **directly in WIT** — "Each capability (filesystem access, networking, environment, clocks, randomness) is a separate import, and the host instantiates the component with only the imports it has granted. The component's **interface contract is the capability surface, and it is statically inspectable in the component binary itself.**" [verified] — same page
- **WASI 0.3**: capabilities that 0.2 required guests to thread explicitly through each call (such as the `wasi:sockets/network` handle passed into `bind`, `connect`, and DNS lookups) "are implicit at the world level in 0.3. **The host still grants network access at the world boundary**; guests no longer plumb the handle through every operation." [verified] — same page
- The claimed strength: "A component without a filesystem import cannot read files; a component without a network import cannot open sockets; a module without preopened file descriptors cannot reach the filesystem at all. **These guarantees hold regardless of what the host process itself is permitted to do**, which is what makes WASI sandboxing stronger than typical OS-level process isolation." [verified] — same page

### What a "preopen" is

- A **preopen is a directory (or file) handle the host grants to a WASI 0.1 module at instantiation time**, becoming the module's entire filesystem world. The module cannot enumerate or open anything it was not preopened, and cannot obtain a new capability at runtime — "there is no way to ask for a new one." [verified] — same page (this is the definition the page implies and states functionally)
- The important architectural consequence: **the preopen set must be decided by the host before the module runs.** There is no in-module `open("/etc/passwd")` to intercept and deny, because there is no path-based namespace outside the preopens. Compare Deno, where `/etc/passwd` exists and a check fires. Preopens make the *namespace itself* the policy.

### Known limitations

- **Path resolution lives in the guest, not the host, in WASI 0.1's model.** The WASI issue tracker contains an open design discussion titled "wasi:filesystem alternative — force guests to do all path resolution" ([WebAssembly/WASI#933](https://github.com/WebAssembly/WASI/issues/933)). I could not read the issue body, so I will not characterize the current consensus, but the existence and title of the discussion indicate that guest-side path resolution is recognized as a design tension. [uncertain]
- **I could not retrieve a WASI threat-model document.** A `docs/ThreatModel.md` path in the WASI repo returned 404. **Do not assume WASI publishes a threat model comparable to gVisor's.** [uncertain / not found]
- **Runtime-level vulnerabilities are real and are the actual boundary.** The security guarantee rests on the Wasm runtime (Wasmtime, WAMR, WasmEdge, wazero, Wasmer, wasmi, etc.), each with its own CVE history. I did not verify specific runtime advisories in this session, so no CVE list is given here. Treat "WASI is capability-safe" and "this particular runtime is bug-free" as separate claims. [uncertain]
- **Practical limitation for an agent sandbox:** the agent must produce a Wasm module, not arbitrary Python or JS. Either you accept a narrow guest language set, or you pay a warm-up and ABI cost per language runtime. WASI's capability model is excellent; its *ergonomics for LLM-authored code* are the weak point. [uncertain — my synthesis]

---

## 8. Pyodide — "an interpreter with no ambient filesystem authority"

Pyodide is the cleanest existing demonstration that you can run a real Python interpreter with no OS authority at all.

- **Filesystem**: "Pyodide includes a file system provided by Emscripten… The default file system used in Pyodide is **MEMFS, which is a virtual in-memory file system. The data stored in MEMFS will be lost when the page is reloaded.**" Files are visible to Python as ordinary paths (`/hello.txt`) and to JS via `pyodide.FS`. [verified] — [Pyodide docs, Dealing with the file system](https://pyodide.org/en/stable/usage/file-system.html)
- **This is the headline property**: the interpreter has a complete, POSIX-looking filesystem API and *zero* ambient authority over the host filesystem. There is no path that escapes MEMFS unless the host explicitly mounts something.
- Real host filesystem access requires an **explicit mount**, and mounts are typed: `IDBFS` (persist across page reloads via IndexedDB), `NODEFS` (Node's native FS — requires Node), `PROXYFS`, `WORKERFS`. A browser-native directory requires the **File System Access API** via `pyodide.mountNativeFS(dirHandle)` after `dirHandle.requestPermission({ mode: "readwrite" })` returns `"granted"` — i.e. **a user gesture and an explicit permission grant, not a policy file.** [verified] — same page
- Mounted native FS changes are **not synchronized by default**: "Due to browser limitations, the changes in the mounted file system is not synchronized by default", and you must call `nativefs.syncfs()` or `pyodide.FS.syncfs(...)` to persist them. [verified] — same page
- **Network**: Pyodide's `requests`/`urllib3` work, but "**all network calls are done via the browser**. This means you are subject to the same limitations as any JavaScript network call… you have very little or no control over certificates, timeouts, proxies and other network related settings. You also are constrained by browser policies relating to cross-origin requests." [verified] — [Pyodide docs, Python compatibility](https://pyodide.org/en/stable/usage/wasm-constraints.html)
- **The security consequence is significant and underappreciated**: in the browser, Pyodide's egress is governed by the **same-origin policy and CORS**, not by your sandbox policy. CORS is a *response-header* control — it prevents the sandbox from *reading* a cross-origin response, but a request still goes out. **A POST request to an attacker-controlled origin is not blocked by CORS**, so Pyodide-in-a-browser is not a reliable exfiltration barrier by itself. [uncertain — my inference from the verified Pyodide statement plus standard web-platform semantics]
- **Host APIs**: Pyodide exposes a `js` module bound to `jsglobals`, defaulting to `globalThis`. Whatever is on the host's global object is reachable from Python. That is an ambient-authority surface the sandbox designer must curate explicitly. [verified] — [Pyodide JavaScript API](https://pyodide.org/en/stable/usage/api/js-api.html)
- **Standard library gaps** — all removed because "they currently wouldn't work in the WebAssembly VM": `curses`, `dbm`, `ensurepip`, `fcntl`, `grp`, `idlelib`, `lib2to3`, `msvcrt`, `pwd`, `resource`, `syslog`, `termios`, `tkinter`, `turtle.py`, `turtledemo`, `venv`, `winreg`, `winsound`. Present but **non-functional**: `multiprocessing`, `threading`, `sockets`. Cannot be imported (depend on removed `termios`): `pty`, `tty`. [verified] — [Pyodide Python compatibility](https://pyodide.org/en/stable/usage/wasm-constraints.html)
- `ssl` is "replaced with a stub implementation that does not use OpenSSL", so OpenSSL-dependent `hashlib` algorithms are unavailable. `zoneinfo` needs the `tzdata` package. [verified] — same page
- **No threads, no subprocesses, no sockets.** "Because Pyodide does not support threading or multiprocessing, packages that use threading or multiprocessing will not work without a patch to disable it." [verified] — same page
- **Streams are interceptable**: `setStdout()`, `setStderr()`, `setStdin()`, plus `stdout`/`stderr`/`stdin` config callbacks on `loadPyodide()`. **This directly answers "what do these systems do about stdout flooding" for this design: the host owns the write callback and can meter or truncate before anything reaches a buffer.** There is also `setInterruptBuffer()` for interrupting execution and `checkInterrupt()`. [verified] — [Pyodide JavaScript API](https://pyodide.org/en/stable/usage/api/js-api.html)
- **This is a genuinely attractive model for an Android agent**: a real Python interpreter, in-process, with a metered stdout, an in-memory filesystem with no host authority, no sockets, no subprocesses, and no threads. **What it cannot do** is exactly what users will ask for: install arbitrary native packages, run a dev server, shell out, or use threads. Pyodide's compatibility page is effectively a list of the features you will have to tell users "no" about. [uncertain — my synthesis]

---

## 9. Capability-based agent sandboxes and principle-of-least-authority literature

### The consensus threat-model framing

**The lethal trifecta** — the most widely used mental model: [uncertain — secondary source, verified text of the blog post] — [Simon Willison, "The lethal trifecta for AI agents"](https://simonwillison.net/2025/Jun/16/the-lethal-trifecta/)

> "The **lethal trifecta** of capabilities is: **Access to your private data** … **Exposure to untrusted content** … **The ability to externally communicate** … If your agent combines these three features, an attacker can easily trick it into accessing your private data and sending it to that attacker."

- The mechanism: "**LLMs follow instructions in content.** … The problem is that they don't just follow *our* instructions. They will happily follow *any* instructions that make it to the model, whether or not they came from their operator or from some other source. … LLMs are unable to *reliably distinguish* the importance of instructions based on where they came from. Everything eventually gets glued together into a sequence of tokens and fed to the model."
- On guardrail products: "Plenty of vendors will sell you 'guardrail' products that claim to be able to detect and prevent these attacks. I am *deeply suspicious* of these: If you look closely they'll almost always carry confident claims that they capture '95% of attacks' or similar… but in web application security 95% is very much a failing grade." [uncertain — secondary]
- **Why this matters for your design:** the trifecta is a *capability* analysis, not a *model* analysis. It says nothing about how good your model's instruction-hierarchy training is. A code-execution sandbox that holds user data, consumes untrusted content (a fetched web page, a user-pasted document), and has network egress is inside the trifecta regardless of how well-isolated its kernel is.

**The Agents Rule of Two** (Meta AI, Oct 31 2025) — generalizes the trifecta beyond exfiltration: [uncertain — secondary source; `ai.meta.com` was not reachable from this session] — [Simon Willison, "New prompt injection papers"](https://simonwillison.net/2025/Nov/2/new-prompt-injection-papers/), quoting [Meta AI blog](https://ai.meta.com/blog/practical-ai-agent-security/)

> "until robustness research allows us to reliably detect and refuse prompt injection, agents **must satisfy no more than two** of the following three properties within a session[.] **[A]** An agent can process untrustworthy inputs. **[B]** An agent can have access to sensitive systems or private data. **[C]** An agent can change state or communicate externally."

- Escalation rule: "If an agent requires all three without starting a new session (i.e., with a fresh context window), then the agent should not be permitted to operate autonomously and at a minimum requires supervision — via human-in-the-loop approval or another reliable means of validation." The remedy for "need all three" is **a new session with a fresh context window**, not a better filter.
- Meta's own clarification in response to criticism: "the framework would describe access to any sensitive system as part of the [B] circle, not only private systems or private data. The intention is that an agent that has removed [B] can write state and communicate freely, but **not with any systems that matter**… An example of an agent in this state would be one that can take actions in a **tight sandbox** or is isolated from production." [uncertain — secondary]
- Willison's own objection is worth carrying: the diagram labels "untrusted input + ability to change state" as lower risk, "but that's not right. Even without access to private systems or sensitive data that pairing can still produce harmful results."

**Design patterns with provable resistance** — the paper's guiding principle is the closest thing to a formal statement of what an agent code sandbox is *for*: [verified for the abstract; quotations via secondary source] — [arXiv:2506.08837, "Design Patterns for Securing LLM Agents against Prompt Injections"](https://arxiv.org/abs/2506.08837)

> "once an LLM agent has ingested untrusted input, it must be constrained so that it is **impossible** for that input to trigger any consequential actions — that is, actions with negative side effects on the system or its environment. At a minimum, this means that restricted agents must not be able to invoke tools that can break the integrity or confidentiality of the system. Furthermore, their outputs should not pose downstream risks — such as exfiltrating sensitive information (e.g., via embedded links) or manipulating future agent behavior[.]"

- And the honest framing of the ceiling: "As long as both agents and their defenses rely on the current class of language models, **we believe it is unlikely that general-purpose agents can provide meaningful and reliable safety guarantees**." [uncertain — secondary]
- The six patterns, all of which trade *utility* for *provable* safety (names and descriptions via secondary source): **Action-Selector** (an "LLM-modulated switch statement" — tools fire but their responses never return to the agent); **Plan-Then-Execute** (plan tool calls before any untrusted exposure, so untrusted output can corrupt *content* but not *recipient*); **LLM Map-Reduce** (sub-agents consume untrusted content and return only safe aggregates); **Dual LLM** (a privileged coordinator + a quarantined LLM returning symbolic variables); **Code-Then-Execute** (the CaMeL pattern — the privileged LLM emits code in a sandboxed DSL that enables full data-flow analysis and taint tracking); **Context-Minimization** (remove the user's prompt from context before returning results). [uncertain — secondary]
- Ten case studies are given, including **SQL Agent** ("an LLM with tools for accessing SQL databases and writing and executing Python code" — described as "a *highly* challenging environment for prompt injection", given three pages) and **Software Engineering Agent**. [uncertain — secondary]

**CaMeL — capabilities for data flow.** The Google DeepMind paper is the most direct instance of "capability-based" applied to agents: [verified for the abstract] — [arXiv:2503.18813, "Defeating Prompt Injections by Design"](https://arxiv.org/abs/2503.18813)

> "CaMeL, a robust defense that creates a protective system layer around the LLM, securing it even when underlying models are susceptible to attacks. To operate, CaMeL explicitly extracts the control and data flows from the (trusted) query; therefore, **the untrusted data retrieved by the LLM can never impact the program flow**. To further improve security, **CaMeL uses a notion of a capability to prevent the exfiltration of private data over unauthorized data flows by enforcing security policies when tools are called.**"

- Reported results: "solving **77%** of tasks with provable security (compared to **84%** with an undefended system) in AgentDojo." Code released at `github.com/google-research/camel-prompt-injection`. [verified] — same page
- **The 77% vs 84% number is the most useful quantitative datapoint in this report about the utility cost of provable containment: roughly a 7-point absolute drop in task completion, in exchange for provable security.** [verified for the numbers; the interpretation is mine]

**The empirical case against relying on detection** — "The Attacker Moves Second" (14 authors incl. representatives from OpenAI, Anthropic and Google DeepMind): [uncertain — secondary source] — [arXiv:2510.09023](https://arxiv.org/abs/2510.09023) via [Simon Willison](https://simonwillison.net/2025/Nov/2/new-prompt-injection-papers/)

> "By systematically tuning and scaling general optimization techniques — gradient descent, reinforcement learning, random search, and human-guided exploration — we bypass **12 recent defenses** (based on a diverse set of techniques) with **attack success rate above 90% for most; importantly, the majority of defenses originally reported near-zero attack success rates.**"

- Human red-teaming (500 participants, $20,000 prize fund) scored **100%**, defeating every defense tested. Notably `PIGuard` fell to 71% under automated attack versus 0% static. [uncertain — secondary]
- **The design implication is decisive**: any sandbox whose safety depends on classifying or filtering *content* is measuring itself against static attacks and will fail against adaptive ones. This is the strongest available argument for capability-based confinement over detection-based defense, and it is why Deno's permission model, WASI preopens, Claude Code's empty-by-default domain allowlist, and Modal's `block_network` all share a common shape: **enumerate what is permitted; do not try to recognize what is forbidden.**

### On whether to give an agent network access at all

Synthesizing the verified positions above — this is the one question where the systems diverge most sharply, and the divergence tracks *who the adversary is*:

- **Product code interpreters that face arbitrary users deny general egress and allow a package gateway instead** (OpenAI's container, with `NETWORK=caas_packages_only` and internal mirrors). This preserves the dominant use case — "install a library, analyze this data" — while removing the exfiltration channel entirely. It is the single most successful compromise in the survey.
- **Developer-agent sandboxes that must run real toolchains allow egress but force it through an out-of-sandbox proxy with an allowlist that starts empty**, and treat each new domain as an escalation event (Claude Code). Anthropic's justification for pairing network with filesystem isolation is the operative argument: without network isolation a compromised agent exfiltrates SSH keys; without filesystem isolation it can backdoor its way to network access.
- **Infrastructure sandboxes leave egress open and make restriction an explicit opt-in** (E2B on by default; Modal open to any public IP by default with `block_network` available). Both offer CIDR and domain allowlists, and both document that domain filtering is SNI/Host-based and therefore defeated by domain fronting. **Neither vendor claims its allowlist is a hard exfiltration barrier.**
- **Kubernetes agent sandboxes default to "public internet yes, private ranges and cloud metadata no"** (Agent Sandbox's managed NetworkPolicy). This is a middle position that keeps agents useful while removing the credential-theft path that makes cloud sandboxes dangerous.
- **Interpreters with no OS authority sidestep the question** (Pyodide's egress is the browser's, so it is CORS-shaped rather than policy-shaped; WASI components have no network import unless the host grants one).
- **The documented cost of denying egress** is consistent across sources: you break `pip`/`npm`/`cargo` installs of anything not mirrored, any tool that fetches a URL at runtime (docs, SDKs, model weights), `git clone`, and any workflow whose point is calling an external API. OpenAI's answer — an internal package proxy per ecosystem — is the mitigation that keeps the most utility.

---

## 10. Synthesis: design principles that recur

Each principle lists the systems that demonstrate it. "Demonstrates" means the system's own documentation states the mechanism.

### P1. Deny by default; grant by explicit capability

| System | Mechanism |
|---|---|
| Deno | No access to I/O by default; `--allow-*` grants; `NotCapable` on refusal [verified] |
| WASI | "no capability handle by default"; preopened fds (0.1) or WIT imports (0.2+) [verified] |
| Pyodide | MEMFS is the whole world; host dirs only via explicit `mountNativeFS` after a permission grant [verified] |
| Claude Code | Network allowlist "starts empty"; writes confined to CWD + temp [verified] |
| Cloudflare | Isolate has no filesystem; capabilities arrive only as bindings [uncertain — inferred] |

**Why it recurs:** the empirical result in §9 is that detection-based defenses fail against adaptive attacks. Enumeration survives; classification does not.

### P2. Scope every grant to a resource, not a category

Deno scopes reads/writes to **paths**, net to **host[:port]**, env to **variable names** (with suffix wildcards), run to **executable names**, sys to **named interfaces**, ffi to **library paths** [verified]. E2B scopes egress to **IP/CIDR/domain** [verified]. Modal scopes to **CIDR or SNI-matched domain** [verified]. WASI 0.2 scopes to **individual WIT imports, statically inspectable in the binary** [verified]. Claude Code scopes writes to **paths** and egress to **hostnames, with per-command allowed domains** [verified].

**Counter-example worth noting:** Node's `--allow-fs-read=/home/test*` **truncates at the first `*`** so `/home/*.js` behaves like `/home/*` [verified]. Scope syntax is easy to get subtly wrong, and the failure is silent over-permission.

### P3. Two boundaries or neither — filesystem *and* network

Anthropic states the invariant most clearly: "effective sandboxing requires **both** filesystem and network isolation. Without network isolation, a compromised agent could exfiltrate sensitive files like SSH keys; without filesystem isolation, a compromised agent could easily escape the sandbox and gain network access." [verified] — [Anthropic Engineering](https://www.anthropic.com/engineering/claude-code-sandboxing)

**This is the single most important principle for your design.** A sandbox with filesystem isolation but open network is an exfiltration channel. A sandbox with network restriction but host filesystem read access is one symlink or one config write away from regaining network access. Deno's own docs demonstrate the composition explicitly: `--allow-write` to a directory containing an `--allow-run` executable = attacker-controlled code execution [verified]. Claude Code's docs warn that an `allowWrite` path or a broad `allowedDomains` entry can "undo a restriction on the other side" [verified].

### P4. Separate the authority to *decide* from the place where code *runs*

| System | Decision point |
|---|---|
| Claude Code | proxy runs **outside** the sandbox; sandbox has no direct route out [verified] |
| Claude Code on the web | git proxy validates credential + operation, attaches the real token outside [verified] |
| E2B | egress proxy injects secrets outside the sandbox; "the token value never passes through your code or the sandbox" [verified] |
| Modal | domain filtering resolves the hostname itself "rather than trusting the destination IP the Sandbox picked"; sidecar option for HTTPS inspection [verified] |
| Deno | permission broker: every check goes to an external process; broker failure → immediate termination [verified] |
| Cloudflare | Container controlled from a Durable Object in Worker code [verified] |

**Why:** code inside the sandbox can be fully attacker-controlled. Any authority that lives inside it will eventually be exercised by the attacker. Anthropic's "Google credentials never inside the sandbox" [verified] and E2B's external secret substitution [verified] are the same idea applied to credentials.

### P5. Fail closed

- Deno's broker: "If anything goes wrong during brokering… Deno **immediately terminates the process** to preserve integrity and prevent permission escalation." [verified]
- Anthropic's `failIfUnavailable`: without it, "if the sandbox can't start because a dependency is missing or the platform is unsupported, **Claude Code runs commands without sandboxing**" — and they document `failIfUnavailable` as the fix, recommending it for managed deployments that "require sandboxing as a security gate." [verified]
- gVisor's CVE policy treats configuration control as out of scope, i.e. "if the attacker controls the config, all bets are off" — the necessary complement to failing closed is **never letting the untrusted side influence the configuration** [verified].
- Agent Sandbox: `automountServiceAccountToken` defaults to `false` when omitted, and `true` is documented as "an **opt-in security exception**" [verified].

**The general form:** a sandbox that silently degrades to no sandbox is worse than one that refuses to run, because the degradation is invisible. Anthropic ships the strict setting; you should default to it.

### P6. Ephemerality by default; persistence as an explicit, expiring decision

- OpenAI: "treat containers as ephemeral and store all data related to the use of this tool on your own systems"; 20-minute idle expiry; "all data associated with the container will be discarded from our systems and not recoverable"; memory state lost [verified via forum quote of the docs].
- E2B: `onTimeout` **defaults to `"kill"`**; pause must be opted into; paused sandboxes are retained indefinitely and are not billed [verified].
- Modal: 5-minute default lifetime; Filesystem/Directory snapshots expire in 30 days, Memory snapshots in 7 days; snapshotting requires application logic to persist the `object_id` [verified].
- Cloudflare Containers: snapshots 30-day retention, refreshed on restore [verified].
- Azure/OpenAI Assistants: 1-hour session, 30-minute idle timeout [verified].

**The tradeoff, stated by the systems themselves:** persistence is what makes an agent useful across turns (variables, installed packages, running servers — E2B preserves memory, Modal preserves whole running processes) and it is also what makes *persistence of an injection* possible. Google's paper flags exactly this: memory "can become a vector for persistent attacks. If malicious data containing a prompt injection is processed and stored in memory… it could influence the agent's behavior in future, unrelated interactions." [uncertain — secondary] **E2B's "paused indefinitely, no TTL, never killed automatically" is therefore a deliberate choice in the opposite direction from OpenAI's 20-minute expiry, and it shifts the burden of the persistence risk entirely onto the application.**

### P7. Two-tier isolation: cheap-and-limited by default, heavy-and-general on demand

- **Cloudflare**: V8 isolate (128 MB, 10 ms–5 min CPU, fast start, JS/WASM) vs Container (up to 4 vCPU/12 GiB/20 GB, any language) [verified].
- **Modal**: gVisor by default vs full VM when you need Docker, FUSE, or nested cgroups [verified].
- **E2B**: Firecracker microVM for everything, with VM memory-snapshot/pause as the heavyweight option [verified].
- **gVisor itself**: systrap (no hardware virtualization needed) vs KVM (bare metal, best performance, poor under nested virtualization) — with ptrace deprecated because it "suffers from the highest structural costs by far" [verified].
- **Deno**: process-level permissions as the cheap default, with Web Worker reduced permission sets inside it, and the docs telling you to add chroot/cgroups/seccomp/VM as the heavier layer [verified].

**Why it recurs:** strong isolation has a real cost, and most agent turns are trivial. A cheap default keeps the common case fast; an explicit opt-in to the heavy tier keeps the hard case possible. Claude Code's `/sandbox` decision tree encodes the same split (native process-level sandbox for trusted code, microVM/Docker for untrusted, cloud sandbox for parallel). [verified]

### P8. Budget resources explicitly, and make exhaustion an observable event

Concrete limits across systems:

| System | CPU | Memory | Wall clock | Disk |
|---|---|---|---|---|
| Cloudflare isolate | 10 ms free / 5 min paid (default 30 s) | **128 MB, not configurable** | HTTP: none while client connected; Cron/Queue/DO-alarm: 15 min | n/a |
| Cloudflare Container | 1/16–4 vCPU | 256 MiB–12 GiB | — | 2–20 GB |
| Modal | request + burst cap (`cpu=(0.5, 4.0)`) | request + burst cap | 5 min default, 24 h max, `idle_timeout` | root fs fixed 512 GiB on VM runtime, not growable |
| E2B | 8 vCPU max | 8 GiB max | 1 h Hobby / 24 h Pro continuous | 10 GiB Hobby / 20+ Pro |
| Azure/OpenAI CI | not documented | not documented | 1 h session, 30 min idle | not documented |

[all verified per the sources cited in each section]

- **Exhaustion is surfaced as a first-class signal everywhere it is documented:** Cloudflare returns **Error 1102 "Worker exceeded resource limits"** with invocation outcomes `exceededCpu` / `exceededMemory` [verified]; Modal returns Unix exit codes with OOM as a distinct exit reason and offers `CPU Pressure Per Sandbox` / `Exit Reasons` metrics [verified]; Claude Code `TimeoutError` on readiness-probe failure without terminating the sandbox [verified]; E2B documents `429` + `Retry-After` for API limits and a specific `ServiceBusyError` when a pause is refused [verified]; OpenAI documents `execution_time_exceeded` [uncertain].
- **Two failure modes are silent and must be designed around.** E2B's "blocked TCP connections may appear successful from inside the sandbox" means **a network denial can be invisible to the agent** [verified]. And Modal's burst behavior means a sandbox can exceed its request and be billed for it, which is why Modal recommends explicit caps specifically "when an AI agent controls what runs inside the Sandbox" [verified].
- **Output flooding has no cross-vendor standard.** Cloudflare caps log data at **256 KB per request** [verified]; Pyodide gives the host full control of stdio via `setStdout` / `setStderr` / `setStdin` callbacks [verified]; E2B streams output to the *client* with 50-second keepalives and warns that background command output "is only delivered to the process that started it" [verified]. **For an agent, the right model is the Pyodide one: the host owns the write callback, meters it, truncates it, and decides what the model gets to see — never let unbounded stdout reach the model's context.**

### P9. Emit an audit trail; treat the permission log as a product feature

- Deno: `DENO_AUDIT_PERMISSIONS` writes JSONL for **all** accesses "regardless of whether the access was allowed or denied", with optional stack traces and an OpenTelemetry export mode [verified].
- Node: `--permission-audit` publishes violations to `node:diagnostics_channel` without denying, explicitly "useful for discovering what permissions your application requires before deploying with enforce mode" [verified].
- Claude Code: sandbox violations trigger notifications; the sandbox panel shows resolved config; denied hosts are named in the command's result "so Claude sees what was blocked" [verified].
- Modal/E2B: sidecar/tunnel metrics, sandbox metrics, lifecycle events and webhooks; E2B exposes OTel telemetry export [verified for existence].
- Google's third principle: actions "must be observable and auditable", with action properties characterized (read-only vs state-changing, sensitive-data handling) because "this metadata is crucial for automated security mechanisms and human reviewers" [uncertain — secondary].

**Two design notes.** First, an audit log is what lets you tighten a policy empirically instead of guessing — Deno and Anthropic both describe exactly this loop (start permissive in audit mode, deploy in enforce mode; expect "roughly a week" for a config to stabilize). Second, telling *the model* why an action was blocked converts a mysterious failure into a recoverable one: Claude Code names the denied host rather than just failing.

### P10. Untrusted code must not be able to edit its own policy

This is a principle most systems assert implicitly; Anthropic and Agent Sandbox state it explicitly.

- Claude Code: "The sandbox denies writes to every `settings.json` and to the managed settings directory, **so a sandboxed command cannot modify its own policy**"; since v2.1.210 the deny rules resolve symlinks [verified].
- Agent Sandbox: reserved label/annotation keys (`agents.x-k8s.io/*`, `extensions.agents.x-k8s.io/*`, trace-context) are filtered from user templates on **both create and adoption paths**, and the Service selector label is assigned **after** merging user labels so it cannot be overridden [verified].
- Deno: the `sandbox.credentials.files` / `denyRead` bypasses; and the fact that `--allow-write` + `--allow-run` lets a program overwrite an allowed binary, i.e. **rewrite the thing the policy points at** [verified].
- The classic exploit family that motivates this, per the "Summer of Johann" survey: GitHub Copilot RCE (CVE-2025-53773) via editing `~/.vscode/settings.json` to set `"chat.tools.autoApprove": true`; Amp Code RCE via editing VS Code `settings.json` to enable new Bash commands and MCP servers. "**Privilege escalation — several of these exploits involved an allow-listed file write operation being used to modify the settings of the coding agent to add further, more dangerous tools to the allow-listed set.**" [uncertain — secondary] — [Simon Willison](https://simonwillison.net/2025/Aug/15/the-summer-of-johann/)

### P11. Denying network access is the highest-leverage single control, and the package mirror is the escape valve

Recurring, and the clearest actionable finding in this report:

- The dominant use case that *needs* egress is **dependency installation**. Both observations of OpenAI's sandbox show package managers routed to internal mirrors with a `caas_packages_only` policy, while general egress is unreachable [uncertain — third-party measurement].
- Where egress is allowed, it is proxied, allowlisted, and often SNI-only — and **every vendor that implements domain allowlisting documents that it is defeatable by domain fronting**: Modal states it outright [verified], Claude Code's docs warn "code running inside the sandbox can potentially use domain fronting or similar techniques to reach hosts outside the allowlist" [verified], and E2B warns that "allowing one hostname can also permit access to other destinations on the same endpoint" on shared CDNs [verified].
- If you must allow egress for real (not mirrorable) destinations, both Anthropic and Modal point to the same escalation: **terminate TLS and inspect, or route through a proxy you control.** Anthropic documents `network.tlsTerminate` / custom proxy ports and warns that setting a custom proxy makes *your* proxy responsible for all filtering [verified]; Modal offers `proxy_traffic_via_sidecar` and notes that relaying replaces rather than adds to the sandbox's controls on port 443 [verified].
- **For your Android agent specifically:** an on-device sandbox with no host network capability, plus a harness-mediated fetch tool with its own allowlist, gets you OpenAI's architecture without needing a package mirror — because your agent's egress needs are probably "read this URL the user mentioned," which is exactly the case Anthropic and OpenAI route through a host-controlled lane rather than through user code.

### P12. The model's own reasoning cannot be a security boundary

- Anthropic's framing: sandboxing exists so that "even a successful prompt injection is fully isolated" [verified] — the security property does not depend on the injection being detected.
- Google's paper acknowledges that "current LLM architectures do not provide rigorous separation between constituent parts of a prompt… making them susceptible to manipulation like prompt injection" [uncertain — secondary].
- The adaptive-attack result (§9) reports **>90% success against most of 12 published defenses**, and **100% by human red-teaming**, where most defenses had reported near-zero attack success [uncertain — secondary].
- Simon Willison's corollary for guardrails: "in web application security 95% is very much a failing grade" [uncertain — secondary].

**Design consequence:** never place a content classifier on the critical path between the agent and a consequential action. Place a capability check there instead. If you want to use a classifier, use it to *raise friction* (require confirmation, log, notify) — not to be the thing that decides whether egress is permitted.

---

## 11. Designs that failed or were abandoned

Only genuinely documented cases are listed. I found **no** publicly documented, confirmed escape or abuse incident for E2B, Modal, Fly Machines, or Cloudflare Workers isolates involving an agent sandbox. Absence of evidence is not evidence of absence; I am reporting what I could verify.

**1. gVisor's `ptrace` platform — deprecated and being removed.** It "suffers from the highest structural costs by far"; `systrap` replaced it as the default in mid-2023; it "is no longer supported and is expected to eventually be removed entirely." [verified] — [gVisor Platform Guide](https://gvisor.dev/docs/architecture_guide/platforms/)
*Lesson:* a syscall-interception mechanism that is correct but slow loses to one that is correct and fast. Performance is a security feature, because a slow sandbox gets disabled.

**2. gVisor's `runc`-style rootfs handling — a patch, not a defense.** CVE-2021-30465 (symlink-exchange race allowing container filesystem breakout via directory traversal) is recorded as **"Patched", not "Defended"**, with the mitigation gap stated explicitly: "`runsc` (gVisor) also handles mount paths similar to `runc` to prepare the container's rootfs", and "VM Runtime Prevented: No." [verified] — [gVisor Security Record](https://gvisor.dev/security-track-record/)
*Lesson:* the parts of a sandbox that run **outside** the sandbox's own isolation (setup, mount preparation, image extraction, proxying) inherit the host's threat model. gVisor's own docs generalize this: "A sandbox is not a substitute for a secure architecture." [verified]

**3. Node.js `--permission` as a security sandbox — abandoned as a security claim by its own authors.** "This feature does not protect against malicious code. According to the Node.js Security Policy, Node.js trusts any code it is asked to run. The permission model implements a 'seat belt' approach… **It does not provide security guarantees in the presence of malicious code.** Malicious code can bypass the permission model and execute arbitrary code without the restrictions imposed by the permission model." [verified] — [Node.js Permissions](https://nodejs.org/api/permissions.html)
*Lesson:* an in-process, dynamic-language-level permission check is a **safety** mechanism (stop accidental damage), not a **security** mechanism (stop a determined adversary). Deno is more careful here — it publishes an explicit "Executing untrusted code" section prescribing additional layers — but the same underlying limitation applies, which is exactly why Deno's own guidance ends with "use a sandboxed environment like a VM or MicroVM." [verified]

**4. Node's permission model's own escape hatch — `process._debugProcess()`.** A fully-restricted Node process with no grants "can call `process._debugProcess(pid)` to force **another** Node.js process to open its V8 Inspector… The target process does not need to be running under `--permission` for this to work — any Node.js process running on the same host under the same OS user can be signaled." Node's conclusion: "this is consistent with the Node.js threat model: Node.js trusts the OS environment in which it runs… restricting it is the responsibility of the operator." [verified] — same page
*Lesson:* **same-user, same-host processes are not isolated from each other by language-level controls.** For an Android agent this is directly relevant: your sandbox's isolation from the host app's own process, and from other apps, must come from the OS (app sandbox, separate UID, `seccomp`, or a separate process with a different UID), not from the interpreter.

**5. Allowlisted file writes that reach the agent's own configuration — a repeatedly exploited class, fixed reactively.** GitHub Copilot CVE-2025-53773 (editing `~/.vscode/settings.json` to set `"chat.tools.autoApprove": true`), Amp Code (editing VS Code `settings.json` to enable new Bash commands and MCP servers). Multiple vendors, same root cause. [uncertain — secondary] — [Simon Willison](https://simonwillison.net/2025/Aug/15/the-summer-of-johann/)
*Lesson:* Anthropic's response is the right shape — deny writes to every settings file at every scope, resolve symlinks in deny rules, and scrub stale reserved metadata on **adoption** of pre-existing objects, not just on create [verified].

**6. Allowlisting a shared cloud hostname — a recurring allowlist design failure.** ChatGPT's `url_safe` allowed `*.blob.core.windows.net`, and anyone can host a logging endpoint on Azure Blob Storage; Codex Web's suggested "Common Dependencies Allowlist" included `azure.net`, and anyone can run a VPS on `*.cloudapp.azure.net`. [uncertain — secondary] — [Simon Willison](https://simonwillison.net/2025/Aug/15/the-summer-of-johann/)
*Lesson:* **an allowlist entry is only as trustworthy as the weakest tenant of that domain.** Every system that implements domain allowlisting documents this (Modal's domain-fronting warning; E2B's "allow one hostname, permit other destinations on the same endpoint"). The mitigation is to allow specific *tenants* (`my-app.pages.dev`), never provider apexes.

**7. Detection-based prompt-injection defenses — measured and defeated.** Twelve recent defenses bypassed at >90% success by adaptive attacks, with most having reported near-zero static attack success; human red-teaming reached 100%. [uncertain — secondary] — [arXiv:2510.09023](https://arxiv.org/abs/2510.09023) via [Simon Willison](https://simonwillison.net/2025/Nov/2/new-prompt-injection-papers/)
*Lesson:* static-attack evaluations of agent defenses are close to worthless. If you build one, evaluate it adaptively.

**8. Sandbox degradation to unsandboxed execution — a documented default, now considered a hazard.** Claude Code's default: if the sandbox can't start, commands run **without sandboxing**; the fix is `sandbox.failIfUnavailable`, recommended for managed deployments using sandboxing as a security gate. Similarly, `dangerouslyDisableSandbox` lets the agent request an unsandboxed retry, and a matching allow rule can auto-approve it. [verified] — [Claude Code sandboxing docs](https://code.claude.com/docs/en/sandboxing.md)
*Lesson:* the escape hatch is where sandboxes actually leak. Anthropic documents the escape hatch, its risks, and the setting to close it — and the "excluded commands" mechanism has a documented wart where an exclusion match **unsandboxes the entire compound command**, including commands that run before the excluded one [verified]. Design your escape hatch as an explicit, logged, user-approved path from day one, or it will become the default path.

**Not abandoned, but worth flagging: Cloudflare's isolate eviction on suspected escape attempts.** "A suspicious script — anything seen as trying to break out of the isolate sandbox" is a documented cause of isolate eviction [verified]. This is a mitigation-by-response rather than a prevention mechanism, and it is unusual enough to be worth noting as a pattern.

---

## 12. Notes specifically for an Android-hosted agent sandbox

Not a separate research section — these are the points where the evidence above bears directly on the design, with the reasoning marked.

1. **You will not run gVisor or Firecracker on Android.** No KVM access for an unprivileged app, no Firecracker. gVisor's *architecture* is still the most instructive document in this survey, but its mechanism is unavailable. The realistic primitives on Android are the app sandbox (separate UID), process isolation, the Android/iOS-style permission model, and an in-process capability layer if you ship a language runtime. [uncertain — my inference from platform constraints, not researched in this session]

2. **Deno's model is the closest available template, and its own docs tell you it is insufficient alone.** Copy: deny-by-default, resource-scoped grants, `NotCapable`-style typed refusals, runtime `request`/`revoke`, worker-scoped reduced permission sets, an external permission broker with fail-closed termination, and JSONL/OTel permission auditing. Then layer network denial and OS-level isolation on top, because "untrusted code is still scary" and one layer is not enough. [verified for the mechanisms; layering is Deno's own recommendation]

3. **Pyodide is the best answer to "Python with no ambient filesystem authority" if you can accept its feature gaps.** MEMFS, no threads, no subprocesses, no sockets, host-controlled stdout, explicit mount-or-nothing host filesystem access. The gaps (`threading`, `multiprocessing`, `sockets`, `ssl`, `fcntl`, `resource`, `pty`, no native packages) are the exact list of user requests you will have to decline. [verified]

4. **The network decision dominates your security posture, more than the isolation primitive.** Every system in this survey that handles untrusted code treats network policy as the primary control and the isolation primitive as the secondary one. Given the trifecta analysis, an on-device agent that holds the user's private data, ingests untrusted content (web pages, documents, pasted text), and has raw egress is inside the trifecta no matter how good the sandbox is. The highest-value design move available is harness-mediated network access with a real allowlist, plus a package-mirror-equivalent if you need dependency installation.

5. **Budget the output path explicitly.** stdout flooding is a context-window attack and a cost attack, not just an annoyance. Cloudflare's 256 KB log cap and Pyodide's host-owned write callbacks are the two mechanisms worth copying: **cap bytes, and let the host — not the sandbox — decide what the model sees.**

6. **Persistence is the decision with the longest shadow.** OpenAI expires containers at 20 minutes idle and discards the data; E2B keeps paused sandboxes forever with memory and running processes intact. The middle path suggested by the literature: persist *files* with an explicit TTL, treat *memory* as disposable, and re-derive the sandbox from a known-good base each session so that a stored injection cannot compound (Google's "memory as a vector for persistent attacks"). Persist nothing from inside the sandbox into the agent's own memory without a quarantine step.

7. **Instrument before you restrict.** Deno's audit mode and Node's `--permission-audit` exist precisely so you can discover the real permission surface before enforcing it. Anthropic's practitioners report roughly a week of real work to stabilize a sandbox config, with every added path traceable to a specific failure. Expect the same, and log denials with the identity of the code that asked so you can attribute them.

---

## Appendix: source index

**Primary — vendor/team documentation and engineering posts**
- E2B: [security & compliance](https://docs.e2b.dev/faq/security-and-compliance.md) · [internet access](https://docs.e2b.dev/network/internet-access.md) · [billing & limits](https://docs.e2b.dev/billing.md) · [persistence](https://docs.e2b.dev/sandbox/persistence.md) · [lifecycle](https://docs.e2b.dev/sandbox.md) · [background commands](https://docs.e2b.dev/commands/background.md) · [streaming](https://docs.e2b.dev/commands/streaming.md) · [languages](https://docs.e2b.dev/code-interpreting/supported-languages.md) · [sandbox lifetime FAQ](https://docs.e2b.dev/faq/sandbox-lifetime.md) · [egress CIDRs FAQ](https://docs.e2b.dev/faq/egress-ip-ranges.md) · [pip install FAQ](https://docs.e2b.dev/faq/pip-install-error.md) · [iptables FAQ](https://docs.e2b.dev/faq/iptables-xt-owner.md) · [GPU FAQ](https://docs.e2b.dev/faq/gpu-support.md) · [paused concurrency FAQ](https://docs.e2b.dev/faq/paused-sandboxes-concurrency.md) · [secrets](https://docs.e2b.dev/secrets.md)
- Modal: [sandboxes](https://modal.com/docs/guide/sandboxes) · [networking and security](https://modal.com/docs/guide/sandbox-networking) · [snapshots](https://modal.com/docs/guide/sandbox-snapshots) · [resources](https://modal.com/docs/guide/sandbox-resources)
- Anthropic: [Beyond permission prompts (engineering blog)](https://www.anthropic.com/engineering/claude-code-sandboxing) · [Configure the sandboxed Bash tool](https://code.claude.com/docs/en/sandboxing.md) · [sandbox environments](https://code.claude.com/docs/en/sandbox-environments) · [sandbox-runtime (OSS)](https://github.com/anthropic-experimental/sandbox-runtime)
- gVisor: [Security Model](https://gvisor.dev/docs/architecture_guide/security/) · [Performance Guide](https://gvisor.dev/docs/architecture_guide/performance/) · [Resource Model](https://gvisor.dev/docs/architecture_guide/resources/) · [Platform Guide](https://gvisor.dev/docs/architecture_guide/platforms/) · [Applications/compatibility](https://gvisor.dev/docs/user_guide/compatibility/) · [Security Record](https://gvisor.dev/security-track-record/) · [Security and Vulnerability Reporting](https://gvisor.dev/security/)
- Agent Sandbox: [overview](https://agent-sandbox.sigs.k8s.io/docs/getting_started/overview/) · [threat model](https://github.com/kubernetes-sigs/agent-sandbox/blob/main/docs/security/threat_model.md) · [repo](https://github.com/kubernetes-sigs/agent-sandbox)
- Deno: [Security and permissions](https://docs.deno.com/runtime/fundamentals/security/) · [Permissions reference](https://docs.deno.com/runtime/reference/permissions/) · [Permissions API](https://docs.deno.com/api/deno/permissions/)
- Node.js: [Permissions](https://nodejs.org/api/permissions.html)
- Cloudflare: [How Workers works](https://developers.cloudflare.com/workers/reference/how-workers-works/index.md) · [Limits](https://developers.cloudflare.com/workers/platform/limits/index.md) · [Wrangler configuration](https://developers.cloudflare.com/workers/wrangler/configuration/index.md) · [Containers](https://developers.cloudflare.com/containers/index.md) · [Containers limits](https://developers.cloudflare.com/containers/platform/limits/index.md)
- WASI: [introduction](https://wasi.dev/) · [Security](https://wasi.dev/security) · [WASI #933 (guest path resolution)](https://github.com/WebAssembly/WASI/issues/933)
- Pyodide: [file system](https://pyodide.org/en/stable/usage/file-system.html) · [Python compatibility](https://pyodide.org/en/stable/usage/wasm-constraints.html) · [JavaScript API](https://pyodide.org/en/stable/usage/api/js-api.html)
- OpenAI / Azure: [Azure OpenAI Code Interpreter](https://learn.microsoft.com/en-us/previous-versions/azure/foundry-classic/openai/how-to/code-interpreter) · [OpenAI Code Interpreter guide](https://developers.openai.com/api/docs/guides/tools-code-interpreter) (403 to this agent)

**Papers**
- [Design Patterns for Securing LLM Agents against Prompt Injections](https://arxiv.org/abs/2506.08837) — Beurer-Kellner et al., 2025
- [Defeating Prompt Injections by Design (CaMeL)](https://arxiv.org/abs/2503.18813) — Debenedetti et al., 2025
- [The Attacker Moves Second](https://arxiv.org/abs/2510.09023) — Nasr, Carlini et al., 2025

**Secondary analysis (clearly labelled where used)**
- Simon Willison: [lethal trifecta](https://simonwillison.net/2025/Jun/16/the-lethal-trifecta/) · [design patterns](https://simonwillison.net/2025/Jun/13/prompt-injection-design-patterns/) · [Google's approach](https://simonwillison.net/2025/Jun/15/ai-agent-security/) · [Summer of Johann](https://simonwillison.net/2025/Aug/15/the-summer-of-johann/) · [Rule of Two & adaptive attacks](https://simonwillison.net/2025/Nov/2/new-prompt-injection-papers/)
- [simonw/research: ChatGPT container environment](https://github.com/simonw/research/blob/main/chatgpt-container-environment/README.md) — hands-on measurement, AI-generated report per its own header
- OpenAI Developer Community: [keeping containers alive](https://community.openai.com/t/what-is-the-best-practice-for-keeping-containers-alive/1321557) · [container expiring](https://community.openai.com/t/container-expiring-last-active-at-60-sec-during-o3-deep-research-background-mode/1367337)
- [FlorianBruniaux/claude-code-ultimate-guide: native sandboxing](https://github.com/FlorianBruniaux/claude-code-ultimate-guide/blob/main/guide/security/sandbox-native.md) — third-party guide; its configuration-detail claims were **not** independently verified and are not relied upon here
