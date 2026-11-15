package com.hireai.domain.biz.task.item.info;

/** One Item as the WorkOrder carries it to the Agent: its number and its input. */
public record DispatchItem(int itemNo, String input) {
}
