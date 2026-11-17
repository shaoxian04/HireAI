package com.hireai.domain.biz.acceptance.source.enums;

/** What a signed-link download attempt got (D31). */
public enum SourceDownloadOutcome {
    /** The bytes were served. */
    SERVED,
    /** The link's execution deadline had passed: refused. */
    EXPIRED,
    /** Retention had deleted the content: refused, only the hash is left. */
    RETIRED
}
