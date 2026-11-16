package com.hireai.domain.biz.task.sampling.model;

import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pro-rata settlement by extrapolation (spec D19): after a failed resample, each passing Item that
 * was not verified one by one is paid its price × (1 − the measured error rate); the rest of its price
 * is refunded. The error rate is the random sample's defects over its size, at 4 decimal places.
 */
public final class ProRata {

    public static final int RATE_SCALE = 4;

    private ProRata() {
    }

    public static BigDecimal errorRate(int defects, int sampled) {
        if (sampled <= 0 || defects < 0 || defects > sampled) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "An error rate needs 0 <= defects <= sampled > 0; got " + defects + "/" + sampled);
        }
        return BigDecimal.valueOf(defects).divide(BigDecimal.valueOf(sampled), RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** What one passing Item is paid (gross, before commission), half-up to 2dp. */
    public static Money paidFor(Money price, BigDecimal errorRate) {
        if (errorRate.signum() < 0 || errorRate.compareTo(BigDecimal.ONE) > 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "An error rate is between 0 and 1; got " + errorRate);
        }
        return price.percentage(BigDecimal.ONE.subtract(errorRate));
    }
}
