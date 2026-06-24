package com.hireai.domain.biz.user.service.impl;

import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.service.OAuthAccountLinkingDomainService;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.util.Optional;

/** Enforces the no-silent-link invariant. See {@link OAuthAccountLinkingDomainService}. */
public class OAuthAccountLinkingDomainServiceImpl implements OAuthAccountLinkingDomainService {

    @Override
    public void assertNoLocalAccountForEmail(Optional<UserModel> existingByEmail, String provider) {
        if (existingByEmail.isPresent()) {
            throw new DomainException(ResultCode.EMAIL_ALREADY_REGISTERED,
                    "An account with this email already exists. Sign in with your password to link "
                            + provider + ".");
        }
    }
}
