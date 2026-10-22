---
title: Technical Advisor administrator login
description: Bootstrap a local administrator for the standalone Technical Advisor app.
zh_link: /v2/zh/docs/others/technical-advisor-login
---

This guide applies to `deepscope-app/backend`, the standalone application, rather than the removed platform services.

The agent creation field Describe your needs for AI generation is optional and used only to assist generation. A manually entered name and work instructions are sufficient without a purpose; existing validation for those fields still applies. Free-mode routing uses the agent name and description rather than this auxiliary input.

Temporary input on a new agent page does not automatically create a sidebar draft. Explicit generation, preview, creation, or choosing Save when leaving persists it. Erasing all input does not save an empty draft or leave an unsaved-change indication. Existing drafts retain autosave and the existing save/discard choices when leaving.

The knowledge library list fits the available viewport: search and pagination remain visible, table content scrolls internally and the header stays fixed. Horizontal scrolling and server pagination are preserved.

## PDF knowledge parsing and sources

Library Settings offers a vector-model selector and Save model, without changing the system default. Empty libraries save directly. Existing documents require confirmation before a full index rebuild and then open Documents for progress. Retrieval and new uploads remain paused for the entire library until every document is ready. Failures or restarts keep retrieval paused; process failed/interrupted documents again to recover. Agents skip rebuilding libraries while other libraries remain available. Rebuilding reuses complete published passages and their page evidence where available, falling back to source files only when needed, with no reupload required. A failed database reset rolls back the previous binding and physical index, preventing mixed model spaces.

All documents share one bounded embedding pool, defaulting to 3 concurrent requests (`ADVISOR_EMBEDDING_WORKERS`, range 1–8). Requests remain single-input compatible; multimodal inputs are not assumed to support independent batches. Documents report completed/total passages. Internal checkpoints reuse only matching file/model/dimensions/content/source results and are removed in the successful publication transaction. Failed work is never searchable. Short text merges within page/title boundaries; tables and image interpretations remain separate with individual source-region mappings.

At most two PDF parsing calls run concurrently. Eligible whole-page timeout/truncation/format failures may trigger one two-region fallback after normalizing the PDF's declared rotation. Splits require a continuous blank band near the middle; coordinates map back to the original physical page. No recursive splitting or forced cuts through text/tables occur. Incomplete regions still fail the document; unsafe splits, authentication/rate-limit failures and cancellation do not blindly create more regional calls. Parsing and embedding log separate duration and reuse counts.

The personal Thinking process preference controls reasoning-text visibility only. Hiding it conceals historical and live reasoning while retaining the underlying records for later display. It does not change the model menu's Think toggle, reasoning effort or subsequent request parameters. Final answers and tool execution remain unaffected.

All documents offer View content. PDFs retain original-page and parsed-content comparison. Markdown, HTML, DOCX and TXT use a fixed-height dialog with internal scrolling, formatted/raw text views and server-paged Indexed passages. DOCX shows extracted text/tables without simulating Word layout or page numbers. TXT preserves line breaks. HTML renders only safe tags without scripts or source event/link attributes. Markdown preserves literal reference markers and shows image descriptions without fetching external images. Previewing never calls a model or republishes evidence and follows existing visibility permissions. Text over 1 MiB produces an explicit preview error; absent indexed passages are shown as unpublished content.

Content completeness is validated separately from location notes. Recognized coordinate-estimation notes do not block publishing; invalid regions fall back to physical-page references. Missing/unreadable content and unknown warnings still block the whole document. Structured table columns/rows are validated and rendered without guessing empty cells. Invalid JSON/contracts allow one fresh request from the same page; network errors do not trigger format correction. Enable `ADVISOR_PDF_JSON_OUTPUT=true` only after confirming provider support; the default is false and a model name does not establish compatibility.

Process again can internally reuse successful pages only for the same file, parser protocol and model configuration. Configuration changes invalidate checkpoints, with consistency checks before saving each page and before publication. Publication remains atomic and failures retain the previous complete version. Safe page error categories and document/attempt/page/model/request identifiers plus elapsed time aid diagnosis without logging response bodies. Logs default to `${advisor.data-dir}/logs/backend.log`, rolling at 10MB with 7 days of history and a 70MB archive cap.

Collapsed passage previews also render compact Markdown with retained paragraph/table structure, tighter spacing and a maximum height of 7rem. Click the result title to read the full passage. Query highlighting operates on parsed text without breaking table syntax or rewriting the original source.

Enter keywords, identifiers or a content description and select Retrieve to test recalled passages rather than generate a final answer. Statistics show passage and source-document counts. Results show rank, filename, source, score and up to three summary lines; click a title to expand or collapse, with only one result open at a time. Bulk expand/collapse, separate expand buttons and passage-copy actions are removed. Expanded content scrolls within a bounded height, renders Markdown tables and contains the raw-text toggle. New successful searches reset expansion. Non-PDF sources without physical pages display Document passage rather than invented references. PDF preview retains formatted/raw text, separate location controls and page highlighting. Presentation changes neither rewrite indexed passages nor call the model again, and cannot repair missing values or incorrect columns produced during parsing.

PDFs are parsed by physical page, preserving text blocks and source regions. Scanned, illustrated and complex pages use an explicitly configured vision model. Any failed required page or incomplete content fails the whole document; partial evidence is not published. Truly blank pages are valid. Reprocess older documents to obtain page references.

Enable Use for knowledge PDF parsing in the existing model configuration drawer. Saving sends a random-number test image and validates recognition before assigning the sole PDF role. Changes take effect dynamically without changing the default chat or embedding model. `ADVISOR_PDF_VISION_ENDPOINT_ID` remains a fallback when no UI role is selected; the default chat model is never selected implicitly. Optional `ADVISOR_PDF_RERANK_ENDPOINT_ID` enables reranking; otherwise keyword/vector candidates use RRF fusion. Chinese uses substrings/bigrams and identifiers are retained. Ranking scores are not similarity percentages; the threshold applies only to vector candidates. Keyword retrieval scans structured chunks within the selected library; larger datasets need separate indexing assessment.

View pages compares the original with extracted content, highlights source regions and requests pages from the server. One Process again action rebuilds the entire document; separate failed-page and failure retry actions are removed. The list shows queued, parsing, embedding and publishing stages. Preview shows the actual PDF model and disables review when unconfigured. References link to physical pages. Model transcription/interpretation remain labelled; only explicit Review page with model sends that page and question. Viewing/search permission allows review; editing controls whole-document reprocessing.

Defaults are 300 pages and a 2000-pixel longest image edge, configurable through `ADVISOR_PDF_MAX_PAGES` and `ADVISOR_PDF_MAX_IMAGE_EDGE`. There are 2 workers and 32 queue slots (`ADVISOR_KNOWLEDGE_WORKERS`, `ADVISOR_KNOWLEDGE_QUEUE_CAPACITY`), with a 20-minute job deadline including queue time (`ADVISOR_KNOWLEDGE_TIMEOUT_MINUTES`). Embedding HTTP attempts have a 25-second deadline within a 60-second budget and allow at most two transient retries. PDF vision attempts use 45 seconds within a 90-second budget and at most one transient retry. Responses are bounded to 1 MiB during reception and vision text to 256 KiB. Cancellation aborts the real HTTP request. Idle threads retire after 60 seconds and shutdown cleans up tasks. Vector table creation, vector replacement, structured chunks, source snapshots, dimensions and successful status commit in one short PostgreSQL transaction outside model calls. Failed reprocessing retains the previous complete version. Real OCR accuracy still needs representative documents and a real vision model; cross-page tables and cross-table aggregation are not guessed.

Set `ADVISOR_ADMIN_USERNAME` and `ADVISOR_ADMIN_PASSWORD` when starting the backend for the first time. Use your chosen administrator credentials; no password is embedded in source or used as a default. Existing accounts keep their passwords on subsequent starts. These bootstrap variables can be removed after the account has been created.

The application stores accounts and login sessions in the configured PostgreSQL database. Passwords are hashed with individual salts; only token digests are persisted. Preserve the database and the workspace and runtime history under `advisor.data-dir` when restarting.

Open the frontend's `/login` page. Login uses `POST /api/auth/login`; identity and the single namespace are returned by `/api/auth/me` and `/api/v1/me/scope`. Sessions expire after 12 hours, configurable through `advisor.auth.session-ttl`. Logout revokes the current token through `POST /api/auth/logout`.

Framework administration under `deepscope.admin.base-path` (default `/v1/admin`) requires the administrator's bearer token. AG-UI chat also requires authentication and checks agent visibility, conversation ownership, and its locked agent. This minimal login does not implement registration, user administration or migrate the old platform's business APIs.

## Agent management

Visit `/agent` to select an agent from the left list and view or edit its details on the right. New and unfinished agents continue through the existing creation flow. Builtin agents are visible to everyone and editable only by administrators. Personal agents are visible and editable by their creator and administrators. Copies contain workspace definitions, excluding user memory and conversation history. Builtin edits survive restarts, and renaming a personal agent preserves its conversation history.

Work instructions, tools, skills, knowledge, and subagents appear as tabs across the top, with work instructions selected initially. Only the current tab mounts its editor. Names and descriptions can be edited in place, with Save shown when changed; users without edit rights see read-only fields. Start conversation opens a session locked to that agent, while Copy and Delete are in More. File access covers `AGENTS.md`, `tools.json`, `skills/`, `subagents/`, and `knowledge/`; runtime data is excluded. Subagent files use the harness's existing Markdown and YAML declaration format. The tools, skills and knowledge tabs — and the creation page's Advanced settings — reuse the shared list components: standard headers, a checkbox column and server-side paging, with search applied on button or Enter. The selected count sits at the right of the toolbar, and the save button and its result stay below the list.

The product identity is DeepScope, and every agent's definition file really takes effect: when an agent is prepared, the app reads that agent's `AGENTS.md` as the system prompt (workspace context is off in this app, so the app reads the file itself) and appends the product identity rule **after** that text. `AGENTS.md` therefore stays adjustable at runtime, while its body can never talk the agent out of being DeepScope. When asked who it is, an agent must open with "I am DeepScope" and only then describe its duty, and it must never call itself a "technical assistant" or another product name. A system-maintained identity line also sits at the top of each `AGENTS.md` (topped up idempotently, never rewriting the body a user wrote). Model-drafted behavior documents and subagent definitions follow the same rule, and basic chat carries the same identity.

A new subagent starts blank: no prefilled name and no prefilled template. The definition supports AI completion, which stays disabled until a name is filled. A standing note beside it explains that the name and what you have typed are sent to the configured chat model only to generate this definition. When the definition already has text, a confirmation replaces the whole content; a failed call keeps the current text and asks you to check the model settings.

The composer offers an agent picker beside the model selector for both new and existing conversations. A selected agent uses its own runtime and mounted capabilities without silently switching to another agent. Explicitly choosing Free mode removes the binding; each message then uses the existing router, with tool-free basic chat when no agent is available.

The picker shrinks to the current result count and is capped at 340px and the available viewport. Each server page contains at most five agents with 12px names and 11px summaries. There are no previous/next buttons: wheel gestures fetch the next or previous server page, including fresh requests when returning to an earlier page. Touch swipes and PageUp/PageDown also page. Search is debounced by 200ms and performed on the backend after visibility filtering; the frontend does not download and filter the full catalog. Failed loads support retry, and missing selections remain visibly unavailable.

Changing selection while a response is running affects the next submitted message, not the active run. Each submission and queued item captures its agent choice; retry preserves that choice. Tool-confirmation resumes stay with their original runtime. Binding changes require the existing runtime's idle-thread reservation, including requests from other tabs; busy threads return 409. Conversation records remain intact, while history does not grant another agent's tool permissions.

The welcome composer is reduced from 104px to 72px high and uses max-w-2xl width. Long input still expands to 200px before scrolling internally. Agent names and lock icons indicate the selection without an additional restriction hint.

A subagent proposal in a locked conversation waits for visitor approval. The draft can be edited before approval. Approval writes a new definition, available for dispatch on the next turn without restarting. Only administrators can approve builtin agent proposals; ordinary visitors can reject them. Free conversations cannot propose subagents. Same-name proposals are resolved in submission order; repeated resolution returns a conflict.

Switching agents or tabs, or navigating Back/Forward, prompts users to save, discard, or keep editing when changes are unsaved. Failed saves preserve edits. Refreshing or closing uses the browser’s native unsaved-changes warning. The searchable list highlights selection in pale purple, and loading errors offer a local retry. Narrow screens show the list first, then the selected details with a clear return button.

## Sidebar

The sidebar uses a 248px expanded layout and a 64px compact rail. Desktop navigation rows are 40px high with 14px labels and 18px icons, increasing touch targets on coarse pointers. Recent sessions show one title line; timestamps and agent names appear in the hover hint, and running or waiting states remain indicated. The session's More menu opens the existing rename or delete confirmation.

The sidebar uses a fixed layout. Only recent sessions scroll within the remaining height; a slim scrollbar appears during scrolling and hides after scrolling stops. Workspace and Settings are mutually exclusive expandable groups showing permitted destinations directly, without search fields. Model settings and access management remain in Settings, and the account menu contains sign-out. Compact icon navigation, mobile navigation and the language switch remain available.

Short viewports use tighter navigation spacing. Expanded menu contents have a bounded internal scrolling fallback with hidden extra scrollbars so bottom destinations remain reachable.

Workspace is spaced below recent sessions. Expanded groups enclose both the heading and contents in one white surface with a subtle gray border and light shadow; Settings follows the same pattern. Neither expanded nor collapsed groups show a search field.

## Conversation page

Text drafts on the start page and in conversations are temporarily stored per account and conversation in the current tab. Reload restores text without sending it; images and attachments are excluded. Successful message persistence clears the matching text, while failures keep it. Late acknowledgements cannot overwrite newer text or a draft from a remounted page. Signing out clears application tab drafts.

The process sidebar's Sub-agents tab shows its count as a top-right badge, hidden when the count is zero.

The conversation uses one continuous background and a single title row, with whitespace separating turns and a light purple visitor bubble. Real thinking increments display inline while running; historical analysis expands in place. Tool calls retain concise records and detailed results. Final answers use normal message text without a delivery stage or an archive claim without a real location.

Use the title-bar icon to show or hide the process sidebar. Drag its left edge, or focus that edge and use the arrow keys, to resize it. There is no separate close button. The Timeline and Sub-agents tabs retain their existing records, navigation and read-only details. Opening or closing the sidebar preserves the composer draft.

The start page and active conversations share one composer, with the placeholder “Collaborate with DeepScope” in every state. The greeting uses Shanghai time and the current nickname, falling back to the account name. The welcome composer is wider, has a minimum input height of 104px and sits about 32px below the greeting; active conversations remain compact. Drafts do not create a separate preview card. Slash commands remain available before and during execution; clearing and compaction are unavailable while running. Code blocks have a persistent copy icon. Visitor messages reveal the copy icon and actual timestamp on one line on hover or keyboard focus. Button names remain in tooltips and accessible labels.

The conversation heading and recent-session sidebar share one title cache refreshed by the session list. Collapsing the sidebar does not stop heading synchronization.

The composer starts as a compact single line with tighter vertical padding, attachments, a model menu and a purple send button. The menu lists configured models as individual rows, marks the current model with a check, and includes a **Think** sliding toggle for the next turn. The entry displays Think when enabled. Below the toggle, a **Reasoning effort** segmented slider offers five tiers (Low, Medium, High, Extra High, Max) in a single row with a sliding highlight; tiers are selectable only while Think is on, and the entry echoes the chosen tier. The selection persists through account preferences and browser storage, defaulting to High. New conversations use the same menu. Existing model-management permissions and APIs still apply; users without those permissions can toggle Think, choose a tier and see the model name reported by run data. Model and Think changes are disabled during a run. Send and Enter are also blocked until a pending model change is confirmed. A failed model change leaves the current model selected and displays an error.

Turns, steps, duration, first-token latency, output rate and cache statistics are available in an anchored popover opened from **Usage details**, without expanding the page. Calculations still use actual samples, excluding unsampled turns from averages. The context ring appears only when both usage and the model window are known and also opens an anchored popover; the system-prompt/tools/messages split remains an estimate.

## Dynamic context capacity

The compact context control shows remaining tokens in K/M units, with used, remaining and total amounts in its popover. The capacity follows the endpoint selected for the current conversation; the YAML model is only a fallback when no saved endpoint is current. Missing capacity is displayed as pending, without pretending that the safe fallback budget is a known model limit.

The application reads the selected model's `context_window` (or compatible `context_length`) from the configured endpoint's `GET /models` response and persists it. Discovery runs on startup, successful model setup/selection, or when the current model's stored capacity is missing. Endpoint metadata takes precedence over the SDK's documented-model directory. Discovery failure preserves previously known capacity; unknown models retain the harness's conservative budget.

Each model call captures one immutable selection for input budgeting, generation and usage reporting. Changing the global selection cannot relabel an in-flight call. History compression follows token budgets with output and summary reserves; the advisor no longer compresses merely because the conversation reaches 50 messages.

Restart the backend after this update to apply the idempotent `context_window` column addition and discover the saved current endpoint. A 1M-capable endpoint can then use its advertised window; the application does not force every model to 1M or perform a paid million-token probe.

## Basic chat fallback

When a free session has no visible agents, routing uses basic chat with the currently selected model. Basic chat preserves conversation history and supports streaming, thinking, usage accounting and context budgeting, with no tools, knowledge libraries or subagents. No managed agent is created.

If a locked agent is unavailable, the visitor chooses basic chat or selects a visible replacement in the same session. Missing or unauthorized sessions still return 404. Retry reuses the saved visitor message instead of posting it again. Restart the backend after updating the application to register the basic chat transport.

## Inline thinking and tool calls

New replies show commentary, thinking and tool calls in their actual order. Once a normal run finishes and final text is confirmed, the process collapses while final text remains visible. Elapsed time uses server run start to first final-text delta, excluding answer streaming and persistence. There are no extra failure counters or summary alerts; actual statuses and errors remain available in individual details.

Normal persistence and disconnect fallback retain the order and available server timing, so refresh restores the same elapsed duration. Cancelled, failed, suspended or unconfirmed replies keep their process visible. Older records with missing timing show a generic process disclosure rather than zero seconds, without inventing paragraph boundaries. Manual expansion survives record updates; timeline navigation opens the matching round before highlighting its block.

A verified summary produced at the execution iteration limit can also collapse its process while retaining an explicit iteration-limit notice. When the model emitted no intermediate commentary, tool groups receive factual descriptions based on their recorded actions. Historical summaries with run timestamps but no final-text start timestamp show total run time, clearly distinguished from pre-answer processing time.

During execution, two or more adjacent tool calls or dispatch records collapse into one batch showing the operation count and running state. Commentary, thinking and actionable cards separate batches; a single action remains visible. Batch disclosure survives updates and outer-turn toggles; timeline navigation opens the matching batch.

Rows use readable actions and short targets instead of shell commands, absolute directories or argument JSON. Tool details use the existing drawer; analysis expands in place. Model commentary and answers use darker gray, while action and analysis labels are lighter. Actual returns appear before technical details. Dispatch receipts do not mean completion; real exit codes and task states remain factual.

The current step or batch uses a gray text shimmer and a subtle activity dot, without an underline. One primary focus per turn keeps expanded batch children static. Motion stops on terminal states or while waiting for user action and respects reduced-motion preferences. Running reasoning starts collapsed in a single row: Analyzing and a current-thought preview. New lines roll into the preview; long lines scroll horizontally with incoming text. Clicking expands the complete reasoning inside a fixed 240px-high scroll window. Bottom readers follow new content; scrolling up pauses following, and returning to the bottom resumes it. Manual disclosure choices survive updates and completion. Historical analysis starts collapsed. Closing tool details restores focus to the originating step.

The context ring is 18px with a 32px interaction target and the existing usage popover. The composer grows up to 200px before scrolling internally; wheel input at its boundaries does not scroll the conversation. Modified wheel gestures, Enter, Shift+Enter and IME behavior remain intact.

Final answers go directly into message text. Existing user workspace definitions are preserved; application runtime guidance changes only the internal reporting format. The deprecated delivery tool remains registered for pending-call recovery but emits ordinary answer text rather than a new delivery archive. Historical unique conclusions, recommendations and real source links remain; metadata-only delivery records are hidden and only complete duplicate passages are deduplicated within a turn.

Models no longer need to generate internal fact markers. Explicit references and known internal prefixes are hidden from answers and child views while code, real links, paths and model identifiers remain literal. Subtasks show names, goals, current actions and true status. Only the open active task's page is refreshed for persisted progress, stopping on completion, back navigation or unmount. Older snapshots cannot overwrite terminal states or newer content.

## Structured question cards

When additional information is needed, the question card occupies the bottom composer area at the same width, hiding the ordinary composer while the question is pending. Options use circular number badges, with Other last and numbered consecutively. A single choice submits immediately; multiple choices require confirmation. Custom and optionless answers are entered inside the card. Cards can collapse and remain outside conversation scrolling. Answer controls are disabled while the run finishes or a submission is pending; Stop remains available while execution continues.

The separate dynamic title identifies the decision concisely: preferably 6–18 Chinese characters, at most 40 characters on one line, without quotations, self-commentary or option lists. Necessary explanation and choices appear separately; long explanations are collapsed under Question details. Historical records use a bounded first-sentence heading while retaining their complete original text without rewriting stored data.

Failed submissions preserve selections and text for retry inside the card. Saving an answer removes the card and restores the ordinary composer with its existing draft and attachments; the question and answer remain in history. Refresh restores unanswered cards without reopening answered questions. When an answer was saved but no execution output was observed, Continue processing reuses the saved answer without posting another visitor message. The current server runtime is checked before continuing to avoid restarting active work. Choices are never submitted automatically or on a timeout.

The card uses the existing composer's input replacement area. Its shared queue stays visible and cancellable; immediate queue sending is disabled while a question is pending.

Agent switching is hidden while the question card is active and returns with the ordinary composer. Chinese questions use Chinese card prompts. Model-provided Other/custom options are merged into the system custom-answer entry. Selecting it focuses a text field and requires a nonblank answer plus confirmation. This also applies when Other is checked alongside multiple business options; the label alone is never submitted as a custom answer.

Older text questions and suggestions retain compatible presentation. The server associates, validates and deduplicates answers. Attachment answers retain their original attachment context across reload and continuation. Answering starts the next turn rather than blocking a tool thread while waiting for a user.

## Think switch and first output

With Think off, new runs do not display or save reasoning fragments; empty reasoning lifecycle markers are not labelled as completed thinking. The selected reasoning-effort tier travels with each run only while Think is on; with Think off the tier is ignored and the legacy low effort applies. Unknown tiers fall back to High. DeepSeek requests explicitly disable its thinking mode rather than only lowering effort or hiding the returned text. Historical replies with actual reasoning retain it.

A single visible agent is selected without a routing model call; multiple candidates continue to be selected per turn. Backend logs record the model's first visible output duration without message content, so provider waiting can be distinguished from application preparation. This timing does not guarantee a provider response speed.

## Process rows and long content

Thinking, tools and tool groups use lightweight rows with a purple activity indicator and black or gray text. Completed rows are static. Analyzing and Analysis record expand actual thinking in place; individual calls do not add duplicate failure notices.

Tools describe the actual action and short target. Search scope, commands and working directories remain in technical details. Labels distinguish running, completed and failed calls; command completion is not automatically labelled as passing tests.

Consecutive tools collapse into action batches with an operation count. Failed records remain inspectable inside the batch. Actual order and manual expansion survive updates and completion; timeline navigation opens the located record.

Expanded thinking scrolls continuously inside a fixed-height window during and after execution, retaining the complete text without paging. Arguments and tool results still display in portions of about 12,000 characters using previous/next controls. Streaming preserves the selected portion; shorter content clamps the selected page. Loading animation respects reduced-motion preferences. Elapsed-time ticking pauses while the page is hidden, and timers and listeners are released when work ends or components unmount.

## Agent Creation Flow and Sandbox Preview

New primary agents use a purpose-first, single-page creation flow (`/agent/new` and `/agent/creation/:draftId`). Unactivated configurations are saved in dedicated draft persistence, physically isolated from active agents and official workspaces.

- **Silent Auto-Save**: Input pauses for 800ms trigger auto-save with optimistic revision conflict protection. Unfinished drafts can be resumed or deleted from the drafts section on the agent listing page.
- **AI Planning Suggestions**: Asynchronously generates recommended names, descriptions, and prompt instructions based on task purpose, allowing one-click application or dismissal without overwriting user input on failure.
- **Isolated Sandbox Preview**: A slide-over preview panel enables interactive trial runs within an isolated runtime container and history table, with zero writes to official sessions or timeline storage.
- **One-Click Atomic Activation**: Clicking "Create and Start Using" flushes pending edits, verifies static requirements, and activates the preallocated agent with atomic rollback protection, navigating directly into an active conversation.

## Conversation Runs and Recovery

A conversation accepts one active run at a time. Duplicate or unauthorized requests do not stop an existing run. Stop requests target their own run, and keep admission reserved until cleanup finishes so an old initializer or cancellation cannot interrupt a newer run.

Native tool confirmations show the actual tool and input separately from business proposals. Choose allow or deny for every item before submitting; cancelling a wait does not grant permission. Reloading restores unresolved confirmations from the server, while confirmations already being resumed remain unavailable. Unverifiable confirmations cannot be approved.

Browser refresh or a lost connection does not stop the server run. The page restores runtime status and persisted progress without submitting the question again, and polls while disconnected execution remains active. Explicit Stop still interrupts the original run. The queue advances only after confirmed successful completion, persistence and reconciliation; stopping, failures, limits and pending confirmations retain queued messages. A lost stream is reconciled with server state before showing an error.

Manual compaction has no minimum message or token count; empty context remains a no-op. Automatic compaction uses an 800,000-token threshold, capped by the existing safety budget for smaller models. Applying the disconnect and compaction settings requires restarting the backend.

Native-log conversations recover actual context and checkpoint the summary through the existing framework log. An empty legacy state file no longer makes native history appear empty. Legacy state mode retains its existing store path, and compaction preserves the visible conversation records.

Reading conversation details does not finalize or flush a run. Client uploads and server fallback saves share run-based deduplication and report conflicting content. Clearing context removes native state for the agents actually used in that conversation. Active runs, pending confirmations and unsaved content prevent clearing; failures retain history and show an error.

Filesystem failures use explicit tool status; file content beginning with “Error:” is not itself a failure. Process links match stable entry identifiers, and stale running snapshots cannot overwrite a subagent's terminal state.

## Tool and MCP Lifecycle Management

HTTP and MCP tools share a test dialog with a fixed-height, internally scrollable description. Parameter templates prefer schema defaults, examples and enums while preserving number and boolean types. The top-level parameter JSON must be an object.

AI autofill uses the configured model to suggest arguments from the saved tool name, description and schema. It never executes the tool or loads its credentials. Input and Run are locked during generation. Success fills editable JSON; running remains an explicit action. Failure shows an error without replacing the previous input. Closing or changing tools prevents stale results from overwriting new input. Execution failures show an error while retaining redacted diagnostics.

The import dialog offers a tool name and a tool description, and both fall back to the values in the definition when left empty. The description is the only thing that tells the model what a tool is for, so a tool imported without one reaches the model as its bare name. A definition holding several operations keeps the names the definition carries so they stay unique, and the description becomes a shared prefix across them. Tool names accept letters, digits, underscores and hyphens; a missing name and a name with nothing usable in it are reported separately.

Connecting an MCP service pastes a configuration by default, using the same command or config block text as before; a form can be used instead, asking for the service name, the transport, and either the start command with its arguments and environment or the service URL with its headers. Once connected, every reported tool is listed with its name and description so each one can be approved on its own.

The test control endpoint uses POST, while target calls follow the tool's saved method and parameter mappings; MCP calls retain their protocol. An omitted optional confirmed field means unconfirmed, avoiding a primitive-boolean JSON parse error and its 400 response. Existing execution approval remains enforced.

Tools and MCP tools share the tool table; skills remain in their own table. Draft and unavailable resources remain stored for management. Tools, MCP servers and skills are visible only to their creator, including for administrators and capability selectors.

The slash menu derives personal resource commands from live state and refreshes from the backend when reopened. Tools require approval, a ready lifecycle, valid configuration and allow/ask permission. MCP tools also require an enabled, previously connected server with the same owner. A transient connection-test failure does not remove an existing command. Skills require publication and a nonempty official file. Denial, approval withdrawal, unpublishing or deletion makes the command unavailable. Resource type and ID distinguish commands with identical display names.

Selecting a resource command fills the composer. Sending checks current ownership, availability and the agent's existing capability bindings again, then uses the normal tool calling or skill loading flow with existing approvals. Basic chat or an agent without the selected capability asks the user to choose an appropriately configured agent. Test endpoints are not used for normal command execution.

Tool and skill entries show their full names, wrapping when necessary, without internal command codes or IDs. System actions retain slash syntax. Descriptions truncate in the list; only hovering over the description reveals its full text in a tooltip. Names, icons and other row areas do not trigger it.

Chat selection, routing and runtime admission allow only the current user's agents, preventing indirect access to another owner's private capabilities; agent management visibility remains independent. Rebuilding a runtime does not reconnect or reenable a disabled MCP server. Resource commands reject multiple tools with the same runtime name on one agent; rename them or keep only one bound to avoid calling the wrong service.

### Workspace Authorization and Recovery

Account navigation retains Profile, Security, Preferences and Conversations. My resources and its credential table are removed; the old URL redirects to Profile. Workspace resource management and tool authentication configuration remain available.

AI autofill uses a text-only button and locks input while generating. MCP discovery serializes SDK JsonSchema values as JSON instead of `JsonSchema[...]` text. Before autofill or testing, legacy malformed records read the real schema from their same-owner, connected MCP server without executing tools, modifying stored definitions or bypassing parameter validation. Read failures are explicit; the existing synchronization flow can persist the corrected schema.

Account pages use a light gray background and a single white settings panel. An internal divider joins navigation and content; fields use spacing and fine separators instead of individual cards. Identity appears only in the profile area. Clicking the profile avatar opens the image picker; removal lives in its options menu and requires confirmation. Asset counts come from existing APIs. The conversations page continues to manage chat history without inventing a login-device list.

Knowledge uploads open one shared dialog. The dashed area contains file selection, queued files, progress and results. Picking, dropping or pasting queues files before explicit upload; pasted text becomes a TXT file. Uploaded files remain inside the dashed area. Retrying sends only pending or failed files. Upload success is separate from parsing, which remains visible in the document list. Paste handling stays within the upload area rather than intercepting unrelated inputs.

Workspace operations enforce both module permissions and resource ownership. Hiding a menu does not authorize backend access. Chat-only accounts can still obtain available-agent summaries; management details, drafts and mutations require the corresponding permissions. Agent capability selectors return resources available to the target owner and grant no resource-management rights.

Draft saves acknowledge only the submitted edit version. Edits made during a save remain pending, and a failed save cannot publish an older snapshot. Each draft produces one official agent. Staging agents are unavailable for selection or execution; successful drafts disappear from the unfinished list. Failed activation-status requests retain the draft and offer a retry.

Skill names and paths stay inside managed directories, rejecting path escapes and unsafe links. ZIP extraction is bounded to 16 MiB per entry, 64 MiB overall, 512 entries and 64 skills. Directory payloads and ignored entries also count. Failed package writes roll back the database batch and clean up files created by that batch.

MCP approvals validate service, owner, tool identity and refresh revision before applying a batch. New calls from older runtimes also enforce deletion, denial and unbinding. Changing allow to ask blocks the stale call; the next turn uses the latest confirmation policy. Operations already sent to a remote service are not guaranteed to be reversible.

Knowledge libraries reopen existing vector indexes after restart without requiring uploads again. Document polling checks both visible parsing rows and the library-wide count so transiently inconsistent summaries cannot stop refresh permanently.

Interrupted activation recovery assumes a single-node startup boundary: stop accepting activations before updating, and do not run old and new nodes concurrently. Unsafe historical skill paths and ambiguous multiple activation targets retain evidence and require explicit resolution; the repair does not delete unknown user directories or duplicate agents automatically.

Manage HTTP tools and Model Context Protocol (MCP) services at `/tools`. The catalog supports automatic execution endpoint resolution, pre-save connectivity probing, in-place configuration editing, controlled single-run testing, MCP diff synchronization, and impact-aware deletion.

In the Tools and MCP lists, enter keywords and click Search or press Enter to apply filtering. Submit an empty search to restore all results. The import dialog uses concise field and action labels. The Connect MCP input includes a configuration example; replace the example URL before connecting. Lists retain connection addresses, approved tool counts and tool names, while omitting decorative name icons and the redundant HTTP transport label.

- **Distinction Between Connection Check and Test Execution**:
  - **Pre-save Connection Check**: Automatically performed by the backend during import or editing. It verifies host and TLS reachability, base URL or HEAD/OPTIONS connectivity, and performs MCP initialization/listTools handshakes to ensure the target is reachable. A successful connection check **does not guarantee valid business parameters or functional correctness**, and **never automatically issues POST/PUT/DELETE write operations with side effects**.
  - **Business Test Execution**: Explicitly triggered by the user in the test panel with concrete parameter values. It displays actual HTTP status, elapsed time, and bounded paginated response text; write-heavy operations require explicit secondary confirmation. Running a test neither modifies persisted configuration nor bypasses tool permissions (e.g. deny/ask).
- **OpenAPI and Format Support Boundaries**:
  - Supports OpenAPI 3.0 and 3.1 (JSON and YAML) and standalone JSON Schemas. Server base URLs can be auto-detected from root, path, or operation-level `servers`, allowing manual address input to remain optional. Common path/query/header/cookie parameters and Bearer, API Key, and Basic authentications are supported.
  - **Full OpenAPI specification coverage is not promised**: OpenAPI 2.0 (Swagger), external remote `$ref` fetching, cyclic recursive schemas, callbacks/webhooks as callable tools, and dynamic OAuth2 handshakes are unsupported. Unsupported constructs return explicit diagnostic errors instead of pretending to succeed.
- **MCP Synchronization and Atomic Confirmation**:
  - Clicking "Refresh" detects tool differences against the remote service, producing persistent diff snapshots of added, changed, and removed tools. Removed tools are immediately blocked from further dispatch. Users review diffs and atomically apply accepted changes without losing tool IDs, permissions, or agent selections.
- **Impact-Aware Deletion and Protection**:
  - Before deleting any tool or MCP service, the system analyzes affected agents and bundled tools. Confirmation executes in an atomic transaction that unbinds agent selections and stops subsequent runtime calls.


## Model Settings and Conversation Selection

Connection and configuration forms retain unfinished drafts in the current tab, including provider, model ID, address, display name and a temporarily entered key. Reopening the same form after reload restores it. Successful save or explicit discard clears it; previous test results are not restored. Drafts are never written to localStorage and are cleared on sign-out.

New connections start without a selected provider or address. Opening the drawer does not automatically expand either dropdown. Existing configurations keep their values. Options appear only when the user enters the provider or model ID field.

Chat and embedding models have separate lists. Defaults are marked in their rows instead of large summary cards. Columns show the name, model ID, supplier, API address, credential status, and last test result and time; embedding rows also show recorded dimensions. List search applies on explicit submission. A previous successful test is not a live availability indicator.

Connect model opens a right-side drawer with grouped model information and permanently expanded connection settings. Test, Cancel and Save stay in the footer. There are 21 supplier presets, including OpenAI, Anthropic, DashScope, DeepSeek, Zhipu GLM, Kimi, SiliconFlow, OpenRouter, MiniMax, StepFun and Groq, with selected international variants and a custom connection. Zhipu standard API, Zhipu Coding and Z.ai Coding have separate entries to avoid mixing subscription endpoints. Selection supplies the matching address. Presets are connection suggestions rather than account availability claims. Embedding shows only verified compatible presets; Anthropic chat retains its native Messages protocol.

Fetch models queries only the configured connection and reports the actual returned count, distinguishing an empty list from a failed request. The official DeepSeek endpoint cannot enumerate another supplier's GLM models. When enumeration is unsupported, built-in suggestions or manual model IDs remain available; saving still validates the actual connection.

Provider and model ID inputs open on focus and support live search, arrow keys and Enter. Model IDs also accept custom text. Search is debounced by about 200ms and matched on the server; the frontend does not filter a downloaded complete list. Typing never queries a provider endpoint. Fetch models explicitly requests the provider list, which is bounded and cached on the server for five minutes per connection and credential; subsequent searches use that cache, falling back to built-in suggestions when absent. Discovery failures preserve the draft and allow manual IDs. Existing endpoints retain their protocol and credential-preservation behavior.

Test connection checks the draft without saving or activating it. Changing connection fields clears the previous result. Saving validates the current configuration on the server and preserves the old configuration on failure. New endpoints do not become defaults unless explicitly selected. Testing sends a small real request and can incur provider charges.

Configuration keeps the endpoint identity and retains existing credentials unless Replace key is selected. Closing a dirty drawer requires confirmation. The action menu provides testing, default selection and deletion, with confirmations and error feedback. Defaults and models referenced by conversations cannot be deleted directly.

Settings controls the system default. The conversation picker persists only that conversation's choice. A new conversation binds the current system default at creation time, and later default changes do not change existing conversations. Each run pins its selected model, context window and usage identity. Existing management permissions and conversation ownership checks remain enforced.

Embedding models are decoupled from the system and bound per knowledge base. The global "current embedding model" serves as the default for newly created knowledge bases and can be freely switched at any time without blocking. Existing knowledge bases lock to their bound embedding model when indexed; subsequent reprocessing and cross-library searches use each library's bound endpoint independently without conflict. Deleting an embedding endpoint currently in use by any knowledge base is prohibited and explicitly reports the names of dependent knowledge bases. Restarting the backend applies the additive display-name, last-test and conversation-selection schema fields.


## Management List Format

Users and Roles follow the supplied Yudao references: compact inline labels in a search card, submitted Search/Clear conditions, and a create action in the table toolbar below. Users display Account; roles display Role identifier and omit the user count. Both reuse text actions: Edit, Delete and More. User menus offer role assignment, password reset and status; role menus offer Menu permissions. Dropdown panels match their trigger width. Only existing project fields and supported actions are shown.

User search supports account, nickname, status, role and creation time; role search supports name, identifier, status and creation time. Input prompts consistently use “Enter field name”; Search is purple and Clear stays white. Creation time uses a combined range input with Today, Last 7 days, Last 30 days, Yesterday, This week, This month and Last week, a calendar and hour/minute/second columns. Confirm updates only the draft; Search or Enter applies server-side filters and pagination. Clear resets every condition and page one. Times follow the browser's local timezone and include the final selected second; a date-only end includes the entire day.

User/role editing and password reset use darker labels and input text. Permission page groups start collapsed; opening or closing a group preserves checked and partial states, read-only grants and the required administration entry. Checkbox accents and the confirmation button use lighter purple.

Permission rows reserve a consistent expander column. Child menus and button permissions indent at each level, with checkboxes aligned among sibling nodes.

User creation and role assignment reuse the shared multi-select dropdown. Existing roles are shown and can be cleared; select multiple options before confirming to save, or cancel to keep the current grants.

Role assignment integrates search inside the dropdown panel, visible only while open; there is no separate search field in the dialog. Name search runs on the server with a debounce of about 200ms, preserves selected roles and distinguishes loading, no matches and retryable failures.

User and role numbers each start at 1 and are generated and persisted by the database. Searching, paging and deleting records do not renumber existing entries. Internal identifiers and relationships remain intact.

Edit user updates Account, Nickname and Note; the account must be nonempty and unique. Passwords and role assignments remain intact. After renaming, sign in with the new account and existing password; conflicts preserve the draft for correction. Role actions are ordered Edit, Delete, More. More contains only Menu permissions without an icon. The Admin role allows editing its name, identifier, order and menu grants; disabling and deletion remain blocked. Other built-in roles retain their protections.

After renaming the built-in administrator, startup bootstrap recognizes the existing built-in identity. It does not recreate the original account or restore its old bootstrap password.

Authorization loads the page/action tree and current role grants from the server, supports partial selection and preserves failed edits. Reopening loads fresh grants. Admin grants are editable while its access-administration entry remains required; startup preserves saved grants. Existing management grants retain their scope; action grants do not add cross-owner access. Role assignment needs grant permission; built-in and super-admin changes require access administration. Built-in admin accounts and roles cannot be disabled or deleted; the last super-admin cannot lose its role.

Copy configuration directly creates a persisted model copy, including its connection, key, embedding dimensions and context metadata. Its name includes a copy suffix; it does not become the default or issue an upstream probe. The list refreshes and moves to the final page when needed to show the new entry. Responses remain masked: plaintext stays on the server and is not copied to the clipboard. Changing a connection address selects Custom and requires re-entering the key. Provider search runs on the server, using the same directory for chat and embedding models. Embedding discovery and cached search retain all returned IDs, including opaque deployment IDs from providers such as Volcengine Ark, without name or capability filtering. Saving still requires a real embedding response and dimension validation; appearing in the directory does not imply embedding support. Existing model name and alias rendering remains unchanged; dimension headers and values stay on one line.

The agent list displays enabled/disabled status and provides edit, enable/disable and delete actions for official agents. Drafts retain continue-setup and delete actions without a status toggle. Deletion uses the existing confirmation dialog. Status persists independently, with existing agents enabled by default. Disabled agents remain manageable but cannot be selected, routed to or admitted for new chat runs; restarts and tool refreshes do not reenable them. Disabling does not forcibly cancel an already started run; connections retire through existing leases. Enabling rebuilds the runtime; a failed rebuild restores the previous status and reports an error.

The account area beside the bottom-left avatar prefers the nickname and falls back to the account when it is blank. Saving your own nickname updates this display immediately.

Tools/MCP, models, skills, knowledge bases, documents, users and roles share the same table components: a white bordered container, subtle header, consistent cell spacing and edge padding. Actions align right without wrapping; narrow screens scroll within the table. Pagination stays below the rows.

Row actions use compact outline buttons matching the tool list, with text or an icon and text. Delete uses a named red icon button with a title. Common actions stay visible, less frequent actions are explicitly assigned to More actions, and dangerous actions appear last. Unauthorized actions are hidden, unavailable actions disabled, and an executing row shows loading feedback and blocks repeated actions. Existing business confirmations remain in place.

Standard search applies only on button submission or Enter. Filters and create actions belong above the table. Loading, recoverable errors, no data and no search results use shared states. Headers and pagination remain available on an empty page so users can navigate back.

Developers supply business content through DataTable columns and actions. Actions describe id, label, icon, iconOnly, tone, hidden, disabled, loading, overflow and onClick. Special rows may retain renderRow but must use TableActions for their controls. Avoid page-specific table frameworks and action-button styles.

Agent capability selectors (tools, skills and knowledge, including the creation page's Advanced settings) are another use of the same components: the checkbox sits in the name column, row content stays business-owned, and loading, retry, empty results and paging remain the list's responsibility. Selectors must reuse `DataTable` and `TableToolbar` instead of hand-built search bars, list containers or paging.

Dialogs use a shared three-part structure — header, body and footer — without rule lines above and below; spacing separates the sections, and the shared footer keeps the actions at the bottom instead of every page styling its own action row.


The agent overview uses the shared DataTable with Name, Summary, Type and Actions headers, including loading and empty states. Edit and Continue setup use standard compact 32px text-only actions. Desktop navigation remains on the left; narrow screens show the overview table and search. Existing detail editing and unsaved-change guards remain in place. Edit actions in all lists use text without an icon.


The knowledge list omits its page title/subtitle and decorative row/action icons, retaining text actions. Knowledge and skill lists share TableToolbar. Typing updates only a draft; Search or Enter applies it and resets pagination. Submitting an empty query restores all results. Permissions and status filters retain their behavior. Empty results offer a clear-search/filter action.

## On-demand Management Lists

Agents, tools/MCP, models, skills, knowledge bases, documents, users and roles use server pagination, initially requesting 10 rows with a maximum page size of 100. The server applies visibility, keyword and filter conditions before returning the current page and total count. Management lists no longer load the entire catalog for browser filtering or pagination.

Search or Enter submits the keyword; repeating a search refreshes it. Changing pages, page sizes, status, permission or provider filters requests the server. Search and filter changes return to page one, while subsequent pages retain submitted conditions. Unsubmitted input does not change the current result. Search prompts use concise object names.

The agent overview merges agents and unfinished drafts in SQL before sorting and paging; draft purposes remain searchable. Knowledge details omit the complete document list, which loads separately by page. Parsing refreshes the current page or lightweight metadata and updates readiness counts on completion. Default model summaries remain independent of search results. Capability selectors page through the same routes with `purpose=selection` plus the agent or draft they belong to, scoping results to that owner and requiring only agent-management rights, 10 rows initially with 10/20/50 options. The unpaged array endpoints stay compatible but are no longer called by pages.

## Knowledge Editor

Compact Documents, Retrieval test and Settings tabs reuse the existing UI components. Documents presents search, status, upload and a paged table. Selecting files immediately uploads them for processing; drop and paste remain available. An empty list has one upload action. Decorative file icons are omitted; failed processing exposes focusable help and a direct retry action.

Retrieval uses one question field and Test or Enter. When no documents are ready, users can select files directly without switching tabs. Results show only the source, raw similarity, passage and Copy. Similarity is not answer accuracy; explanatory help works on hover and keyboard focus. Copy feedback appears only after clipboard success.

Settings starts with General, Q&A, Long documents or Custom. Advanced numeric parameters are collapsed until needed and have contextual help. Failed saves retain input; successful saves show inline feedback. Chunking changes apply to new uploads or reprocessed documents and do not automatically rebuild existing content. The saved notice explicitly identifies the need to reprocess existing documents.

## Account Self-service API

Users enter Personal settings from the user menu. Application navigation stays visible. Existing Page, Card, Button and form components provide a wide workspace with Profile, Security, Preferences, My resources and Conversations. The top bar contains only a back arrow; repeated page titles and subtitles are omitted. The right card fits the available viewport and scrolls internally, with subtle separators between settings. About is removed and its old URL returns to Profile. The page retains the 1440px maximum content container and 192–224px settings navigation; narrow screens stack fields and use a section dropdown. Every `/api/me/*` request requires authentication and operates on the caller's account; conversation deletion also requires chat.use. `/api/auth/me` returns nickname, avatar details and roleNames: the account uses the same field as user management, while roles use the management names of currently active assignments. File paths remain private.

| Action | Endpoint | Behavior |
| --- | --- | --- |
| Nickname | `PUT /api/me/profile` | Trim surrounding whitespace; allow clearing; maximum 40 characters; preserve the account and administrator remark; submit using Save or Enter |
| Password | `PUT /api/me/password` | Verify the current password; require 8–256 characters; update and revoke other sessions in one transaction while retaining the current login |
| Sign out other devices | `POST /api/me/sessions/revoke-others` | Preserve the current session and leave other accounts unaffected |
| Avatar | `POST /api/me/avatar`, `GET /api/me/avatar`, `DELETE /api/me/avatar` | Upload multipart field file; PNG/JPEG/WebP up to 10 MiB and 8192 pixels per side; replace and clean up the old file; deletion restores the default avatar; limits are not displayed as permanent helper text |
| Safe conversation deletion | `DELETE /api/me/sessions/{sessionId}` | Owner-only conditional deletion; running sessions return 409; bulk deletion uses this endpoint after confirmation |
| Preferences | `GET /api/me/preferences`, `PUT /api/me/preferences` | Think, reasoning effort tier, default model and default agent; partial updates without changing global defaults or existing conversations |
| Personal assets | `GET /api/me/overview` | Count only owned agents, skills, knowledge bases and tools; return null without module viewing permission; credentials expose configuration status only |
| Version | `GET /api/me/about` | Application name and the build-resolved backend version |

Fetch avatar bytes with Authorization; never put tokens in image URLs. Successful responses use private caching and vary by Authorization. PNG/JPEG use ImageIO validation. WebP checks RIFF, frame headers and dimensions for static and animated files; this is not full pixel decoding.

Unset preferences mean Think off, system model and free agent selection, with `initialized=false`. Import an old browser Think value using `If-None-Match: *`; an existing server preference returns 412 instead of being overwritten. Normal editing omits that condition. Non-current chat models retain the existing model-selection management scope, and selected agents must be available to the caller. Language remains browser-local.

The credential list contains no keys, ciphertext or credential references. configured indicates a credential reference or MCP environment/header configuration, not a successful remote authentication test. Existing conversation listing and individual operations remain compatible; bulk deletion uses the guarded endpoint listed above.

Permissions use the existing tree in read-only mode. Modules start collapsed with granted counts; expanding shows permission names and granted states. Delete all shows the confirmed conversation count before deletion and does not include conversations created afterwards. Running conversations require stopping first; partial failures refresh the remaining list for retry.

Frontend defaults apply only to new conversations. Think, the default model and the default agent are checked against the caller's current availability before creation, while an explicit per-conversation choice takes precedence. If model binding fails, the newly created empty session is removed. Avatar bytes are fetched with authentication into a local preview; tokens never appear in image URLs. Language continues to use the browser preference and is not stored server-side.
