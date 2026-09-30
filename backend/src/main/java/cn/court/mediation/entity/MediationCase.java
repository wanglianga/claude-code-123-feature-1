package cn.court.mediation.entity;

import cn.court.mediation.enums.CaseStatus;
import cn.court.mediation.enums.CaseType;
import cn.court.mediation.enums.Channel;
import cn.court.mediation.enums.Urgency;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 调解案件主表 */
@Getter
@Setter
@Entity
@Table(name = "mediation_case", indexes = {
        @Index(name = "idx_case_no", columnList = "caseNo", unique = true),
        @Index(name = "idx_status", columnList = "status")
})
public class MediationCase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 案号样式编号，如 TZ20260926001 */
    @Column(nullable = false, length = 30)
    private String caseNo;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private CaseType caseType;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private CaseStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Urgency urgency = Urgency.NORMAL;

    // ---------- 当事人信息 ----------
    @Column(length = 50)
    private String applicantName;
    @Column(length = 20)
    private String applicantPhone;
    /** 申请人若为平台注册用户，关联其账号 */
    private Long applicantUserId;

    @Column(length = 50)
    private String respondentName;
    @Column(length = 20)
    private String respondentPhone;
    @Column(length = 200)
    private String respondentAddress;
    /** 是否外地当事人 */
    private Boolean respondentOutOfTown = false;

    // ---------- 诉求 ----------
    /** 诉求金额（元） */
    @Column(precision = 14, scale = 2)
    private BigDecimal claimAmount;

    @Column(length = 1000)
    private String claimSummary;

    /** 证据清单，文本条目换行分隔 */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String evidenceList;

    /** 是否愿意线上调解 */
    private Boolean onlineWilling = true;

    // ---------- 分流与承办 ----------
    private Long windowUserId;
    @Column(length = 500)
    private String triageOpinion;
    private LocalDateTime triagedAt;

    private Long mediatorId;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Channel channel;

    /** 改期次数 */
    private Integer rescheduleCount = 0;

    // ---------- 失联 / 缺材料 标记 ----------
    private Boolean respondentMissing = false;
    private LocalDateTime missingAt;

    /** 累计联系失败次数（电话/短信/地址核验/送达尝试失败均计入，联系上则归零） */
    private Integer failedContactCount = 0;
    /** 达到失联预警阈值后，已提醒窗口核验送达地址 */
    private Boolean missingThresholdAlerted = false;
    /** 窗口是否已核验被申请人送达地址 */
    private Boolean addressVerified = false;
    private LocalDateTime addressVerifiedAt;
    @Column(length = 500)
    private String addressVerifyNote;
    /** 窗口是否已决定转为线下送达 */
    private Boolean offlineDelivery = false;
    private LocalDateTime offlineDeliveryAt;
    @Column(length = 500)
    private String offlineDeliveryNote;
    /** 窗口是否已建议转立案（失联处置建议，区别于正式生成材料包） */
    private Boolean transferSuggested = false;
    private LocalDateTime transferSuggestedAt;
    @Column(length = 500)
    private String transferSuggestNote;

    /** 证据材料是否齐备（窗口分流时核验） */
    private Boolean evidenceComplete = true;
    @Column(length = 500)
    private String evidenceGapNote;

    // ---------- 转立案 ----------
    /** 是否申请/决定转立案 */
    private Boolean transferRequested = false;
    @Column(length = 500)
    private String transferReason;
    private LocalDateTime transferredAt;
    /** 转入诉讼后的立案案号 */
    @Column(length = 30)
    private String litigationCaseNo;

    // ---------- 回访 ----------
    private LocalDate lastFollowUpDate;
    @Column(length = 500)
    private String lastFollowUpResult;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
