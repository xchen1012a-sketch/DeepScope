---
title: FAQ
description: Frequently asked questions about DeepScope Java 2.0
zh_link: /v2/zh/docs/others/faq
---

<Accordion title="Is DeepScope Java 2.0 compatible with 1.0?">

DeepScope Java 2.0 aims to preserve compatibility with 1.x where possible so that most users can upgrade smoothly. That said, 2.0 does introduce API-level breaking changes — including a redesigned agent abstraction and the new event system, permission system, and middleware stack. See the [V1 Migration Guide](/v2/en/docs/change-log) for details.

    For new projects we recommend adopting 2.0 directly to benefit from the new capabilities; the 1.0 docs remain available for existing users.

</Accordion>



<Accordion title="Is there a frontend that ships with DeepScope Java 2.0?">

Yes. The repository includes the `deepscope-admin` module, an out-of-the-box web app that speaks the same protocol as `ReActAgent`. It works without any custom UI code and integrates cleanly with the event system (`AgentEvent`) and the HITL flow of the permission system.

</Accordion>



<Accordion title="Will 2.0 ship RAG and long-term memory?">

Yes. `io.deepscope.core.rag` and `io.deepscope.core.memory.LongTermMemory` already exist in the repo, but knowledge bases, document readers and similar components are still being completed — track progress in the [Release Notes](/v2/en/docs/others/release-notes) and on GitHub releases.

</Accordion>



<Accordion title="Is there a non-Java edition?">

Yes. DeepScope ships in three independent language editions, each in its own repository:

    - **Java** — [`xchen1012a-sketch/DeepScope`](https://github.com/xchen1012a-sketch/DeepScope) (this docs site)
    - **Python** — [`deepscope-ai/deepscope`](https://github.com/deepscope-ai/deepscope)
    - **TypeScript** — [`deepscope-ai/deepscope-typescript`](https://github.com/deepscope-ai/deepscope-typescript)

</Accordion>
