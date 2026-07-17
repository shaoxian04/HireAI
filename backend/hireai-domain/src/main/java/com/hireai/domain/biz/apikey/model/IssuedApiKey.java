package com.hireai.domain.biz.apikey.model;

/** The result of issuing a key: the persistable model plus the raw key, shown to the user ONCE. */
public record IssuedApiKey(ApiKeyModel model, String rawKey) {}
