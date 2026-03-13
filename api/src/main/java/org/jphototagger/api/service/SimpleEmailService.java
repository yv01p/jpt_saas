package org.jphototagger.api.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URISyntaxException;

@Service
@Profile("!dev & !test")
public class SimpleEmailService implements EmailService {

    @Autowired private JavaMailSender mailSender;
    @Value("${app.base-url}") private String baseUrl;
    @Value("${app.email.from}") private String fromAddress;

    @PostConstruct
    void validateBaseUrl() {
        try {
            var uri = new URI(baseUrl);
            if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
                throw new IllegalStateException("app.base-url must use http or https scheme");
            }
        } catch (URISyntaxException e) {
            throw new IllegalStateException("app.base-url is not a valid URI: " + baseUrl, e);
        }
    }

    @Override
    public void sendVerificationEmail(String to, String token) {
        var msg = new SimpleMailMessage();
        msg.setFrom(fromAddress);
        msg.setTo(to);
        msg.setSubject("Verify your email");
        msg.setText(UriComponentsBuilder.fromUriString(baseUrl)
            .path("/auth/verify")
            .queryParam("token", token)
            .toUriString());
        mailSender.send(msg);
    }

    @Override
    public void sendPasswordResetEmail(String to, String token) {
        var msg = new SimpleMailMessage();
        msg.setFrom(fromAddress);
        msg.setTo(to);
        msg.setSubject("Reset your password");
        msg.setText(UriComponentsBuilder.fromUriString(baseUrl)
            .path("/auth/reset-password")
            .queryParam("token", token)
            .toUriString());
        mailSender.send(msg);
    }
}
