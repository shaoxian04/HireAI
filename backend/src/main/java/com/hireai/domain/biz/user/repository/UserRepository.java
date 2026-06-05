package com.hireai.domain.biz.user.repository;

import com.hireai.domain.biz.user.model.UserModel;

import java.util.Optional;

/**
 * Persistence contract for the User read aggregate. One repository per aggregate root; the
 * interface lives in the domain layer with no framework imports. Lookup by email backs login;
 * an empty Optional is the single "no such user" signal (the app service maps both unknown-email
 * and wrong-password to one generic 401, so existence is never leaked).
 */
public interface UserRepository {

    Optional<UserModel> findByEmail(String email);
}
