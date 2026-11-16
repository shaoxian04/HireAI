package com.hireai.domain.biz.task.sampling.enums;

/** What one sampling stage decided about the lot (ISO 2859-1 double sampling). */
public enum StageVerdict {
    /** Few enough defects: the lot is accepted. */
    ACCEPT,
    /** Between the acceptance and rejection numbers of the first sample: draw the second sample. */
    SECOND_SAMPLE,
    /** Too many defects: the lot fails this round. */
    REJECT
}
