package com.hireai.controller.biz.adjudication.dto;

import java.math.BigDecimal;

/** How one dispute fee was borne: {@code fullCost = clientCharged + builderCharged + platformCovered}. */
public record DisputeFeeDTO(String type, BigDecimal fullCost, BigDecimal cappedAmount,
                            BigDecimal clientCharged, BigDecimal builderCharged, BigDecimal platformCovered) {}
