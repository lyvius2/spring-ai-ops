package com.walter.spring.ai.ops.record;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Administrator(
    String username,
    String password,
    Instant createdAt,
    Instant lastLoginAt,
    Boolean passwordChangeRequired
) { }