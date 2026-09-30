package cn.court.mediation.controller;

import cn.court.mediation.common.ApiResponse;
import cn.court.mediation.entity.ContactAttempt;
import cn.court.mediation.entity.MediationCase;
import cn.court.mediation.security.CurrentUser;
import cn.court.mediation.service.MissingContactService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 被申请人失联处置：
 * 调解员登记电话/短信/地址核验/送达尝试；
 * 窗口补充联系方式、核验送达地址、改线下送达、建议转立案。
 * 所有尝试写入案件时间线，转立案时随材料包移交。
 */
@RestController
@RequestMapping("/api/cases/{caseId}")
public class MissingContactController {

    private final MissingContactService missingContactService;

    public MissingContactController(MissingContactService missingContactService) {
        this.missingContactService = missingContactService;
    }

    /** 调解员登记一次联系尝试（PHONE/SMS/ADDRESS_CHECK/DELIVERY） */
    @PostMapping("/contact-attempts")
    public ApiResponse<ContactAttempt> logAttempt(@PathVariable Long caseId, @RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        return ApiResponse.ok(missingContactService.logAttempt(caseId, body, CurrentUser.get(request)));
    }

    /** 窗口补充被申请人联系方式（电话/地址） */
    @PostMapping("/contact-supplement")
    public ApiResponse<MediationCase> supplement(@PathVariable Long caseId, @RequestBody Map<String, Object> body,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(missingContactService.supplementContact(caseId, body, CurrentUser.get(request)));
    }

    /** 窗口核验被申请人送达地址 */
    @PostMapping("/address-verify")
    public ApiResponse<MediationCase> verifyAddress(@PathVariable Long caseId, @RequestBody Map<String, Object> body,
                                                    HttpServletRequest request) {
        return ApiResponse.ok(missingContactService.verifyAddress(caseId, body, CurrentUser.get(request)));
    }

    /** 窗口改为线下送达 */
    @PostMapping("/offline-delivery")
    public ApiResponse<MediationCase> offlineDelivery(@PathVariable Long caseId, @RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        return ApiResponse.ok(missingContactService.switchOfflineDelivery(caseId, body, CurrentUser.get(request)));
    }

    /** 窗口建议失联转立案 */
    @PostMapping("/suggest-transfer")
    public ApiResponse<MediationCase> suggestTransfer(@PathVariable Long caseId, @RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        return ApiResponse.ok(missingContactService.suggestTransfer(caseId, body, CurrentUser.get(request)));
    }
}
