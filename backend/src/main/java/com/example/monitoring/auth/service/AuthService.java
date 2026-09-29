package com.example.monitoring.auth.service;

public interface AuthService {

    AuthPrincipal authenticate(String accessToken);
}
