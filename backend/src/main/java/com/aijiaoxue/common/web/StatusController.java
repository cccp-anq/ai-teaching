package com.aijiaoxue.common.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
    @GetMapping("/system/status")
    public Map<String, String> status() { return Map.of("status", "UP"); }
}
