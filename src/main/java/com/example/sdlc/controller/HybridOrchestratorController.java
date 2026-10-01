package com.example.sdlc.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v3/sdlc")
public class HybridOrchestratorController {
    @PostMapping("/jobs")
    public ResponseEntity<Void> triggerSdlcJob() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}