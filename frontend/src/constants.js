// 枚举标签（与后端 enums 保持一致）
export const CASE_TYPES = {
  LOAN: '民间借贷纠纷',
  PROPERTY_FEE: '物业费纠纷',
  TRAFFIC: '交通事故纠纷',
  NEIGHBOR: '邻里纠纷',
  SALES: '小额买卖合同纠纷'
}

export const CASE_STATUS = {
  PENDING_TRIAGE: '待分流',
  TRIAGED: '已分流待排期',
  SCHEDULED: '已排期待调解',
  MEDIATING: '调解中',
  MEDIATION_SUCCESS: '调解成功',
  MEDIATION_FAILED: '调解失败',
  JUDICIAL_CONFIRMING: '司法确认审查中',
  PERFORMING: '履行中',
  PERFORMED_DONE: '履行完毕',
  OVERDUE: '履行逾期',
  TRANSFERRED: '已转立案',
  CLOSED: '已归档'
}

export const STATUS_TAG = {
  PENDING_TRIAGE: 'danger',
  TRIAGED: 'warning',
  SCHEDULED: 'primary',
  MEDIATING: 'primary',
  MEDIATION_SUCCESS: 'success',
  MEDIATION_FAILED: 'info',
  JUDICIAL_CONFIRMING: 'warning',
  PERFORMING: '',
  PERFORMED_DONE: 'success',
  OVERDUE: 'danger',
  TRANSFERRED: 'info',
  CLOSED: 'info'
}

export const URGENCY = { NORMAL: '普通', URGENT: '加急', EMERGENCY: '特急' }
export const URGENCY_TAG = { NORMAL: 'info', URGENT: 'warning', EMERGENCY: 'danger' }
export const CHANNEL = { ONLINE: '线上调解', OFFLINE: '线下调解' }

export const RISK_LABELS = {
  RESPONDENT_MISSING: '被申请人失联',
  MISSING_ALERT: '失联达阈值·待窗口核验地址',
  EVIDENCE_MISSING: '证据材料缺失',
  REPEAT_RESCHEDULE: '多次改期',
  INSTALLMENT: '分期履行',
  OVERDUE: '付款逾期/部分履行',
  TRANSFER_REQUESTED: '已申请转立案'
}

export const RISK_TAG = {
  RESPONDENT_MISSING: 'danger',
  MISSING_ALERT: 'danger',
  EVIDENCE_MISSING: 'danger',
  REPEAT_RESCHEDULE: 'warning',
  INSTALLMENT: 'info',
  OVERDUE: 'danger',
  TRANSFER_REQUESTED: 'warning'
}

// 失联处置：联系尝试方式
export const CONTACT_METHODS = {
  PHONE: '电话联系',
  SMS: '短信联系',
  ADDRESS_CHECK: '地址核验',
  DELIVERY: '送达尝试'
}

// 失联处置：联系结果
export const CONTACT_RESULTS = {
  REACHED: '已联系上',
  NO_ANSWER: '无人接听',
  SHUTDOWN: '停机/空号',
  NO_REPLY: '短信未回复',
  NOT_FOUND: '查无此人/地址不详',
  REFUSED: '拒收',
  DELIVERED: '已送达',
  FAILED: '送达失败'
}

// 每种方式默认的失败结果
export const CONTACT_DEFAULT_RESULT = {
  PHONE: 'NO_ANSWER',
  SMS: 'NO_REPLY',
  ADDRESS_CHECK: 'NOT_FOUND',
  DELIVERY: 'FAILED'
}

export const CONTACT_RESULT_TAG = {
  REACHED: 'success',
  DELIVERED: 'success',
  NO_ANSWER: 'warning',
  NO_REPLY: 'warning',
  SHUTDOWN: 'danger',
  NOT_FOUND: 'danger',
  REFUSED: 'danger',
  FAILED: 'danger'
}

export const PLAN_STATUS = {
  PENDING: { label: '待履行', tag: 'info' },
  PAID: { label: '已履行', tag: 'success' },
  PARTIAL: { label: '部分履行', tag: 'warning' },
  OVERDUE: { label: '已逾期', tag: 'danger' }
}

export const TRANSFER_REASONS = {
  MISSING: '被申请人失联，调解无法进行',
  EVIDENCE: '证据材料争议较大，需诉讼查明',
  REJECT: '一方明确拒绝调解',
  OVERDUE: '协议达成后付款逾期/拒不履行',
  OTHER: '其他不适宜继续调解情形'
}

export const FOLLOW_RESULTS = {
  NORMAL: '履约正常', PARTIAL: '部分履行', OVERDUE: '仍处逾期', DONE: '已履行完毕', OBJECTION: '有异议'
}

export const hasRole = (user, ...roles) => roles.includes(user?.role)
