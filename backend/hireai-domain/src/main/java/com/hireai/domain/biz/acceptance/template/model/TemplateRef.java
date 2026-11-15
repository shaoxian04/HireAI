package com.hireai.domain.biz.acceptance.template.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.util.regex.Pattern;

/**
 * Names one Template version, written {@code key@version} (for example {@code text_basic@1}).
 * Contracts and Offers refer to Templates only through this reference.
 */
public record TemplateRef(String key, int version) {

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public TemplateRef {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Template key must be lowercase letters, digits and underscores: " + key);
        }
        if (version < 1) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Template version must be at least 1");
        }
    }

    @Override
    public String toString() {
        return key + "@" + version;
    }
}
