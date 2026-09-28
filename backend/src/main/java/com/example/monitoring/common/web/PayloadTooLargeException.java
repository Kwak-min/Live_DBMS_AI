package com.example.monitoring.common.web;

import java.io.IOException;

public class PayloadTooLargeException extends IOException {
    public PayloadTooLargeException() { super("Request body exceeds the configured limit"); }
}
