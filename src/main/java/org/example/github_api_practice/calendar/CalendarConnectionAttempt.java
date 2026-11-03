package org.example.github_api_practice.calendar;

import org.springframework.security.core.Authentication;

import java.io.Serializable;

record CalendarConnectionAttempt(String state, Authentication originalAuthentication) implements Serializable {
    static final String SESSION_ATTRIBUTE = CalendarConnectionAttempt.class.getName();
}
