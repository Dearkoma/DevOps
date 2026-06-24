package com.devops.platform.controller;
import com.devops.platform.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {
    private final DashboardService dashboardService;
    @GetMapping("/stats")
    public Map<String, Object> getStats() { return dashboardService.getDashboardStats(); }
    @GetMapping("/recent-builds")
    public List<Map<String, Object>> getRecentBuilds(
            @RequestParam(defaultValue = "10") int limit) {
        return dashboardService.getRecentBuilds(limit);
    }

    @GetMapping("/trends")
    public List<Map<String, Object>> getBuildTrends() {
        return dashboardService.getBuildTrends();
    }
}
