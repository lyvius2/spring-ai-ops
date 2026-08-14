# Spring AI Ops

![Kotlin](https://img.shields.io/badge/Kotlin-2.2.0-7F52FF?logo=kotlin&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.4-6DB33F?logo=springboot&logoColor=white)
![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.0-6DB33F)
![OpenAI](https://img.shields.io/badge/OpenAI-supported-412991?logo=openai&logoColor=white)
![Anthropic](https://img.shields.io/badge/Anthropic-supported-191919)
![DeepSeek](https://img.shields.io/badge/DeepSeek-supported-4D6BFE)
![EXAONE](https://img.shields.io/badge/EXAONE-supported-A50034)
![Amazon Bedrock](https://img.shields.io/badge/Amazon%20Bedrock-supported-FF9900?logo=amazonaws&logoColor=white)
![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2024.0.1-6DB33F)
![Redis](https://img.shields.io/badge/Redis-enabled-DC382D?logo=redis&logoColor=white)
![OpenFeign](https://img.shields.io/badge/OpenFeign-client-2C3E50)
![Prometheus](https://img.shields.io/badge/Prometheus-optional-E6522C?logo=prometheus&logoColor=white)
![Loki](https://img.shields.io/badge/Loki-logs-F46800?logo=grafana&logoColor=white)
![Spring Security](https://img.shields.io/badge/Spring%20Security-enabled-6DB33F?logo=springsecurity&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-green)

[한국어(Korean) 문서](README_KR.md)  
  
An AI-powered operations automation tool that receives webhooks from **Grafana Alerting**, **GitHub**, and **GitLab**, then uses an LLM (OpenAI, Anthropic, DeepSeek, EXAONE via FriendliAI, or Amazon Bedrock) to analyze errors, review code, and perform static code risk analysis in real time. For Grafana alerts, the application queries Loki logs, optionally fetches Prometheus metrics over the same alert window, checks out the registered application source repository, and sends focused stack-trace-related source snippets to the LLM. Results are delivered to a live dashboard via WebSocket.  

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [Architecture](#architecture)
- [Interface Flows](#interface-flows)
  - [Static Code Risk Analysis](#static-code-risk-analysis)
  - [GitHub → LLM](#github--llm-code-review)
  - [GitLab → LLM](#gitlab--llm-code-review)
  - [GitHub PR / GitLab MR → LLM (Inline Review)](#github-pr--gitlab-mr--llm-inline-review)
  - [Grafana → Loki + Prometheus → LLM](#grafana--loki--prometheus--llm-error-analysis)
- [Screenshots](#screenshots)
- [Technology Stack](#technology-stack)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Configuration](#configuration)
  - [Sensitive Value Encryption](#sensitive-value-encryption)
  - [Running](#running)
  - [Setting Up Grafana](#setting-up-grafana)
  - [Setting Up GitHub Webhooks](#setting-up-github-webhooks)
  - [Setting Up GitLab Webhooks](#setting-up-gitlab-webhooks)
- [Admin Authentication](#admin-authentication)
- [API Reference](#api-reference)
- [API Documentation (Swagger)](#api-documentation-swagger)
- [Package Structure](#package-structure)
- [Changelog](#changelog)
- [License](#license)

---

## Overview

Spring AI Ops bridges your monitoring and version-control toolchain with large language models. It covers three distinct AI-powered workflows:

1. **Static Code Risk Analysis** — On demand, Spring AI Ops clones a registered Git repository, scans the entire source tree, and sends the bundled code to an LLM for a full security and quality review. For large codebases the analysis is split into chunks and processed in parallel (map-reduce), then consolidated into a single final report. Results include a markdown report and a structured JSON issue list (severity, file, line, recommendation).

2. **Automated Code Review** — When a GitHub or GitLab push webhook arrives, the application fetches the commit diff and sends it to the LLM for an automated code review covering correctness, security, performance, and code quality. In addition, a dedicated **Pull Request / Merge Request** endpoint reviews PR/MR events — posting a summary comment on `opened` / `reopened` / `ready_for_review` and **inline line-anchored comments** on `synchronize` (new commits pushed to the PR head) via the GitHub Reviews API or GitLab Discussions API, with automatic fallback to a summary comment when inline positioning fails.

3. **Incident Intelligence** — When Grafana fires an alert, the application builds a Loki stream selector from alert labels, queries matching logs, optionally queries Prometheus range data, and checks out the registered source repository in parallel. JVM stack traces are parsed from the logs, resolved to source files, and converted into focused snippets around the failing lines. The LLM receives alert, log, metric, and source context, then returns a root-cause report plus structured source code change suggestions.

All results are pushed to connected browsers in real time via STOMP WebSocket.

No relational database is used. Redis serves as the sole persistence layer — storing LLM configuration, application registry, alert analysis records, and code review records.

> **Live Demo**: [https://ai-ops.duckdns.org](https://ai-ops.duckdns.org)

---

## Key Features

| Feature | Description |
|---|---|
| **Static Code Risk Analysis** | Clone a Git repository and run an AI-powered full-codebase review — security vulnerabilities, code quality issues, and actionable recommendations. Supports single-call and map-reduce strategies based on codebase size |
| **Automated Code Review** | GitHub / GitLab commit diff → code quality, potential bugs, security considerations. Optionally sends the result to a Slack channel via Incoming Webhook |
| **Pull Request / Merge Request Review** | Dedicated webhook (`/webhook/git/{application}/pull-request`) reviews PR/MR events. On `opened` / `reopened` / `ready_for_review` posts a summary comment; on `synchronize` (new commits pushed) posts **inline line-anchored comments** — GitHub uses the Reviews API (single review with N comments), GitLab uses the Discussions API (one per comment) with an accompanying summary note. Draft PRs are skipped; inline comments outside the current diff hunks are filtered out, and if the inline flow fails or returns nothing, the facade falls back to a single summary comment |
| **Slack Code Review Notification** | When a code review completes, the result (converted from Markdown to Slack mrkdwn) is posted to a per-application Slack Incoming Webhook channel. Toggle and webhook path are configurable per application in the UI |
| **LLM-Powered Error Analysis** | Grafana alert context + Loki logs + Prometheus metric series (optional) + stack-trace-related source snippets → root cause, affected components, source file references, and recommended actions |
| **Source Fix Suggestions** | Incident analysis can return structured source code suggestions (`filePath`, `originalCode`, `suggestionCode`, `description`, `lineNumber`) shown at the bottom of the AI Analysis panel with a side-by-side popup and copy action |
| **Persistent Repository Storage** | Optionally keep registered application repositories under `repository.local-path` to avoid repeated fresh checkouts; Redis locks protect branch switch/reset operations and failures fall back to temporary clones |
| **Real-Time Dashboard** | WebSocket STOMP push to browser on analysis completion |
| **Dynamic LLM Configuration** | Switch between OpenAI, Anthropic, DeepSeek, EXAONE (via FriendliAI), and Amazon Bedrock at runtime via the UI — no restart required. Each provider's API key is stored independently and can be updated separately. Amazon Bedrock credentials are managed via `application.yml` or environment variables and are never stored in Redis |
| **Multi-Application** | Register multiple application names; analysis history is scoped per application |
| **Zero-RDB Design** | Redis is the only data store; embedded Redis starts automatically in local dev |
| **Virtual Thread Executor** | Webhook handlers return immediately; analysis runs on Java 21 virtual threads. LLM API calls are rate-limited via a dedicated Semaphore (default: 20 concurrent) |
| **Admin Authentication** | Spring Security role-based access control — the `admin` super-account is auto-created on first startup with a one-time password printed to the console; only `admin` can create or delete other administrator accounts; App registration, update, deletion, and Code Risk Analysis require authentication |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Browser (SPA)                           │
│  Mustache + STOMP/SockJS  ←─── WebSocket /topic/firing         │
│                           ←─── WebSocket /topic/commit         │
└───────────────┬─────────────────────────────────────────────────┘
                │ HTTP
┌───────────────▼─────────────────────────────────────────────────┐
│                  Spring Boot Application                        │
│                                                                 │
│  WebhookController  ──►  AnalyzeFacade                         │
│  (POST /webhook/*)        │                                     │
│                           ├─ ApplicationService  ──► Redis      │
│  AiConfigController       ├─ GrafanaService      ──► Redis           │
│  LokiConfigController     ├─ GithubService       ──► Redis           │
│  ApplicationController    ├─ GitlabService       ──► Redis           │
│  FiringController         ├─ LokiService         ──► Loki API        │
│  CommitController         ├─ PrometheusService   ──► Prometheus API  │
│                           ├─ RepositoryService   ──► Git cache       │
│                           ├─ IncidentSourceContextService            │
│                           ├─ GithubConnector     ──► GitHub API      │
│                           ├─ GitlabConnector     ──► GitLab API      │
│                           └─ AiModelService      ──► LLM API        │
│                                    │                            │
│                           SimpMessagingTemplate                 │
│                                    │                            │
└────────────────────────────────────┼────────────────────────────┘
                                     │ WebSocket push
                              Connected browsers
```

**Layering rules**

- **Controller** — receives HTTP request and returns DTO; no business logic.
- **Facade** — orchestrates multiple services for a single use-case; marked `@Facade`.
- **Service** — single-responsibility business logic and Redis persistence.
- **Connector** — OpenFeign clients for Loki, Prometheus, GitHub, and GitLab REST APIs.

---

## Interface Flows

### Static Code Risk Analysis

```
User clicks "Run Static Analysis" in the dashboard
        │
        ▼
POST /api/code-risk
        │
        ├─ Look up registered Git repository URL for the application
        │
        ├─ Resolve access token (GitHub or GitLab token from Redis)
        │
        ├─ Prepare repository via JGit (with token auth if available)
        │    persistent sync when repository.stored=true, otherwise temporary clone
        │    requested branch is fetched, checked out, and hard-reset to origin/{branch}
        │
        ├─ Collect source files and build a code bundle
        │
        ├─ Estimate token count
        │    ≤ token-threshold (default: 27,000)
        │      → Single-call analysis
        │           Call LLM once with the full bundle
        │    > token-threshold
        │      → Map-reduce analysis
        │           Split into chunks → analyze each chunk in parallel
        │           (max concurrency: 3, delay: 1,000 ms between calls)
        │           Consolidate chunk results with a final LLM call
        │
        ├─ Parse LLM response
        │    Markdown analysis  (overall summary, recommendations)
        │    Issues JSON        (file, line, severity, description, codeSnippet)
        │
        ├─ Save CodeRiskRecord to Redis  (key: code-risk:{application})
        │
        ├─ Push progress messages to /topic/analysis/status via WebSocket
        │
        └─ Push completion notification to /topic/analysis/result via WebSocket
                │
                ▼
           Browser opens Code Risk tab with full analysis result
```

> **Note**: Analysis progress (cloning, chunk status, consolidation) is streamed to the dashboard in real time via WebSocket. If the LLM returns a rate-limit error (429) mid-analysis, the facade stops and returns partial results gathered up to that point.

> **Persistent repository storage**: If `repository.stored=true` and `repository.local-path` is valid, Code Risk analysis reuses a local persistent repository and synchronizes the requested branch before analysis. If sync fails, analysis falls back to a temporary clone.

> **Git Authentication**: The access token configured under Git Remote Configuration (GitHub or GitLab) is used automatically for private repository cloning. No additional setup is required.

---

### GitHub → LLM (Code Review)

```
git push to repository
        │
        ▼  (GitHub Webhook)
POST /webhook/git[/{application}]
        │
        ├─ Extract owner / repo / before SHA / after SHA from payload
        │
        ├─ Call GitHub Commits API
        │    before == 0000...0000 (initial push) → GET /repos/{owner}/{repo}/commits/{sha}
        │    otherwise                             → GET /repos/{owner}/{repo}/compare/{base}...{head}
        │
        ├─ Call LLM
        │    System: expert code reviewer
        │    User:   diff per changed file
        │            → summary / issues / security / suggestions
        │
        ├─ Save CodeReviewRecord to Redis  (key: commit:{application})
        │
        ├─ Push to /topic/commit via WebSocket
        │       │
        │       ▼
        │  Browser opens Code Review tab with result
        │
        └─ Publish CodeReviewCompletedEvent
                │
                ▼ (async EventListener)
           If Slack notification is enabled for the application
                │
                ├─ Convert Markdown review result → Slack mrkdwn
                │
                └─ POST https://hooks.slack.com/{slackChannel}
                        (Incoming Webhook — Block Kit message)
```

---

### GitLab → LLM (Code Review)

```
git push to repository
        │
        ▼  (GitLab Webhook)
POST /webhook/git[/{application}]
        │
        ├─ Detected by X-Gitlab-Event header → parsed as GitLab payload
        │
        ├─ Extract project / before SHA / after SHA from payload
        │
        ├─ Call GitLab Repository API
        │    before == 0000...0000 (initial push) → GET /projects/{id}/repository/commits/{sha}/diff
        │    otherwise                             → GET /projects/{id}/repository/compare?from={base}&to={head}
        │
        ├─ Call LLM
        │    System: expert code reviewer
        │    User:   diff per changed file
        │            → summary / issues / security / suggestions
        │
        ├─ Save CodeReviewRecord to Redis  (key: commit:{application})
        │
        ├─ Push to /topic/commit via WebSocket
        │       │
        │       ▼
        │  Browser opens Code Review tab with result
        │
        └─ Publish CodeReviewCompletedEvent
                │
                ▼ (async EventListener)
           If Slack notification is enabled for the application
                │
                ├─ Convert Markdown review result → Slack mrkdwn
                │
                └─ POST https://hooks.slack.com/{slackChannel}
                        (Incoming Webhook — Block Kit message)
```

---

### GitHub PR / GitLab MR → LLM (Inline Review)

```
Pull Request / Merge Request event
        │
        ▼  (GitHub `Pull requests` / GitLab `Merge request events`)
POST /webhook/git/{application}/pull-request
        │
        ├─ Detect provider via X-GitHub-Event / X-Gitlab-Event header
        │
        ├─ Normalise action
        │    OPENED       ← GitHub: opened / reopened / ready_for_review
        │                    GitLab: open / reopen
        │    SYNCHRONIZE  ← GitHub: synchronize
        │                    GitLab: update (only when oldrev is present)
        │    IGNORED      ← everything else (close / edit / label / WIP toggle w/o commits)
        │
        ├─ Validate: skip early with a warn log if IGNORED / draft / missing number/base/head
        │           (facade logs and returns; the async CompletableFuture completes normally)
        │
        ├─ Fetch full PR diff via compare API (baseSha…headSha) — used for inline comment positioning
        │
        └─ Route by action
             │
             ├─ OPENED / reopened / ready_for_review
             │     ├─ LLM code review (executeAnalyzeCodeDiffer)
             │     └─ Post ONE summary comment
             │          GitHub → POST /repos/{owner}/{repo}/issues/{n}/comments
             │          GitLab → POST /projects/{path}/merge_requests/{iid}/notes
             │
             └─ SYNCHRONIZE (new commits pushed to PR head)
                   ├─ Fetch DELTA diff via compare API (beforeSha…headSha)
                   │    — only the lines modified since the previous push
                   │    fallback: if beforeSha is missing or compare fails, use full PR diff
                   │
                   ├─ LLM inline review on the delta (executeAnalyzeCodeDifferInline)
                   │    Prompt asks for markdown summary + JSON array of {file, line, side, body}
                   │    delimited by ---INLINE_COMMENTS_JSON_START/END---
                   │
                   ├─ Parse into LlmInlineReviewResult(summary, comments)
                   │
                   ├─ Filter comments through the FULL PR diff hunks (baseSha…headSha)
                   │    each (file, line, side) must map to a real hunk position in the PR — the rest are dropped
                   │    (positions must be in PR-level coordinates for the Reviews/Discussions APIs)
                   │
                   ├─ If filtered list is empty
                   │      → post summary only (v1 fallback path)
                   │
                   └─ Otherwise post inline comments
                        GitHub → POST /repos/{owner}/{repo}/pulls/{n}/reviews
                                 (single Review with commit_id, body=summary, event=COMMENT, comments[])
                        GitLab → POST /projects/{path}/merge_requests/{iid}/discussions
                                 (one per comment, position: base_sha/start_sha/head_sha + new_path/new_line + old_path/old_line)
                                 + separate summary note when at least one discussion succeeds
                        If the inline call fails or all discussions error → fall back to summary comment
```

> **v1 vs v2**: v1 (2026-08-06) supports only the OPENED / reopened / ready_for_review actions with a single summary comment. v2 (2026-08-09) adds inline line-anchored comments for SYNCHRONIZE via `DiffHunkParser` (parses `@@ -a,b +c,d @@` headers to map file line ↔ diff position) and `InlineReviewParser` (extracts delimited JSON from LLM output).

> **Fallback conditions**: The pipeline falls back to a single summary comment when (a) the LLM returns zero inline comments, (b) all comments fall outside the current diff hunks after filtering, or (c) the inline API call fails or returns a 4xx response.

> **PR review does not persist**: Unlike the push-webhook code review, the PR/MR review flow does not save `CodeReviewRecord` to Redis or push updates via `/topic/commit`. Results are posted only to the PR/MR itself.

> **Diff scope**: v2 uses **two compare calls per SYNCHRONIZE push** — the delta (`beforeSha…headSha`) is sent to the LLM so it reviews only the lines modified since the previous push, while the full PR diff (`baseSha…headSha`) is used to filter and position inline comments (GitHub Reviews API and GitLab Discussions API operate on the PR-level coordinate system). Comments the LLM produces for lines outside the current full PR diff are dropped by the filter. When `beforeSha` is missing (e.g., malformed webhook) or the delta compare returns an error, the facade falls back to the full PR diff for LLM analysis too — matching v1 behavior.

---

### Grafana → Loki + Prometheus → LLM (Error Analysis)

```
Grafana Alert fires
        │
        ▼
POST /webhook/grafana[/{application}]
        │
        ├─ status == "resolved"? → skip (return RESOLVED)
        │
        ├─ Pick the first firing alert
        │    (or the first alert in the payload if none are marked firing)
        │
        ├─ Build selectors from alert labels
        │    Loki selector example:       {job="my-app", namespace="prod", pod="api-xyz"}
        │    Prometheus selector example: {job="my-app", namespace="prod", pod="api-xyz"}
        │
        ├─ Calculate time range
        │    start = alert.startsAt − 5 min buffer
        │    end   = alert.endsAt  (current time if zero-value)
        │
        ├─ Query Loki logs
        │    GET {loki.url}/loki/api/v1/query_range
        │    ?query={...}&start=...&end=...
        │
        ├─ Query Prometheus metrics in parallel  ── OPTIONAL (if prometheus.url is configured)
        │    GET {prometheus.url}/api/v1/query_range
        │    Reuses the same alert labels to build the metric selector
        │    and returns all matching series for the alert window
        │
        ├─ Prepare registered source repository in parallel  ── OPTIONAL
        │    Runs only when a deploy branch is configured
        │    Uses the configured deploy branch
        │    persistent sync when repository.stored=true, otherwise temporary clone
        │    Uses GitHub/GitLab token authentication when available
        │
        ├─ Parse JVM stack traces from Loki log text
        │    Resolve application frames to source files
        │    Extract focused snippets around stack trace line numbers
        │    Does not send the full repository to the LLM
        │
        ├─ Call LLM
        │    System: expert in application errors and logs
        │    User:   alert context
        │            + Loki log lines   (if available)
        │            + Prometheus metric data  (if available)
        │            + related source snippets  (if available)
        │            → root cause / affected components / related files
        │              / concrete fix guidance / source code suggestions JSON
        │
        ├─ Save AnalyzeFiringRecord to Redis  (key: firing:{application})
        │    Includes markdown analysis and structured sourceCodeSuggestions
        │
        └─ Push to /topic/firing via WebSocket
                │
                ▼
           Browser receives analysis result in real time
```

> **Prerequisite**: Prometheus metric labels and Loki stream labels should share the same identifying labels (`job`, `instance`, `namespace`, `pod`, etc.). Configure Promtail or Grafana Alloy accordingly so the same alert labels can be reused for both queries.

> **Prometheus is optional**: If `prometheus.url` is not set, the analysis proceeds with Loki logs only. No errors are raised.

> **Loki is required for error analysis**: The current Grafana alert pipeline always attempts a Loki query. Configure `loki.url` before using Grafana webhook analysis.

> **Source context is focused**: Incident source analysis uses stack trace frames to extract small nearby snippets only. The full repository is not sent to the LLM.

> **Deploy branch is required for source snippets**: If the registered application has no `deployBranch`, Grafana alert analysis skips source checkout, snippet extraction, and source code suggestions. It does not fall back to the repository default branch.

> **Git authentication for incident source checkout**: If the registered repository URL points to GitHub or GitLab, the configured Git remote token is used automatically when available. The token is optional; public repositories can be cloned without it.

---

## Screenshots

### Admin login required
*Registering, updating, or deleting an application and running Static Code Risk Analysis all require an administrator account. On first startup, the `admin` super-account is created automatically and its initial password is displayed in the application log — look for a line beginning with `Admin account created`.*

![Admin Account Initialize](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/AdminInitialize.png?raw=true)

---

### LLM API Key Configuration
*Enter your LLM provider and API key through the UI. The model is activated immediately without restarting the application.*

![LLM API Key Configuration](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/AIConfig.png?raw=true)

---

### Observability Integration (Loki Required, Prometheus Optional)
*Configure required Loki connection settings for Grafana alert analysis. Prometheus is also supported as an optional metric data source for richer incident context.*

![Metric Visualization](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/ObservabilityProviders.png?raw=true)

---

### Metric Visualization
*If Prometheus is configured and reachable, the dashboard renders a simple metric visualization for each application, including CPU usage, memory usage, average latency, and HTTP response status distribution over time.*

![Metric Visualization](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/MetricVisualization.png?raw=true)

---

### Static Code Risk Analysis — Real-Time Progress
*During Code Risk analysis, progress updates (cloning, chunk analysis, consolidation) are streamed to the dashboard in real time via WebSocket STOMP. You can monitor each stage without refreshing the page. An administrator account login is required to initiate the analysis.*

![Static Code Risk Analysis Progress](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/Progress.png?raw=true)

---

### Static Code Risk Analysis — Results
*Run a full AI-powered static analysis on any registered Git repository. Issues are grouped by file with severity levels (HIGH / MEDIUM / LOW), and each entry includes the affected code snippet and a recommended fix.*

![Static Code Risk Analysis Results](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/CodeRisk.png?raw=true)

---

### AI-Powered Code Review
*When a GitHub push event is received, the LLM reviews the commit diff per changed file and delivers a structured report — covering code quality, potential bugs, security considerations, and improvement suggestions.*

![Code Review](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/CodeReview.png?raw=true)

---

### Grafana Alerting Webhook
*When a Grafana alert fires, the webhook payload is delivered to Spring AI Ops in real time. The alert status and labels are visible in the dashboard.*

![Grafana Alerting Webhook](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/GrafanaAlerting.png?raw=true)

---

### AI-Powered Error Analysis
*The LLM analyzes the Grafana alert context together with Loki logs, optional Prometheus metric series, and focused source snippets resolved from JVM stack traces. Structured source code suggestions appear at the bottom of the AI Analysis panel; clicking a file path opens a popup with original code on the left and the suggested replacement on the right.*

![Firing Analysis](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/FiringAnalyze.png?raw=true)

---

### Source Fix Suggestions
*During error analysis, the system identifies source code that may contribute to the exception and provides concrete fix suggestions, including what to change and why the change helps prevent recurrence.*

![Suggested Source](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/SuggestedSource.png?raw=true)

---

## Technology Stack

| Category | Technology |
|---|---|
| Language | Kotlin 2.2 / Java 21 |
| Framework | Spring Boot 3.4.4 |
| AI | Spring AI 1.1.0 — OpenAI (`gpt-4o-mini`), Anthropic (`claude-sonnet-4-6`), DeepSeek (`deepseek-v4-pro`, OpenAI-compatible), EXAONE (`LGAI-EXAONE/K-EXAONE-236B-A23B` via FriendliAI, OpenAI-compatible), Amazon Bedrock (`us.amazon.nova-pro-v1:0` default, any Bedrock-supported model) |
| Observability | Loki (log queries), Prometheus (metric queries, optional) |
| Persistence | Redis (primary store, no RDBMS) |
| Dev Redis | Embedded Redis (auto-start, no install needed) |
| HTTP Client | Spring Cloud OpenFeign + Resilience4j Circuit Breaker |
| Real-Time | Spring WebSocket (STOMP over SockJS) |
| Templating | Mustache |
| API Docs | springdoc-openapi 2.8.3 (Swagger UI) |
| Async | Java 21 Virtual Threads (`CompletableFuture` + `SimpleAsyncTaskExecutor` concurrency limit: 200) + Semaphore-based LLM rate limiter |
| Build | Gradle Kotlin DSL |

**Design note — Spring AI AutoConfiguration disabled**

All Spring AI `AutoConfiguration` classes are explicitly excluded in `application.yml`. `AiModelService` builds `OpenAiChatModel` / `AnthropicChatModel` / `BedrockProxyChatModel` directly using `ToolCallingManager.builder().build()`, `RetryUtils.DEFAULT_RETRY_TEMPLATE`, and `ObservationRegistry.NOOP`. This gives full control over model instantiation and allows hot-swapping the LLM provider at runtime. DeepSeek and EXAONE both use the OpenAI-compatible API (`OpenAiChatModel` pointed at their respective base URLs — `https://api.deepseek.com` and FriendliAI's serverless endpoint `https://api.friendli.ai/serverless`). EXAONE requires a FriendliAI API key obtained from [friendli.ai](https://friendli.ai).

Amazon Bedrock uses `BedrockProxyChatModel` from `spring-ai-bedrock-converse`. AWS credentials are supplied via `ai.bedrock.access-key` / `ai.bedrock.secret-key` in `application.yml` or environment variables (`AI_BEDROCK_ACCESS_KEY` / `AI_BEDROCK_SECRET_KEY`). If both are blank, the AWS SDK's `DefaultCredentialsProvider` is used (IAM role, `~/.aws/credentials`, etc.). Bedrock credentials are **never** stored in Redis. When BEDROCK is selected as the active provider in the UI, the API key input field is disabled.

**Design note — Virtual Thread concurrency**

The `SimpleAsyncTaskExecutor` is capped at **200 concurrent tasks** via `app.async.virtual.executor-concurrency-limit`. This bounds asynchronous work triggered by inbound webhooks while still using Virtual Threads for efficient blocking I/O. A separate `Semaphore` (`app.async.virtual.llm-max-concurrency`, default `10`) further limits only the actual LLM API call inside `AiModelService`, so webhook fan-out and external API work do not translate into unbounded model traffic.

**Design note — Resilience4j TimeLimiter + Virtual Thread compatibility**

Resilience4j's `TimeLimiter` cancels timed-out tasks via `future.cancel(true)`, which calls `Thread.interrupt()`. Virtual Threads handle interrupts differently from platform threads — particularly when pinned to a carrier thread — so the interrupt may not propagate correctly, leaving tasks running past the timeout silently.

To avoid this, `resilience4j.timelimiter.configs.default.cancel-running-future` is set to `false`. This disables the interrupt-based cancellation. Actual I/O timeouts are enforced instead by Feign's own `Request.Options` (`feign.loki.*` / `feign.github.*`), which operate at the socket level and are not affected by the Virtual Thread interrupt issue. The Circuit Breaker state machine (open/half-open/closed) and `FallbackFactory` remain fully active.

---

## Getting Started

### Prerequisites

**Required to complete initial setup and access the dashboard**

- **An API key for at least one LLM provider** (OpenAI, Anthropic, DeepSeek, FriendliAI for EXAONE, or AWS credentials for Amazon Bedrock). The application cannot proceed past the initial setup modal without an active LLM configuration.
- **A GitHub or GitLab personal access token — at least one of the two is required.** The dashboard forces Git Remote Configuration on first launch and remains blocked until either a GitHub or GitLab token is saved. The token is used for code review on push, PR/MR inline review, and cloning registered application repositories for Static Code Risk Analysis and Grafana source-snippet extraction.

**Also required by the runtime**

- JDK 21+
- A non-empty `CRYPTO_SECRET_KEY` value for encrypting Redis-stored credentials

**Optional**

- A running Loki instance — required only for Grafana alert analysis. The initial Observability setup modal offers a **Skip for now** option; if you skip it, you can still use Code Review and Static Code Risk Analysis, but Grafana webhook analysis will be unavailable until Loki is configured.
- A running Prometheus instance for metric queries and per-application dashboard charts
- An OTLP-compatible tracing backend or OpenTelemetry Collector for trace export

### Configuration

Edit `src/main/resources/application.yml`:

```yaml
ai:
  open-ai:
    model: gpt-4o-mini                   # OpenAI model name
    api-key: ${AI_OPEN_AI_API_KEY:}      # Or set env var AI_OPEN_AI_API_KEY
  anthropic:
    model: claude-sonnet-4-6             # Anthropic model name
    api-key: ${AI_ANTHROPIC_API_KEY:}    # Or set env var AI_ANTHROPIC_API_KEY
    max-tokens: 8192                     # Max output tokens for Anthropic (default: 8192)
  deepseek:
    model: deepseek-v4-pro               # DeepSeek model name
    api-key: ${AI_DEEP_SEEK_API_KEY:}    # Or set env var AI_DEEP_SEEK_API_KEY
    base-url: ${AI_DEEP_SEEK_BASE_URL:https://api.deepseek.com}  # DeepSeek API base URL
  exaone:
    model: LGAI-EXAONE/K-EXAONE-236B-A23B  # EXAONE model served via FriendliAI
    api-key: ${AI_EXAONE_API_KEY:}          # Or set env var AI_EXAONE_API_KEY (issued at friendli.ai)
    base-url: ${AI_EXAONE_BASE_URL:https://api.friendli.ai/serverless}  # FriendliAI serverless endpoint
  bedrock:
    region: ${AI_BEDROCK_REGION:us-east-1}            # AWS region for Bedrock
    model: ${AI_BEDROCK_MODEL:us.amazon.nova-pro-v1:0} # Bedrock model ID
    access-key: ${AI_BEDROCK_ACCESS_KEY:}             # AWS Access Key ID (optional — falls back to DefaultCredentialsProvider)
    secret-key: ${AI_BEDROCK_SECRET_KEY:}             # AWS Secret Access Key (optional — falls back to DefaultCredentialsProvider)

loki:
  url: ${LOKI_URL:}                      # e.g. http://localhost:3100 (authentication is not supported)

prometheus:
  url: ${PROMETHEUS_URL:}                # e.g. http://localhost:9090 (optional — omit to skip metric queries)

github:
  url: ${GITHUB_URL:https://api.github.com}
  access-token: ${GITHUB_ACCESS_TOKEN:}  # GitHub personal access token
  api-version: ${GITHUB_API_VERSION:2022-11-28}  # GitHub API version header

gitlab:
  url: ${GITLAB_URL:https://gitlab.com/api/v4}  # GitLab API base URL (use your self-hosted URL if applicable)
  access-token: ${GITLAB_ACCESS_TOKEN:}          # GitLab personal access token

analysis:
  data-retention-hours: 120  # How long to keep analysis records (default: 5 days)
  maximum-view-count: 5      # Max records shown per application (0 = unlimited)
  result-language: ${ANALYSIS_RESULT_LANGUAGE:en}  # Language of LLM analysis output (e.g. ko, ja, en)
  code-risk:
    token-threshold: 27000        # Max tokens for single-call analysis; larger bundles switch to map-reduce (default: 27000)
    map-reduce-concurrency: 3     # Max parallel chunk analysis calls in map phase (default: 3)
    map-reduce-delay-ms: 1000     # Delay (ms) after each chunk call in map phase (default: 1000)

app:
  async:
    virtual:
      executor-concurrency-limit: 200  # Max concurrent async tasks using virtual threads
      llm-max-concurrency: 20          # Max simultaneous in-flight LLM API calls (Semaphore)

repository:
  stored: false                         # true = keep application repositories under repository.local-path
  local-path: ${REPOSITORY_LOCAL_PATH:./data/repository}
  lock:
    ttl-ms: 30000                       # Redis lock TTL for repository mutation operations
    wait-timeout-ms: 15000              # Max time to wait for another worker's repository lock
    retry-interval-ms: 1000             # Retry delay while waiting for the repository lock

management:
  tracing:
    sampling:
      probability: 1.0  # Trace sampling rate; 1.0 exports all sampled traces in local/dev
  otlp:
    tracing:
      transport: http
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://127.0.0.1:4318/v1/traces}
      export:
        enabled: false  # Set to true to send traces to the OTLP endpoint

resilience4j:
  timelimiter:
    configs:
      default:
        timeout-duration: 35s         # Safety net only — Feign read-timeout (30s) fires first
        cancel-running-future: false  # Prevent Thread.interrupt() on Virtual Threads

feign:
  loki:
    connect-timeout: 5000   # Loki connect timeout (ms)
    read-timeout: 30000     # Loki read timeout (ms)
  prometheus:
    connect-timeout: 5000   # Prometheus connect timeout (ms)
    read-timeout: 30000     # Prometheus read timeout (ms)
  github:
    connect-timeout: 5000   # GitHub API connect timeout (ms)
    read-timeout: 30000     # GitHub API read timeout (ms)
  gitlab:
    connect-timeout: 5000   # GitLab API connect timeout (ms)
    read-timeout: 30000     # GitLab API read timeout (ms)
```

If both a property value and a Redis value exist for the same setting, the Redis value takes precedence.

**Persistent repository storage**

`repository.stored=false` keeps the legacy behavior: each Code Risk analysis or Grafana source snippet extraction clones into a temporary directory.

When `repository.stored=true` and `repository.local-path` is valid, Spring AI Ops stores each registered application repository under a deterministic path inside `repository.local-path`. App registration/update returns after Redis metadata is saved, then a background virtual-thread task prepares the repository. Code Risk analysis and Grafana source snippet extraction reuse the persistent working copy by acquiring a Redis lock, running `fetch -> checkout/switch branch -> reset --hard origin/{branch}`, and then releasing the lock.

If persistent preparation fails, analysis falls back to a temporary clone. Background checkout failures are sent to the dashboard through `/topic/alert`. If a configured deploy branch is invalid during app save, the repository default branch is checked out for storage initialization and the saved deploy branch is cleared.

Use a secure local path with enough disk space. Rename/delete operations remove only paths that resolve safely under `repository.local-path`.

**Tracing export**

The application includes Spring Boot Actuator and `micrometer-tracing-bridge-otel`, so server requests and instrumented client calls can be converted into OpenTelemetry traces. Set `management.otlp.tracing.export.enabled=true` and point `management.otlp.tracing.endpoint` to your OpenTelemetry Collector, Tempo, Jaeger, or another OTLP-compatible receiver. The default HTTP endpoint is `http://127.0.0.1:4318/v1/traces`; for gRPC, change `management.otlp.tracing.transport` and use the matching collector endpoint.

### Sensitive Value Encryption

API keys and access tokens saved to Redis are encrypted at rest using **AES-256-GCM**.

`crypto.secret-key` is required. If it is blank, the application logs a fatal security configuration error and stops during startup.

```bash
# Environment variable (recommended)
export CRYPTO_SECRET_KEY=your-strong-secret-passphrase
```

```yaml
# application.yml
crypto:
  secret-key: ${CRYPTO_SECRET_KEY:}
```

| Situation | Behaviour |
|---|---|
| `crypto.secret-key` is set | All values written to Redis are AES-256-GCM encrypted |
| `crypto.secret-key` is blank | Startup fails and the application exits before serving requests |
| Secret key changes after values are stored | Existing encrypted values cannot be decrypted; re-enter API keys via the UI to re-encrypt them with the new key |

**LLM key auto-configuration behaviour**

| Situation | Result |
|---|---|
| Only one provider key present in yml | That provider is selected automatically on startup — no UI prompt |
| Multiple provider keys present in yml | A provider-selection modal appears in the UI once |
| No keys in yml | The full API key entry modal appears in the UI |
| Key saved via UI before | Redis value is restored on restart — no prompt |
| Multiple providers have keys saved in UI | The previously active provider is restored; others show **Key saved** in the configuration modal |

### Running

```bash
# Build
./gradlew build

# Run (embedded Redis starts automatically)
./gradlew bootRun

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.walter.spring.ai.ops.service.AiModelServiceTest"
```

Open `http://localhost:7079` in your browser. On first launch you will be prompted to enter your LLM API key (unless pre-configured in yml).

### Setting Up Grafana

1. In Grafana, go to **Alerting → Contact points → New contact point**.
2. Select type **Webhook**.
3. Set URL to `http://<your-host>:7079/webhook/grafana` (or `/webhook/grafana/{application}` to tag results with an application name).
4. Add the contact point to your alert rule's notification policy.

> Configure `loki.url` first. Loki is the primary data source for Grafana alert analysis; Prometheus only enriches the analysis when configured.

> Ensure Prometheus labels (`job`, `instance`, `namespace`, `pod`, etc.) and Loki stream labels are aligned so the same alert labels can drive both log and metric queries automatically.

> **Note**: Loki authentication (Basic Auth, Bearer Token, etc.) is not currently supported. Only unauthenticated Loki endpoints are supported at this time.

### Setting Up GitHub Webhooks

**Push events** (code review on push):

1. Go to **Repository → Settings → Webhooks → Add webhook**.
2. Set **Payload URL** to `http://<your-host>:7079/webhook/git/{application}`.
3. Set **Content type** to `application/json`.
4. Select event: **Just the push event**.
5. Save the webhook.

**Pull request events** (PR summary/inline review — optional, add as a separate webhook):

1. Add another webhook on the same repository.
2. Set **Payload URL** to `http://<your-host>:7079/webhook/git/{application}/pull-request`.
3. Set **Content type** to `application/json`.
4. Under **Which events would you like to trigger this webhook?** choose **Let me select individual events** and check **Pull requests** only.
5. Save the webhook.

Ensure your GitHub personal access token (configured in yml or via the UI) has `repo` read scope (classic PAT). Fine-grained PATs need `Contents: Read` for push-diff review, plus `Pull requests: Read and write` to post PR review comments.

> **Note**: GitHub Webhook **Secret** is not currently supported. Leave the Secret field blank when configuring the webhook. Secret-based HMAC-SHA256 signature verification is planned for a future release.

### Setting Up GitLab Webhooks

**Push events** (code review on push):

1. Go to **Project → Settings → Webhooks → Add new webhook**.
2. Set **URL** to `http://<your-host>:7079/webhook/git/{application}`.
3. Under **Trigger**, check **Push events**.
4. Save the webhook.

**Merge request events** (MR summary/inline review — optional, add as a separate webhook):

1. Add another webhook on the same project.
2. Set **URL** to `http://<your-host>:7079/webhook/git/{application}/pull-request`.
3. Under **Trigger**, check **Merge request events** only.
4. Save the webhook.

Ensure your GitLab personal access token (configured in yml or via the UI) has `api` scope for MR review (or at minimum `read_api` for push-only review). For self-hosted GitLab instances, set `gitlab.url` to your instance's API base URL (e.g. `https://gitlab.example.com/api/v4`).

> **Note**: GitLab Webhook **Secret Token** is not currently supported. Leave the Secret Token field blank when configuring the webhook.

---

## Admin Authentication

Spring AI Ops uses **Spring Security** to protect write operations. When no administrator account exists in Redis, the `admin` super-account is created automatically on application startup and its initial password is printed once to the log:

```
[AdminAccountService] Admin account created — username: admin, password: <generated-password>
```

> Store this password securely. It is printed only once at startup and cannot be recovered after the log is rotated.

### Protected Operations

| Operation | Authentication required |
|---|---|
| Register a new application | Yes |
| Update application settings | Yes |
| Delete an application | Yes |
| Run Static Code Risk Analysis | Yes |
| Read analysis results and dashboard | No |
| Configure LLM / Loki / Prometheus / Git | No |

### Account Management

Administrator accounts are managed from the **Account Management** panel in the dashboard. Only the `admin` super-account can create or delete other administrator accounts.

> The `admin` super-account itself cannot be deleted.

---

## API Reference

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Dashboard UI |
| `POST` | `/api/auth/login` | Log in → creates session |
| `POST` | `/api/auth/logout` | Log out → invalidates session |
| `POST` | `/api/auth/password` | Change own password |
| `POST` | `/api/auth/admin` | Create additional admin account (admin only) |
| `GET` | `/api/auth/admins` | List admin accounts (admin only) |
| `DELETE` | `/api/auth/admins` | Remove admin accounts (admin only) |
| `POST` | `/api/llm/config` | Save LLM provider + API key |
| `POST` | `/api/llm/select-provider` | Select provider when both yml keys are present |
| `GET` | `/api/loki/status` | Get Loki configuration status |
| `POST` | `/api/loki/config` | Save Loki base URL |
| `GET` | `/api/prometheus/status` | Get Prometheus configuration status |
| `POST` | `/api/prometheus/config` | Save Prometheus base URL (optional) |
| `GET` | `/api/prometheus/application-metrics` | Get per-application Prometheus metrics (memory, CPU, latency, HTTP status) |
| `POST` | `/api/github/config` | Save GitHub / GitLab access token and base URL |
| `GET` | `/api/github/config/status` | Get Git provider configuration status |
| `GET` | `/api/apps` | List registered applications |
| `GET` | `/api/apps/{name}` | Get application Git config |
| `POST` | `/api/apps` | Register a new application |
| `PUT` | `/api/apps/{name}` | Update application Git config |
| `DELETE` | `/api/apps/{name}` | Remove an application |
| `GET` | `/api/firing/{application}/list` | Get alert analysis records for an application |
| `GET` | `/api/commit/{application}/list` | Get code review records for an application |
| `POST` | `/api/code-risk` | Run static code risk analysis for an application |
| `GET` | `/api/code-risk/{application}/list` | Get static analysis records for an application |
| `POST` | `/webhook/grafana[/{application}]` | Grafana Alerting webhook receiver |
| `POST` | `/webhook/git[/{application}]` | GitHub / GitLab push webhook receiver |
| `POST` | `/webhook/git/{application}/pull-request` | GitHub Pull Request / GitLab Merge Request webhook receiver (summary comment on open, inline comments on synchronize) |

**WebSocket topics** (STOMP over SockJS at `/ws`)

| Topic | Payload | Triggered when |
|---|---|---|
| `/topic/firing` | `AnalyzeFiringRecord` | LLM error analysis completes |
| `/topic/commit` | `CodeReviewRecord` | LLM code review completes |
| `/topic/analysis/status` | `String` | Static analysis progress update (clone / chunk / consolidate) |
| `/topic/analysis/result` | `CodeRiskRecord` | Static analysis completes |
| `/topic/alert` | `AlertMessage` | Background repository checkout needs user attention |

---

## API Documentation (Swagger)

Spring AI Ops integrates [springdoc-openapi](https://springdoc.org/) to provide interactive API documentation.

| URL | Description |
|---|---|
| `http://localhost:7079/swagger-ui.html` | Swagger UI — browse and try all REST endpoints |
| `http://localhost:7079/v3/api-docs` | OpenAPI 3.0 spec (JSON) |
| `http://localhost:7079/v3/api-docs.yaml` | OpenAPI 3.0 spec (YAML) |

The Swagger UI is useful for testing webhook payloads and configuration endpoints without an external tool.

---

## Package Structure

```
com.walter.spring.ai.ops
├── SpringAiOpsApplication.kt
├── code/                          # Enums and constants
│   ├── AlertMessageType.kt        # Typed frontend alert payload categories
│   ├── AlertingStatus.kt          # FIRING / RESOLVED / ACCEPTED
│   ├── AnalysisStatus.kt          # ANALYZED / SKIPPED_NO_OBSERVABILITY / SKIPPED_OBSERVABILITY_ERROR
│   ├── ConnectionStatus.kt        # SUCCESS / READY / FAILURE
│   ├── DiffSide.kt                # LEFT / RIGHT — side of a diff a PR/MR inline comment refers to (v2)
│   ├── GitRemoteProvider.kt       # GITHUB / GITLAB
│   ├── LlmProvider.kt             # OPEN_AI / ANTHROPIC / DEEP_SEEK / EXAONE / BEDROCK
│   ├── ObservabilityProvider.kt   # LOKI / PROMETHEUS
│   ├── PullRequestAction.kt       # OPENED / SYNCHRONIZE / IGNORED — normalised PR/MR action (v1/v2)
│   ├── RedisKeyConstants.kt       # Centralised Redis key constants
│   └── RepositoryCloneStatus.kt   # RUNNING / SUCCESS / FAILED — background checkout state
├── config/
│   ├── AuthenticationInterceptor.kt        # Enforces login on PROTECTED_ROUTES
│   ├── CsrfTokenInterceptor.kt             # Validates X-CSRF-Token on /api/code-risk/**
│   ├── CsrfTokenProvider.kt                # Generates startup-time CSRF token
│   ├── EmbeddedRedisConfig.kt              # Auto-start embedded Redis (local profile)
│   ├── GithubConnectorConfig.kt            # Feign client configuration for GitHub API
│   ├── GitlabConnectorConfig.kt            # Feign client configuration for GitLab API
│   ├── GlobalExceptionHandler.kt           # Maps custom exceptions (e.g. InvalidPullRequestException) → JSON error
│   ├── LokiConnectorConfig.kt              # Feign client configuration for Loki API
│   ├── MapperConfig.kt                     # Primary ObjectMapper bean
│   ├── PasswordChangeRequiredInterceptor.kt # Blocks /api/** when password change is pending
│   ├── PrometheusConnectorConfig.kt        # Feign client configuration for Prometheus API
│   ├── RepositoryProperties.kt             # Persistent repository storage settings and path safety
│   ├── SecurityConfig.kt                   # Spring Security session config
│   ├── SwaggerConfig.kt                    # springdoc-openapi OpenAPI info & server config
│   ├── VirtualThreadConfig.kt              # Virtual thread executor + LLM rate-limit Semaphore
│   ├── WebMvcConfig.kt                     # Registers interceptors
│   ├── WebSocketConfig.kt                  # STOMP WebSocket endpoint & broker
│   ├── annotation/
│   │   ├── AdminOnly.kt                    # Method-level admin-only marker (enforced by aspect)
│   │   └── Facade.kt                       # @Facade stereotype meta-annotation
│   ├── aspect/AdminOnlyAspect.kt           # Enforces @AdminOnly via SecurityContextHolder
│   ├── base/
│   │   ├── DynamicConnectorConfig.kt       # Base for runtime-URL Feign configs
│   │   └── LenientMapper.kt                # ObjectMapper variant tolerating unknown fields
│   └── exception/
│       ├── ForbiddenException.kt           # 403
│       └── UnauthorizedException.kt        # 401
├── connector/
│   ├── GithubConnector.kt                  # Feign: GitHub Commits / Compare / Issue Comments / Reviews
│   ├── GithubConnectorFallbackFactory.kt   # Resilience4j fallback for GitHub
│   ├── GitlabConnector.kt                  # Feign: GitLab Compare / Commit Diff / MR Notes / Discussions
│   ├── GitlabConnectorFallbackFactory.kt   # Resilience4j fallback for GitLab
│   ├── LokiConnector.kt                    # Feign: Loki query_range
│   ├── LokiConnectorFallbackFactory.kt     # Resilience4j fallback for Loki
│   ├── PrometheusConnector.kt              # Feign: Prometheus query_range
│   ├── PrometheusConnectorFallbackFactory.kt # Resilience4j fallback for Prometheus
│   ├── SlackChannelConnector.kt            # Feign: Slack Incoming Webhook
│   └── dto/                                # Connector DTOs (see below)
│       ├── GitCommentRequest.kt            # Body payload for issue/MR-note comments (v1)
│       ├── GitCompareResult.kt             # Interface — errorMessage / prompt / changedFiles / parseDiffs
│       ├── GitDifferInquiry.kt             # owner/repo/base/head/commitShas passed to compare
│       ├── Github*.kt                      # GitHub compare/commit/file DTOs
│       ├── GithubIssueCommentResponse.kt   # GitHub issue-comment response (v1)
│       ├── GithubReview{Comment,Request,Response}.kt  # GitHub PR Review API (v2)
│       ├── Gitlab*.kt                      # GitLab compare/commit/file DTOs
│       ├── GitlabMergeRequestNoteResponse.kt          # GitLab MR-note response (v1)
│       ├── GitlabDiscussion{Position,Request,Response,Note}.kt  # GitLab MR discussion API (v2)
│       ├── Loki*.kt / Prometheus*.kt / Slack*.kt      # Observability & Slack DTOs
│       └── ...
├── controller/
│   ├── AiConfigController.kt               # POST /api/llm/*
│   ├── ApplicationController.kt            # GET|POST|PUT|DELETE /api/apps/**
│   ├── AuthController.kt                   # POST /api/auth/{login,logout,password,admin,admins}
│   ├── CodeRiskController.kt               # POST /api/code-risk, GET /api/code-risk/{app}/list
│   ├── CommitController.kt                 # GET /api/commit/{app}/list
│   ├── DashboardController.kt              # GET /
│   ├── FiringController.kt                 # GET /api/firing/{app}/list
│   ├── GitRemoteConfigController.kt        # POST /api/github/config, GET /api/github/config/status
│   ├── LokiConfigController.kt             # GET|POST /api/loki/*
│   ├── PrometheusConfigController.kt       # GET|POST /api/prometheus/*
│   ├── WebhookGitRemote.kt                 # POST /webhook/git[/{application}] + /webhook/git/{application}/pull-request (v1/v2)
│   ├── WebhookGrafana.kt                   # POST /webhook/grafana[/{application}]
│   └── dto/                                # Request/Response DTOs
│       ├── GithubPullRequestRequest.kt     # Normalised PR/MR payload (v1) — action, number, baseSha, headSha, beforeSha, draft, ...
│       ├── GithubPullRequestResponse.kt    # ACCEPTED / SKIPPED (v1)
│       └── ...                             # AiConfig, App*, Auth, CodeRisk, Commit, Firing, Github push, Grafana alert, Loki, Prometheus, Slack, ...
├── event/
│   ├── CodeReviewCompletedEvent.kt         # Published after push-webhook code review
│   ├── CodeReviewCompletedEventListener.kt # Fans out to Slack when enabled
│   ├── RateLimitHitEvent.kt                # Published by AiModelService on 429 response
│   └── RateLimitHitEventListener.kt        # Pushes rate-limit notice via WebSocket
├── facade/
│   ├── ApplicationFacade.kt                # App registry + background repository checkout
│   ├── CodeReviewFacade.kt                 # Push-webhook AND PR/MR webhook — v1 summary + v2 inline routing (SYNCHRONIZE → postInlineReview)
│   ├── CodeRiskFacade.kt                   # Static analysis (prepare → analyze → save)
│   ├── DashboardFacade.kt                  # Dashboard aggregation
│   ├── GitRemoteFacade.kt                  # GitHub/GitLab configuration orchestration
│   └── IncidentAnalyzeFacade.kt            # Grafana firing analysis flow
├── record/                                 # Java records (immutable Redis-persisted results)
│   ├── Administrator.java                  # Admin account
│   ├── AnalyzeFiringRecord.java            # Grafana firing analysis result
│   ├── ChangedFile.java                    # Per-file diff info
│   ├── CodeReviewRecord.java               # Push-webhook code review result
│   ├── CodeRiskIssue.java                  # Per-issue entry: file, line, severity, description, codeSnippet
│   ├── CodeRiskRecord.java                 # Static analysis result
│   ├── CommitSummary.java                  # Commit metadata
│   └── SourceCodeSuggestion.java           # LLM-suggested source code change
├── service/
│   ├── AdminService.kt                     # Admin account management
│   ├── AiModelService.kt                   # ChatModel lifecycle + LLM calls (includes executeAnalyzeCodeDifferInline for v2)
│   ├── ApplicationService.kt               # Application registry (Redis)
│   ├── GitRemoteService.kt                 # Abstract base — abstract postPullRequestComment + postPullRequestInlineComments (v2)
│   ├── GithubService.kt                    # GitHub differ inquiry + v1 issue comment + v2 Review API batch inline post
│   ├── GitlabService.kt                    # GitLab differ inquiry + v1 MR note + v2 Discussions per-comment + summary note
│   ├── GrafanaService.kt                   # Firing record persistence
│   ├── IncidentSourceContextService.kt     # Stack-trace parsing + source snippet extraction
│   ├── LokiService.kt                      # Loki log query execution
│   ├── MessageService.kt                   # WebSocket push for all topics
│   ├── PrometheusService.kt                # Prometheus metric query execution
│   ├── RepositoryService.kt                # JGit clone/sync + code-risk record persistence
│   ├── SlackChannelService.kt              # Slack Incoming Webhook client
│   └── dto/                                # Service DTOs
│       ├── AlertMessage.kt / AppConfig.kt / CodeChunk.kt / IncidentSourceContext.kt / LlmConfig.kt / RepositoryStatus.kt / SourceSnippet.kt / StackTraceFrame.kt
│       ├── HunkLine.kt                     # (v2) One (line, side) entry from parsed diff — side + line + newLine + oldLine
│       ├── LlmInlineComment.kt             # (v2) LLM output element — file, line, side, body
│       ├── LlmInlineReviewResult.kt        # (v2) Parsed LLM output — summary + comments list
│       └── ParsedFileDiff.kt               # (v2) Per-file parser output — newPath / oldPath / (line, side) → HunkLine map
└── util/
    ├── CodeAnalysisResultHandler.kt        # JSON parsing, sanitisation, and recovery for LLM issue output
    ├── CryptoProvider.kt                   # AES-256-GCM encryption/decryption
    ├── DiffHunkParser.kt                   # (v2) Parses `@@ -a,b +c,d @@` headers → ParsedFileDiff for PR inline positioning
    ├── GitRemoteResolver.kt                # Resolves GitRemoteProvider → concrete Git service
    ├── InlineReviewParser.kt               # (v2) Extracts delimited JSON from LLM output → LlmInlineReviewResult
    ├── MarkdownConverter.kt                # Markdown → Slack mrkdwn conversion
    ├── MetricHandler.kt                    # Prometheus metric series builder
    ├── RedisLockManager.kt                 # Token-based Redis locks
    ├── StackTraceParser.kt                 # Extracts stack frames from raw log text
    ├── dto/RedisLock.kt                    # Redis lock handle
    └── extension/
        ├── DoubleExtensions.kt / MatchResultExtensions.kt / PathExtensions.kt
        ├── RedisExtensions.kt              # zSetPushWithTtl / zSetRangeAllDesc / getArrayList
        ├── StringExtentions.kt             # toISO8601, extractSourceSnippet helpers
        └── URIExtentions.kt                # URI builder helpers
```

---

## Changelog

| Date       | Description |
|------------|---|
| 2026-08-09 | Fixed **Amazon Bedrock provider initialization / configure flow** — `initialize()` now bypasses the API-key decrypt step when the persisted `LlmConfig` provider is BEDROCK (BEDROCK has no API key) and directly assigns the pre-built `bedrockChatModel` to `chatModel`; `configure(BEDROCK, "")` lazily builds `bedrockChatModel` on first use instead of requiring `initialize()` to have run first |
| 2026-08-09 | Added **Pull Request / Merge Request inline review (v2)** for the `synchronize` action — when new commits are pushed to a PR/MR head, the facade fetches the delta diff (`beforeSha…headSha`) so the LLM reviews only the lines modified since the previous push, then filters and positions inline comments against the full PR diff (`baseSha…headSha`) via `DiffHunkParser` before posting through the GitHub Reviews API (single review with N comments) or GitLab Discussions API (per-comment + summary note). Falls back to a single summary comment when the inline flow returns nothing or fails |
| 2026-08-06 | Added **Pull Request / Merge Request summary review (v1)** — dedicated `/webhook/git/{application}/pull-request` endpoint reviews `opened` / `reopened` / `ready_for_review` events (GitHub) and `open` / `reopen` events (GitLab), posting a single summary comment via the Issue Comment / MR Notes API. Invalid webhook payloads (IGNORED action / draft PR / missing SHA) are logged and skipped early. Draft PRs are skipped |
| 2026-05-24 | Added Amazon Bedrock as a supported LLM provider — uses `BedrockProxyChatModel` via `spring-ai-bedrock-converse`; AWS credentials are configured via `ai.bedrock.*` in `application.yml` or env vars `AI_BEDROCK_ACCESS_KEY` / `AI_BEDROCK_SECRET_KEY` (falls back to `DefaultCredentialsProvider` when blank); credentials are never stored in Redis; the API key input field is disabled in the UI when BEDROCK is selected |
| 2026-05-16 | Added Slack Incoming Webhook notification for code reviews — when a code review completes, the result is converted from Markdown to Slack mrkdwn and posted to a per-application Slack channel. Toggle (`Send Slack Notification on Code Review`) and webhook path are configurable per application in the UI |
| 2026-05-12 | Added EXAONE (LG AI Research) as a supported LLM provider — served via FriendliAI's serverless API (OpenAI-compatible); configurable via `ai.exaone.*` in application.yml or env vars `AI_EXAONE_API_KEY` / `AI_EXAONE_BASE_URL`. API key must be obtained from [friendli.ai](https://friendli.ai). Placeholder message in the LLM configuration UI guides users to FriendliAI when no key is saved |
| 2026-05-04 | Added role-based admin authentication via Spring Security — `admin` super-account is auto-created on first startup with a one-time password logged to the console; only `admin` can create or delete other administrator accounts; App registration, update, deletion, and Code Risk Analysis require authentication |
| 2026-05-03 | Added DeepSeek as a supported LLM provider — uses the OpenAI-compatible API (`deepseek-v4-pro` default); configurable via `ai.deepseek.*` in application.yml or env vars `AI_DEEP_SEEK_API_KEY` / `AI_DEEP_SEEK_BASE_URL`. Multiple provider API keys can be saved independently via the UI and switched at runtime |
| 2026-05-02 | Added Prometheus Application Metrics dashboard (`GET /api/prometheus/application-metrics`) — displays per-application JVM memory (used %, allocated MB, used MB), uptime, open-file count, CPU usage (system / process), average HTTP latency, and HTTP status breakdown (2xx / 4xx / 5xx) as time-series charts when `prometheus.url` is configured |
| 2026-04-30 | Added optional persistent repository storage — registered repositories can be kept under `repository.local-path`, synchronized under a Redis lock, reused by Code Risk and Grafana source snippet flows, and cleaned up on app rename/delete |
| 2026-04-29 | Added source code suggestion support for Grafana alert analysis — stack-trace-related snippets are sent to the LLM, structured `sourceCodeSuggestions` are stored with firing records, and the UI renders original/suggested code side by side |
| 2026-04-26 | Added Prometheus metric query to Grafana alert analysis — `PrometheusService` fetches `query_range` data alongside Loki logs and sends both to the LLM; Prometheus is optional (`prometheus.url` may be left blank) |
| 2026-04-22 | Added CSRF token same-origin protection for `/api/code-risk/**` — token embedded in HTML meta tag, validated via `X-CSRF-Token` header |
| 2026-04-22 | Added fallback JSON parser in `CodeAnalysisResultHandler` to recover partial issue data when LLM returns malformed delimiters or truncated JSON |
| 2026-04-20 | Added Static Code Risk Analysis — clone a Git repository and run AI-powered full-codebase review with single-call or map-reduce strategy; results include per-issue severity, affected file, and code snippet |
| 2026-04-18 | Added GitLab push webhook support — automatically detected via `X-Gitlab-Event` header on the unified `/webhook/git` endpoint |
| 2026-04-15 | Abstracted external connector integration with a shared dynamic URL resolution base for GitHub and Loki |
| 2026-04-15 | Fixed embedded Redis startup failure on macOS ARM64 — requires `brew install openssl@3` due to dynamic link dependency in the bundled binary |
| 2026-04-15 | Replaced `@PostConstruct` with `@EventListener(ApplicationReadyEvent::class)` in `AiModelService` to prevent Redis connection attempts before embedded Redis has fully started |
| 2026-04-13 | Added Status column to Firing List — automatically extracts Exception/Error info from logs |

---

## License

This project is open source and available under the [MIT License](LICENSE).

```
MIT License

Copyright (c) 2025 Walter Hwang

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
