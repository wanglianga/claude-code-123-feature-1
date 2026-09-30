package cn.court.mediation.service;

import cn.court.mediation.common.BizException;
import cn.court.mediation.entity.ContactAttempt;
import cn.court.mediation.entity.MediationCase;
import cn.court.mediation.entity.User;
import cn.court.mediation.enums.Role;
import cn.court.mediation.repository.CaseRepository;
import cn.court.mediation.repository.ContactAttemptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 被申请人失联处置：
 * 1. 调解员多次联系失败时，逐条登记电话、短信、地址核验、送达尝试，全部写入案件时间线；
 * 2. 联系失败累计达到阈值（默认 3 次），平台自动提醒立案窗口核验送达地址；
 * 3. 窗口可补充联系方式（开启新一轮联系）、改为线下送达、建议转立案；
 * 4. 建议/正式转立案时，前期全部联系尝试证据自动汇编进材料包，逐条标明联系结果。
 */
@Service
public class MissingHandleService {

    /** 联系失败次数阈值：达到后提醒窗口核验送达地址 */
    public static final int FAIL_THRESHOLD = 3;

    /** 计为"联系成功"的结果：联系上 / 已送达签收 */
    private static final List<String> SUCCESS_RESULTS = List.of("CONNECTED", "DELIVERED");

    private final ContactAttemptRepository attemptRepository;
    private final CaseRepository caseRepository;
    private final TimelineService timelineService;
    private final TransferService transferService;

    public MissingHandleService(ContactAttemptRepository attemptRepository, CaseRepository caseRepository,
                                TimelineService timelineService, TransferService transferService) {
        this.attemptRepository = attemptRepository;
        this.caseRepository = caseRepository;
        this.timelineService = timelineService;
        this.transferService = transferService;
    }

    /** 登记一次联系尝试（电话/短信/地址核验/送达尝试） */
    @Transactional
    public Map<String, Object> logAttempt(Long caseId, Map<String, Object> body, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        String type = (String) body.getOrDefault("attemptType", "PHONE");
        String result = (String) body.getOrDefault("result", "NO_ANSWER");

        ContactAttempt a = new ContactAttempt();
        a.setCaseId(caseId);
        a.setAttemptType(type);
        a.setChannel((String) body.getOrDefault("channel", defaultChannel(type)));
        a.setTarget(deriveTarget(c, type, body));
        a.setResult(result);
        a.setNote((String) body.getOrDefault("note", ""));
        a.setOperatorId(operator.getId());
        a.setOperatorName(operator.getRealName());
        a.setOperatorRole(operator.getRole().name());
        if (body.get("attemptTime") != null && !body.get("attemptTime").toString().isBlank()) {
            a.setAttemptTime(LocalDateTime.parse(body.get("attemptTime").toString().replace(' ', 'T')));
        } else {
            a.setAttemptTime(LocalDateTime.now());
        }
        attemptRepository.save(a);

        boolean success = SUCCESS_RESULTS.contains(result);
        timelineService.record(caseId, "CONTACT",
                attemptTypeLabel(type) + "联系尝试（" + resultLabel(result) + "）",
                buildAttemptDetail(a), operator);

        boolean thresholdReached = false;
        boolean recovered = false;
        if (success) {
            // 联系成功：若此前处于失联，自动恢复联系，关闭窗口地址核验提醒
            recovered = Boolean.TRUE.equals(c.getRespondentMissing());
            if (recovered) {
                c.setRespondentMissing(false);
                c.setMissingAt(null);
                c.setAddressVerifyRemindAt(null);
                c.setMissingTransferSuggested(false);
                // 恢复联系作为新一轮联系的计数起点
                c.setMissingHandledAt(LocalDateTime.now());
                timelineService.record(caseId, "MISSING", "恢复与被申请人联系",
                        attemptTypeLabel(type) + "联系成功（" + resultLabel(result) + "），被申请人恢复联系，失联预警解除。",
                        operator);
            }
        } else {
            // 失败：达到阈值且尚未提醒过窗口时，自动置失联并推送"核验送达地址"提醒（仅一次）
            long failed = countRoundFailures(caseId, c.getMissingHandledAt());
            if (failed >= FAIL_THRESHOLD) {
                thresholdReached = true;
                if (!Boolean.TRUE.equals(c.getRespondentMissing())) {
                    c.setRespondentMissing(true);
                    c.setMissingAt(LocalDateTime.now());
                    timelineService.record(caseId, "MISSING", "被申请人失联登记",
                            "经多种方式连续联系失败（本轮已达" + failed + "次），系统自动登记被申请人失联。", operator);
                }
                if (c.getAddressVerifyRemindAt() == null) {
                    c.setAddressVerifyRemindAt(LocalDateTime.now());
                    timelineService.record(caseId, "MISSING_HANDLE", "失联处置预警：请窗口核验送达地址",
                            "被申请人连续" + failed + "次联系失败（电话/短信/地址核验/送达），已达阈值"
                                    + FAIL_THRESHOLD + "次。平台提醒立案窗口核验送达地址，"
                                    + "并可选择补充联系方式、改为线下送达或建议转立案。", null);
                }
            }
        }
        caseRepository.save(c);

        return Map.of(
                "attempt", a,
                "failedCount", countRoundFailures(caseId, c.getMissingHandledAt()),
                "threshold", FAIL_THRESHOLD,
                "thresholdReached", thresholdReached,
                "recovered", recovered
        );
    }

    /** 窗口补充被申请人联系方式（开启新一轮联系尝试） */
    @Transactional
    public MediationCase supplementContact(Long caseId, Map<String, Object> body, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        String phone = trim((String) body.get("phone"));
        String address = trim((String) body.get("address"));
        String source = trim((String) body.getOrDefault("source", ""));
        if (phone == null && address == null) {
            throw new BizException("请填写补充的联系电话或送达地址");
        }
        StringBuilder desc = new StringBuilder();
        if (phone != null) {
            c.setRespondentPhone(phone);
            desc.append("补充联系电话：").append(phone).append("；");
        }
        if (address != null) {
            c.setRespondentAddress(address);
            desc.append("补充/修正送达地址：").append(address).append("；");
        }
        if (source != null && !source.isBlank()) {
            desc.append("信息来源：").append(source).append("；");
        }
        // 新一轮联系从此刻重新计数，关闭上一轮地址核验提醒
        LocalDateTime now = LocalDateTime.now();
        c.setMissingHandledAt(now);
        c.setAddressVerifyRemindAt(null);
        caseRepository.save(c);

        timelineService.record(caseId, "MISSING_HANDLE", "窗口补充被申请人联系方式",
                desc + "已通知调解员据此重新联系，前期联系记录仍连续留痕，联系失败次数重新计数。", operator);
        return c;
    }

    /** 窗口核验送达地址（阈值预警后的处置） */
    @Transactional
    public MediationCase verifyAddress(Long caseId, Map<String, Object> body, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        String address = trim((String) body.get("verifiedAddress"));
        String note = trim((String) body.get("note"));
        if (address != null) {
            c.setVerifiedServiceAddress(address);
        } else if (c.getVerifiedServiceAddress() == null) {
            c.setVerifiedServiceAddress(c.getRespondentAddress());
        }
        LocalDateTime now = LocalDateTime.now();
        c.setAddressVerifiedAt(now);
        c.setAddressVerifiedBy(operator.getRealName());
        c.setAddressVerifyRemindAt(null);
        caseRepository.save(c);

        timelineService.record(caseId, "MISSING_HANDLE", "窗口核验送达地址",
                "核验人：" + operator.getRealName() + "；核验后送达地址：" + nz(c.getVerifiedServiceAddress())
                        + (note == null ? "" : "；核验情况：" + note), operator);
        return c;
    }

    /** 窗口决定改为线下送达（可同时记录送达尝试结果） */
    @Transactional
    public MediationCase switchOfflineService(Long caseId, Map<String, Object> body, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        String address = trim((String) body.get("serviceAddress"));
        String note = trim((String) body.get("note"));
        String way = trim((String) body.getOrDefault("offlineWay", "DIRECT"));
        if (address != null) {
            c.setVerifiedServiceAddress(address);
        } else if (c.getVerifiedServiceAddress() == null) {
            c.setVerifiedServiceAddress(c.getRespondentAddress());
        }
        LocalDateTime now = LocalDateTime.now();
        c.setOfflineServiceAt(now);
        c.setOfflineServiceNote((way == null ? "" : offlineWayLabel(way) + "；") + (note == null ? "" : note));
        // 改线下送达前窗口已完成地址核验
        c.setAddressVerifiedAt(now);
        c.setAddressVerifiedBy(operator.getRealName());
        c.setAddressVerifyRemindAt(null);
        c.setMissingHandledAt(now);
        caseRepository.save(c);

        timelineService.record(caseId, "MISSING_HANDLE", "窗口决定改为线下送达",
                "线下送达方式：" + offlineWayLabel(way) + "；送达地址：" + nz(c.getVerifiedServiceAddress())
                        + (note == null ? "" : "；情况：" + note)
                        + "。送达尝试与结果继续登记并随卷留痕。", operator);
        return c;
    }

    /** 窗口建议转立案：设置建议标记，并以"失联"原因自动汇编材料包（含全部联系尝试证据） */
    @Transactional
    public Map<String, Object> suggestTransfer(Long caseId, Map<String, Object> body, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        String note = trim((String) body.get("note"));
        LocalDateTime now = LocalDateTime.now();
        c.setMissingTransferSuggested(true);
        c.setMissingTransferSuggestedAt(now);
        c.setMissingTransferSuggestNote(note);
        caseRepository.save(c);

        String detail = "立案窗口评估被申请人失联情形后建议转立案"
                + (note == null ? "" : "：" + note)
                + "。前期全部电话/短信/地址核验/送达尝试证据已自动进入转立案材料包，逐条标明联系结果。";
        timelineService.record(caseId, "MISSING_HANDLE", "窗口建议转立案（失联）", detail, operator);

        // 自动汇编转立案材料包（失联原因），窗口可直接在"司法确认/转立案"页查看与接收
        Object material = transferService.prepareTransfer(caseId,
                Map.of("reasonType", "MISSING",
                        "reasonDetail", note == null ? "被申请人多次联系失败失联，窗口核验后建议转立案" : note),
                operator);
        return Map.of("case", c, "transferMaterial", material);
    }

    /** 本轮（自窗口上次处置起；未处置过则全部）联系失败次数 */
    public long countRoundFailures(Long caseId, LocalDateTime since) {
        return attemptRepository.findByCaseIdOrderByAttemptTimeAsc(caseId).stream()
                .filter(a -> since == null || a.getAttemptTime() == null || !a.getAttemptTime().isBefore(since))
                .filter(a -> !SUCCESS_RESULTS.contains(a.getResult()))
                .count();
    }

    /** 失联处置汇总（供案件详情聚合） */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(MediationCase c) {
        List<ContactAttempt> all = attemptRepository.findByCaseIdOrderByAttemptTimeDesc(c.getId());
        long roundFail = countRoundFailures(c.getId(), c.getMissingHandledAt());
        boolean remindPending = c.getAddressVerifyRemindAt() != null
                && (c.getAddressVerifiedAt() == null || c.getAddressVerifiedAt().isBefore(c.getAddressVerifyRemindAt()));
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("attempts", all);
        m.put("attemptCount", all.size());
        m.put("failedCount", all.stream().filter(a -> !SUCCESS_RESULTS.contains(a.getResult())).count());
        m.put("roundFailedCount", roundFail);
        m.put("failThreshold", FAIL_THRESHOLD);
        m.put("addressVerifyRemind", c.getAddressVerifyRemindAt() != null);
        m.put("addressVerifyRemindAt", c.getAddressVerifyRemindAt());
        m.put("addressVerifyPending", remindPending);
        return m;
    }

    private String buildAttemptDetail(ContactAttempt a) {
        StringBuilder sb = new StringBuilder();
        sb.append("方式：").append(channelLabel(a.getAttemptType(), a.getChannel()));
        if (a.getTarget() != null && !a.getTarget().isBlank()) {
            sb.append("；对象：").append(a.getTarget());
        }
        sb.append("；结果：").append(resultLabel(a.getResult()));
        if (a.getNote() != null && !a.getNote().isBlank()) {
            sb.append("；情况：").append(a.getNote());
        }
        sb.append("；登记人：").append(a.getOperatorName());
        return sb.toString();
    }

    private String deriveTarget(MediationCase c, String type, Map<String, Object> body) {
        Object t = body.get("target");
        if (t != null && !t.toString().isBlank()) {
            return t.toString();
        }
        if ("ADDRESS_CHECK".equals(type) || "SERVICE".equals(type)) {
            return c.getRespondentAddress();
        }
        return c.getRespondentPhone();
    }

    private String defaultChannel(String type) {
        return switch (type) {
            case "SMS" -> "SMS_PLATFORM";
            case "ADDRESS_CHECK" -> "DOOR_VISIT";
            case "SERVICE" -> "DIRECT";
            default -> "PHONE_CALL";
        };
    }

    public static String attemptTypeLabel(String t) {
        return switch (t) {
            case "PHONE" -> "电话";
            case "SMS" -> "短信";
            case "ADDRESS_CHECK" -> "地址核验";
            case "SERVICE" -> "送达尝试";
            default -> "联系";
        };
    }

    public static String resultLabel(String r) {
        return switch (r) {
            case "CONNECTED" -> "已联系上";
            case "DELIVERED" -> "已送达签收";
            case "NO_ANSWER" -> "无人接听/未回应";
            case "STOPPED" -> "停机/空号";
            case "REFUSED" -> "拒绝沟通/拒收";
            case "NOT_FOUND" -> "查无此人";
            case "MOVED" -> "已搬迁、地址不详";
            case "FAILED" -> "送达失败";
            default -> r;
        };
    }

    public static String channelLabel(String type, String ch) {
        if (ch == null) return attemptTypeLabel(type);
        return switch (ch) {
            case "PHONE_CALL" -> "电话拨打";
            case "SMS_PLATFORM" -> "短信平台";
            case "DOOR_VISIT" -> "现场/户籍地走访";
            case "GRID" -> "社区网格协查";
            case "DIRECT" -> "直接送达";
            case "MAIL" -> "邮寄送达";
            case "LEFT" -> "留置送达";
            case "ANNOUNCE" -> "公告送达";
            default -> ch;
        };
    }

    public static String offlineWayLabel(String w) {
        return switch (w) {
            case "MAIL" -> "邮寄送达";
            case "LEFT" -> "留置送达";
            case "ANNOUNCE" -> "公告送达";
            default -> "直接送达（上门）";
        };
    }

    private String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String nz(String s) {
        return s == null || s.isBlank() ? "（未登记）" : s;
    }

    /** 仅窗口/管理员可执行失联处置动作 */
    public static void requireWindow(User operator) {
        if (operator.getRole() != Role.WINDOW && operator.getRole() != Role.ADMIN) {
            throw new BizException("仅立案窗口可执行该失联处置操作");
        }
    }
}
