package cn.court.mediation.controller;

import cn.court.mediation.common.ApiResponse;
import cn.court.mediation.entity.ContactAttempt;
import cn.court.mediation.entity.MediationCase;
import cn.court.mediation.security.CurrentUser;
import cn.court.mediation.service.MissingHandleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 被申请人失联处置：
 * 调解员登记电话/短信/地址核验/送达尝试；达阈值后窗口核验地址、
 * 补充联系方式、改线下送达或建议转立案；全过程写入时间线并随卷汇编。
 */
@RestController
@RequestMapping("/api/cases/{caseId}")
public class MissingHandleController {

    private final MissingHandleService missingHandleService;

    public MissingHandleController(MissingHandleService missingHandleService) {
        this.missingHandleService = missingHandleService;
    }

    /** 登记一次联系尝试（调解员/窗口），返回本轮失败次数与是否达到阈值 */
    @PostMapping("/contact-attempts")
    public ApiResponse<Map<String, Object>> logAttempt(@PathVariable Long caseId,
                                                       @RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        return ApiResponse.ok(missingHandleService.logAttempt(caseId, body, CurrentUser.get(request)));
    }

    /** 窗口补充被申请人联系方式（开启新一轮联系） */
    @PostMapping("/missing/contact")
    public ApiResponse<MediationCase> supplementContact(@PathVariable Long caseId,
                                                        @RequestBody Map<String, Object> body,
                                                        HttpServletRequest request) {
        MissingHandleService.requireWindow(CurrentUser.get(request));
        return ApiResponse.ok(missingHandleService.supplementContact(caseId, body, CurrentUser.get(request)));
    }

    /** 窗口核验送达地址 */
    @PostMapping("/missing/verify-address")
    public ApiResponse<MediationCase> verifyAddress(@PathVariable Long caseId,
                                                    @RequestBody Map<String, Object> body,
                                                    HttpServletRequest request) {
        MissingHandleService.requireWindow(CurrentUser.get(request));
        return ApiResponse.ok(missingHandleService.verifyAddress(caseId, body, CurrentUser.get(request)));
    }

    /** 窗口改为线下送达 */
    @PostMapping("/missing/offline-service")
    public ApiResponse<MediationCase> offlineService(@PathVariable Long caseId,
                                                     @RequestBody Map<String, Object> body,
                                                     HttpServletRequest request) {
        MissingHandleService.requireWindow(CurrentUser.get(request));
        return ApiResponse.ok(missingHandleService.switchOfflineService(caseId, body, CurrentUser.get(request)));
    }

    /** 窗口建议转立案：自动汇编含全部联系尝试证据的材料包 */
    @PostMapping("/missing/suggest-transfer")
    public ApiResponse<Map<String, Object>> suggestTransfer(@PathVariable Long caseId,
                                                            @RequestBody Map<String, Object> body,
                                                            HttpServletRequest request) {
        MissingHandleService.requireWindow(CurrentUser.get(request));
        return ApiResponse.ok(missingHandleService.suggestTransfer(caseId, body, CurrentUser.get(request)));
    }
}
