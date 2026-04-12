package com.example.blast_radius.controller;

import com.example.blast_radius.model.PrAnalysisResponse;
import com.example.blast_radius.model.PrAnalysisRequest;
import com.example.blast_radius.service.AnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/analysis")
public class AnalysisController {
    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping("/pr")
    public ResponseEntity<PrAnalysisResponse> analyzePr(@RequestBody PrAnalysisRequest request) {
        PrAnalysisResponse response = analysisService.analyze(request);
        return ResponseEntity.ok(response);
    }
}
