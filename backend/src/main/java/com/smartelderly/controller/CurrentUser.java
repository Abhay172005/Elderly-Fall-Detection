package com.smartelderly.controller;

import org.springframework.security.oauth2.jwt.Jwt;

/** Display name of the authenticated caregiver, recorded on acknowledgements and resolutions. */
final class CurrentUser {
    private CurrentUser() {}

    static String name(Jwt jwt) {
        if (jwt == null) return "system";
        String name = jwt.getClaimAsString("name");
        return name != null && !name.isBlank() ? name : jwt.getClaimAsString("email");
    }
}
