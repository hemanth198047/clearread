package com.clearread.identity;

import java.util.UUID;

public record UserRecord(UUID id, String idpSubject, String email, String displayName) {
}
