package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.service.PlanAdminService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/v1/settings")
public class SettingsController {

    private final PlanAdminService planAdminService;

    public SettingsController(PlanAdminService planAdminService) { this.planAdminService = planAdminService; }

    public record DefaultPlanRequest(String planKey) {}

    @PutMapping("/default-plan")
    public void designateDefault(@RequestBody DefaultPlanRequest request) {
        planAdminService.designateDefault(request.planKey());
    }
}
