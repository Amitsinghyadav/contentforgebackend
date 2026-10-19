package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.GenerationUsageEntity;
import com.ozo.contentforge.repository.GenerationUsageRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/usage")
@CrossOrigin
public class UsageController {

    private final GenerationUsageRepository usageRepository;

    public UsageController(GenerationUsageRepository usageRepository) {
        this.usageRepository = usageRepository;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getUsageReport(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDate startDate = (from != null) ? from : LocalDate.now().minusDays(30);
        LocalDate endDate = (to != null) ? to : LocalDate.now();

        LocalDateTime fromTime = startDate.atStartOfDay();
        LocalDateTime toTime = endDate.atTime(LocalTime.MAX);

        List<GenerationUsageEntity> records = usageRepository.findByDateRange(fromTime, toTime);

        double totalCost = 0.0;
        Map<String, Double> costByProvider = new HashMap<>();
        Map<String, Double> unitsByProvider = new HashMap<>();

        for (GenerationUsageEntity r : records) {
            double cost = r.getActualCost() != null ? r.getActualCost() : (r.getEstimatedCost() != null ? r.getEstimatedCost() : 0.0);
            totalCost += cost;
            costByProvider.put(r.getProvider(), costByProvider.getOrDefault(r.getProvider(), 0.0) + cost);
            unitsByProvider.put(r.getProvider(), unitsByProvider.getOrDefault(r.getProvider(), 0.0) + (r.getUnits() != null ? r.getUnits() : 0.0));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("from", startDate);
        result.put("to", endDate);
        result.put("totalRecords", records.size());
        result.put("totalCostUsd", Math.round(totalCost * 100.0) / 100.0);
        result.put("costByProvider", costByProvider);
        result.put("unitsByProvider", unitsByProvider);
        result.put("records", records);

        return ResponseEntity.ok(result);
    }
}
