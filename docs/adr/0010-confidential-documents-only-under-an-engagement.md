---
status: accepted
---

# Confidential input documents are allowed only when the buyer chose the Agent

**Status:** decided, not built. This partially supersedes [ADR 0005](./0005-public-documents-first-and-the-self-hosted-agent-ceiling.md).

ADR 0005 launched on public documents only. Its reason was that a self-hosted Agent is outside the
platform's control, and HireAI would be sending a client's confidential file to a stranger the client
never chose.

That premise does not hold for an **Engagement**. In an Engagement, a buyer such as an AP team picks its
own Agent, as it would any outsourcing vendor. It also consents, in the Contract, to the documents
going there, and it settles confidentiality with that Builder the way it would with any vendor.

So confidential documents are allowed **only under an Engagement**. For those documents the platform
provides:

- private, upload-only storage, frozen as a SourceSnapshot;
- per-Item signed download links that expire at the execution deadline, with every download logged;
- retention until every dispute and appeal window has closed plus a set period, after which only the
  content hash is kept.

The marketplace paths, routed submit and the web shortlist, keep ADR 0005's rule: the platform or a
ranking picked the Agent, not the client, so they take public documents only.

URL-fetched input is deferred because of its security surface.
