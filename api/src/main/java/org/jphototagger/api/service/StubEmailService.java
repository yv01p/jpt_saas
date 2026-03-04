package org.jphototagger.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile({"dev", "test"})
public class StubEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(StubEmailService.class);

    @Override
    public void sendVerificationEmail(String to, String token) {
        log.info("STUB: Verification email to={} token={}", to, token);
    }

    @Override
    public void sendPasswordResetEmail(String to, String token) {
        log.info("STUB: Password reset email to={} token={}", to, token);
    }
}
