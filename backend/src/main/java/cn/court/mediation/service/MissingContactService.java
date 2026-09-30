package cn.court.mediation.service;

import cn.court.mediation.common.BizException;
import cn.court.mediation.entity.ContactAttempt;
import cn.court.mediation.entity.MediationCase;
import cn.court.mediation.entity.User;
import cn.court.mediation.repository.CaseRepository;
import cn.court.mediation.repository.ContactAttemptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 被申请人失联处置：
 * 调解员多次联系失败时逐条记录电话、短信、地址核验、送达尝试，全部写入案件时间线；
 * 失败次数达到阈值后平台自动标记失联并提醒窗口核验送达地址；
 * 窗口可补充联系方式、改为线下送达、建议转立案；失联转立案时前期全部联系证据自动进入材料包。
 */
@Service
public class MissingContactService {

    /** 失联预警阈值：累计联系失败达到该次数，提醒窗口核验送达地址 */
    public static final int ALERT_THRESHOLD = 3;

    public static final Map<String, String> METHOD_LABELS = Map.of(
            "PHONE", "电话联系",
            "SMS", "短信联系",
            "ADDRESS_CHECK", "地址核验",
            "DELIVERY", "送达尝试");

    public static final Map<String, String> RESULT_LABELS = Map.ofEntries(
            Map.entry("REACHED", "已联系上"),
            Map.entry("NO_ANSWER", "无人接听"),
            Map.entry("SHUTDOWN", "停机/空号"),
            Map.entry("NO_REPLY", "短信未回复"),
            Map.entry("NOT_FOUND", "查无此人/地址不详"),
            Map.entry("REFUSED", "拒收"),
            Map.entry("DELIVERED", "已送达"),
            Map.entry("FAILED", "送达失败"));

    /** 视为“成功联系上被申请人”的结果 */
    private static final List<String> REACHED_RESULTS = List.of("REACHED", "DELIVERED");

    private final ContactAttemptRepository attemptRepository;
    private final CaseRepository caseRepository;
    private final TimelineService timelineService;

    public MissingContactService(ContactAttemptRepository attemptRepository, CaseRepository caseRepository,
                                 TimelineService timelineService) {
        this.attemptRepository = attemptRepository;
        this.caseRepository = caseRepository;
        this.timelineService = timelineService;
    }

    public static String methodLabel(String m) {
        return METHOD_LABELS.getOrDefault(m, m);
    }

    public static String resultLabel(String r) {
        return RESULT_LABELS.getOrDefault(r, r);
    }

    public static boolean isReached(String result) {
        return REACHED_RESULTS.contains(result);
    }

    /** 调解员登记一次联系尝试（电话/短信/地址核验/送达尝试），结果进入案件时间线 */
    @Transactional
    public ContactAttempt logAttempt(Long caseId, Map<String, Object> body, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        String method = str(body, "method", "PHONE");
        String result = str(body, "result", "NO_ANSWER");
        if (!METHOD_LABELS.containsKey(method)) {
            throw new BizException("联系方式无效");
        }
        if (!RESULT_LABELS.containsKey(result)) {
            throw new BizException("联系结果无效");
        }

        List<ContactAttempt> history = attemptRepository.findByCaseIdOrderByCreatedAtAsc(caseId);
        ContactAttempt a = new ContactAttempt();
        a.setCaseId(caseId);
        a.setMethod(method);
        a.setResult(result);
        a.setNote(str(body, "note", ""));
        // 默认使用案件当前登记的联系方式，允许手工改写
        String value = str(body, "contactValue", "");
        if (value.isBlank()) {
            value = switch (method) {
                case "ADDRESS_CHECK", "DELIVERY" -> c.getRespondentAddress() == null ? "" : c.getRespondentAddress();
                default -> c.getRespondentPhone() == null ? "" : c.getRespondentPhone();
            };
        }
        a.setContactValue(value);
        a.setRoundNo(history.size() + 1);
        if (operator != null) {
            a.setOperatorId(operator.getId());
            a.setOperatorName(operator.getRealName());
            a.setActorRole(operator.getRole().name());
        } else {
            a.setOperatorName("系统");
            a.setActorRole("SYSTEM");
        }
        attemptRepository.save(a);

        boolean reached = isReached(result);
        int failed = c.getFailedContactCount() == null ? 0 : c.getFailedContactCount();
        if (reached) {
            c.setFailedContactCount(0);
            c.setMissingThresholdAlerted(false);
        } else {
            c.setFailedContactCount(failed + 1);
        }
        caseRepository.save(c);

        // 每次联系尝试都写入案件时间线，标明方式与结果
        String title = "第" + a.getRoundNo() + "次联系被申请人（" + methodLabel(method) + "）";
        StringBuilder detail = new StringBuilder();
        detail.append("联系方式：").append(methodLabel(method))
                .append("；联系对象信息：").append(value.isBlank() ? "（未登记）" : value)
                .append("；联系结果：").append(resultLabel(result));
        if (!a.getNote().isBlank()) {
            detail.append("；情况说明：").append(a.getNote());
        }
        detail.append("。");
        if (!reached) {
            detail.append("累计联系失败").append(c.getFailedContactCount()).append("次。");
        }
        timelineService.record(caseId, "CONTACT", title, detail.toString(), operator);

        // 达到阈值：自动标记失联并提醒窗口核验送达地址
        if (!reached && c.getFailedContactCount() >= ALERT_THRESHOLD
                && !Boolean.TRUE.equals(c.getMissingThresholdAlerted())) {
            c.setMissingThresholdAlerted(true);
            if (!Boolean.TRUE.equals(c.getRespondentMissing())) {
                c.setRespondentMissing(true);
                c.setMissingAt(LocalDateTime.now());
            }
            caseRepository.save(c);
            timelineService.record(caseId, "MISSING", "被申请人失联预警（达阈值）",
                    "经电话、短信、地址核验、送达等多种方式累计联系失败" + c.getFailedContactCount()
                            + "次（阈值" + ALERT_THRESHOLD + "次），平台已标记被申请人失联，"
                            + "并提醒立案窗口核验送达地址、补充联系方式或改线下送达；若转立案，前期全部联系证据自动进入材料包。",
                    null);
        }
        return a;
    }

    /** 窗口补充被申请人联系方式（新电话/新地址） */
    @Transactional
    public MediationCase supplementContact(Long caseId, Map<String, Object> body, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        String phone = str(body, "phone", "").trim();
        String address = str(body, "address", "").trim();
        String note = str(body, "note", "");
        if (phone.isBlank() && address.isBlank()) {
            throw new BizException("请至少填写一项新的电话或地址");
        }
        StringBuilder sb = new StringBuilder("立案窗口补充被申请人联系方式：");
        if (!phone.isBlank()) {
            c.setRespondentPhone(phone);
            sb.append("新电话").append(phone).append("；");
        }
        if (!address.isBlank()) {
            c.setRespondentAddress(address);
            sb.append("新地址").append(address).append("；");
        }
        sb.append("后续联系尝试与送达将使用最新联系方式。");
        if (!note.isBlank()) {
            sb.append("来源说明：").append(note).append("。");
        }
        caseRepository.save(c);
        timelineService.record(caseId, "MISSING", "窗口补充被申请人联系方式", sb.toString(), operator);
        return c;
    }

    /** 窗口核验被申请人送达地址 */
    @Transactional
    public MediationCase verifyAddress(Long caseId, Map<String, Object> body, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        String address = str(body, "address", "").trim();
        String note = str(body, "note", "");
        if (!address.isBlank()) {
            c.setRespondentAddress(address);
        }
        c.setAddressVerified(true);
        c.setAddressVerifiedAt(LocalDateTime.now());
        c.setAddressVerifyNote(note);
        // 窗口已核验，本次预警处置完成
        c.setMissingThresholdAlerted(false);
        caseRepository.save(c);
        timelineService.record(caseId, "MISSING", "窗口核验被申请人送达地址",
                "经窗口核验（公安/社区/物业等渠道），送达地址确认为："
                        + (c.getRespondentAddress() == null ? "（未登记）" : c.getRespondentAddress())
                        + "。核验情况：" + (note.isBlank() ? "地址可作为线下送达依据" : note) + "。",
                operator);
        return c;
    }

    /** 窗口改为线下送达 */
    @Transactional
    public MediationCase switchOfflineDelivery(Long caseId, Map<String, Object> body, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        String note = str(body, "note", "");
        c.setOfflineDelivery(true);
        c.setOfflineDeliveryAt(LocalDateTime.now());
        c.setOfflineDeliveryNote(note);
        caseRepository.save(c);
        timelineService.record(caseId, "MISSING", "电子送达未果，改为线下送达",
                "因被申请人失联、电子送达无法完成，立案窗口决定转为线下直接送达/留置送达，送达地址："
                        + (c.getRespondentAddress() == null ? "（未登记）" : c.getRespondentAddress())
                        + "。安排说明：" + (note.isBlank() ? "已交送达人员上门" : note) + "。",
                operator);
        return c;
    }

    /** 窗口建议转立案（前期联系证据将自动汇编进材料包） */
    @Transactional
    public MediationCase suggestTransfer(Long caseId, Map<String, Object> body, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        String note = str(body, "note", "");
        c.setTransferSuggested(true);
        c.setTransferSuggestedAt(LocalDateTime.now());
        c.setTransferSuggestNote(note);
        caseRepository.save(c);
        long attempts = attemptRepository.findByCaseIdOrderByCreatedAtAsc(caseId).size();
        timelineService.record(caseId, "MISSING", "窗口建议被申请人失联转立案",
                "经电话/短信/地址核验/送达等共" + attempts + "次联系尝试仍无法联系被申请人，"
                        + "窗口建议按失联情形转立案；前期全部联系尝试记录将自动进入转立案材料包并标明每次结果。"
                        + (note.isBlank() ? "" : "说明：" + note + "。"),
                operator);
        return c;
    }

    private String str(Map<String, Object> body, String key, String def) {
        Object v = body.get(key);
        return v == null ? def : v.toString();
    }
}
