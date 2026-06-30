// RulingInfo.java
package com.hireai.application.biz.adjudication.port;

import com.hireai.domain.biz.adjudication.enums.RulingCategory;

/** The raw ruling an arbitrator produces: only a category + rationale (Inv #3 — no money). */
public record RulingInfo(RulingCategory category, String rationale) {}
