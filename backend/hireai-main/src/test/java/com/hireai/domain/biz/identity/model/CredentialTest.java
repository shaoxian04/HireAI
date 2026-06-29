package com.hireai.domain.biz.identity.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialTest {

    @Test
    void ofHashWrapsAnExistingHash() {
        Credential c = Credential.ofHash("$2a$bcrypt");
        assertThat(c.secretHash()).isEqualTo("$2a$bcrypt");
        assertThat(c.isAbsent()).isFalse();
    }

    @Test
    void ofHashNullIsTheNoneCredential() {
        assertThat(Credential.ofHash(null)).isEqualTo(Credential.NONE);
        assertThat(Credential.ofHash(null).isAbsent()).isTrue();
    }

    @Test
    void noneHasNoSecretAndIsAbsent() {
        assertThat(Credential.NONE.secretHash()).isNull();
        assertThat(Credential.NONE.isAbsent()).isTrue();
    }
}
