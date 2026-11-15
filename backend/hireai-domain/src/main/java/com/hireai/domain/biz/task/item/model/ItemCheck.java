package com.hireai.domain.biz.task.item.model;

/** One Acceptance Check run against an Item's result: the rule, whether it passed, and why not. */
public record ItemCheck(String rule, boolean passed, String detail) {
}
