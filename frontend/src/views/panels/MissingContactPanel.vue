<template>
  <div>
    <!-- 失联状态总览 -->
    <el-card shadow="never" class="mb">
      <template #header>
        <div class="head">
          <b>被申请人失联处置</b>
          <div>
            <el-tag :type="c.respondentMissing ? 'danger' : 'success'" size="small" class="ml">
              {{ c.respondentMissing ? '失联中' : '联系正常' }}
            </el-tag>
            <el-tag :type="failedCount >= 3 ? 'danger' : failedCount > 0 ? 'warning' : 'info'" size="small" class="ml">
              累计联系失败 {{ failedCount }} 次（阈值 3 次）
            </el-tag>
          </div>
        </div>
      </template>

      <el-alert v-if="c.missingThresholdAlerted" type="error" :closable="false" show-icon class="mb"
                title="失联处置预警：调解员多次联系失败已达 3 次，请立案窗口核验被申请人送达地址"
                description="可补充联系方式、改为线下送达，或建议转立案；若转立案，前期全部电话/短信/地址核验/送达尝试证据将自动进入材料包并标明每次结果。" />

      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="被申请人">{{ c.respondentName }}</el-descriptions-item>
        <el-descriptions-item label="登记电话">{{ c.respondentPhone || '（未登记）' }}</el-descriptions-item>
        <el-descriptions-item label="登记地址" :span="2">{{ c.respondentAddress || '（未登记）' }}</el-descriptions-item>
        <el-descriptions-item label="窗口地址核验">
          <el-tag size="small" :type="c.addressVerified ? 'success' : 'info'">
            {{ c.addressVerified ? '已核验 ' + fmt(c.addressVerifiedAt) : '未核验' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="线下送达">
          <el-tag size="small" :type="c.offlineDelivery ? 'warning' : 'info'">
            {{ c.offlineDelivery ? '已安排 ' + fmt(c.offlineDeliveryAt) : '未安排' }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>

      <div v-if="c.addressVerifyNote || c.offlineDeliveryNote || c.transferSuggestNote" class="notes">
        <p v-if="c.addressVerifyNote"><b>地址核验情况：</b>{{ c.addressVerifyNote }}</p>
        <p v-if="c.offlineDeliveryNote"><b>线下送达安排：</b>{{ c.offlineDeliveryNote }}</p>
        <p v-if="c.transferSuggestNote"><b>建议转立案说明：</b>{{ c.transferSuggestNote }}</p>
      </div>

      <div class="quick">
        <el-button v-if="canMediate" size="small" type="primary" @click="openAttempt">
          <el-icon><Phone /></el-icon>登记联系尝试
        </el-button>
        <template v-if="canWindow">
          <el-button size="small" @click="openDialog('supplement')"><el-icon><CirclePlus /></el-icon>补充联系方式</el-button>
          <el-button size="small" type="warning" @click="openDialog('address')">
            <el-icon><Location /></el-icon>{{ c.addressVerified ? '重新核验送达地址' : '核验送达地址' }}
          </el-button>
          <el-button size="small" type="warning" @click="openDialog('offline')">
            <el-icon><Van /></el-icon>改为线下送达
          </el-button>
          <el-button size="small" type="danger" @click="openDialog('suggest')">
            <el-icon><FolderAdd /></el-icon>{{ c.transferSuggested ? '再次建议转立案' : '建议转立案' }}
          </el-button>
        </template>
        <el-button v-if="canMediate || canWindow" size="small" @click="emit('go-transfer')">
          <el-icon><Document /></el-icon>查看/生成转立案材料包
        </el-button>
      </div>
    </el-card>

    <!-- 联系尝试记录（窗口可直接查看，每次结果逐条标明） -->
    <el-card shadow="never">
      <template #header>
        <b>联系尝试记录（电话 / 短信 / 地址核验 / 送达尝试，共 {{ attempts.length }} 次，全部写入案件时间线）</b>
      </template>
      <el-timeline>
        <el-timeline-item v-for="a in attempts" :key="a.id"
                          :timestamp="`第${a.roundNo}次 · ${fmt(a.createdAt)} · ${a.operatorName}（${roleLabel(a.actorRole)}）`"
                          :type="CONTACT_RESULT_TAG[a.result] === 'success' ? 'success' : CONTACT_RESULT_TAG[a.result] === 'danger' ? 'danger' : 'warning'">
          <el-card shadow="hover">
            <div class="att">
              <el-tag size="small" type="info">{{ CONTACT_METHODS[a.method] }}</el-tag>
              <el-tag size="small" class="ml" :type="CONTACT_RESULT_TAG[a.result]">
                {{ CONTACT_RESULTS[a.result] }}
              </el-tag>
            </div>
            <p class="mv"><b>使用的联系方式：</b>{{ a.contactValue || '（未登记）' }}</p>
            <p v-if="a.note"><b>情况说明：</b>{{ a.note }}</p>
          </el-card>
        </el-timeline-item>
        <template #empty><el-empty description="尚无联系尝试记录" :image-size="70" /></template>
      </el-timeline>
    </el-card>

    <!-- 登记联系尝试 -->
    <el-dialog v-model="attemptVisible" title="登记联系尝试" width="520px">
      <el-form :model="attempt" label-width="100px">
        <el-form-item label="联系方式">
          <el-radio-group v-model="attempt.method" @change="onMethodChange">
            <el-radio-button value="PHONE">电话</el-radio-button>
            <el-radio-button value="SMS">短信</el-radio-button>
            <el-radio-button value="ADDRESS_CHECK">地址核验</el-radio-button>
            <el-radio-button value="DELIVERY">送达尝试</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="使用的信息">
          <el-input v-model="attempt.contactValue"
                    :placeholder="attempt.method === 'ADDRESS_CHECK' || attempt.method === 'DELIVERY' ? '送达地址' : '联系电话'" />
        </el-form-item>
        <el-form-item label="联系结果">
          <el-select v-model="attempt.result" style="width:100%">
            <el-option v-for="(l, k) in resultOptions" :key="k" :label="l" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="情况说明">
          <el-input v-model="attempt.note" type="textarea" :rows="3"
                    placeholder="如：多次拨打无人接听；社区反映其长期在外务工；上门留置送达邻居拒收等" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="attemptVisible = false">取消</el-button>
        <el-button type="primary" @click="saveAttempt">保存并写入时间线</el-button>
      </template>
    </el-dialog>

    <!-- 窗口处置弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="500px">
      <el-alert v-if="dialog === 'suggest'" type="error" :closable="false" show-icon class="mb"
                title="建议转立案后，可在「司法确认 / 转立案」页生成材料包：前期全部联系尝试、地址核验、线下送达记录将自动汇编并标明每次结果，窗口人员可直接查看。" />
      <el-alert v-if="dialog === 'address'" type="warning" :closable="false" show-icon class="mb"
                title="核验后系统消除本次达阈值预警，核验结论写入案件时间线并随材料包移交。" />
      <el-form :model="form" label-width="100px">
        <el-form-item v-if="dialog === 'supplement'" label="补充电话">
          <el-input v-model="form.phone" :placeholder="`原登记：${c.respondentPhone || '无'}`" />
        </el-form-item>
        <el-form-item v-if="dialog === 'supplement'" label="补充地址">
          <el-input v-model="form.address" type="textarea" :rows="2" :placeholder="`原登记：${c.respondentAddress || '无'}`" />
        </el-form-item>
        <el-form-item v-if="dialog === 'address'" label="确认地址">
          <el-input v-model="form.address" type="textarea" :rows="2" :placeholder="c.respondentAddress || '请输入核验后的送达地址'" />
        </el-form-item>
        <el-form-item label="情况说明">
          <el-input v-model="form.note" type="textarea" :rows="3" :placeholder="notePlaceholder" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitWindow">{{ confirmText }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api.js'
import { auth } from '../../store.js'
import {
  CONTACT_METHODS, CONTACT_RESULTS, CONTACT_DEFAULT_RESULT
} from '../../constants.js'

const props = defineProps({ data: Object })
const emit = defineEmits(['changed', 'go-transfer'])

const c = computed(() => props.data.case)
const attempts = computed(() => props.data.contactAttempts || [])
const failedCount = computed(() => c.value.failedContactCount ?? 0)

const canMediate = computed(() => auth.is('MEDIATOR', 'WINDOW', 'ADMIN'))
const canWindow = computed(() => auth.is('WINDOW', 'ADMIN'))

const roleLabel = r => ({ MEDIATOR: '调解员', WINDOW: '立案窗口', ASSISTANT: '法官助理', SYSTEM: '系统' }[r] || r)
const fmt = t => t ? t.replace('T', ' ').substring(0, 16) : ''

// ---- 登记联系尝试 ----
const attemptVisible = ref(false)
const attempt = reactive({ method: 'PHONE', contactValue: '', result: 'NO_ANSWER', note: '' })

const resultOptions = computed(() => {
  const opts = {}
  if (attempt.method === 'PHONE') {
    opts.REACHED = CONTACT_RESULTS.REACHED
    opts.NO_ANSWER = CONTACT_RESULTS.NO_ANSWER
    opts.SHUTDOWN = CONTACT_RESULTS.SHUTDOWN
    opts.REFUSED = CONTACT_RESULTS.REFUSED
  } else if (attempt.method === 'SMS') {
    opts.REACHED = CONTACT_RESULTS.REACHED
    opts.NO_REPLY = CONTACT_RESULTS.NO_REPLY
    opts.SHUTDOWN = CONTACT_RESULTS.SHUTDOWN
  } else if (attempt.method === 'ADDRESS_CHECK') {
    opts.REACHED = '找到本人/地址有效'
    opts.NOT_FOUND = CONTACT_RESULTS.NOT_FOUND
  } else {
    opts.DELIVERED = CONTACT_RESULTS.DELIVERED
    opts.REFUSED = CONTACT_RESULTS.REFUSED
    opts.FAILED = CONTACT_RESULTS.FAILED
  }
  return opts
})

const onMethodChange = m => {
  attempt.result = CONTACT_DEFAULT_RESULT[m]
  attempt.contactValue = (m === 'ADDRESS_CHECK' || m === 'DELIVERY')
    ? (c.value.respondentAddress || '')
    : (c.value.respondentPhone || '')
}

const openAttempt = () => {
  attempt.method = 'PHONE'
  attempt.result = 'NO_ANSWER'
  attempt.contactValue = c.value.respondentPhone || ''
  attempt.note = ''
  attemptVisible.value = true
}

const saveAttempt = async () => {
  await api.post(`/cases/${c.value.id}/contact-attempts`, { ...attempt })
  attemptVisible.value = false
  ElMessage.success('联系尝试已记录并写入案件时间线')
  emit('changed')
}

// ---- 窗口处置 ----
const dialogVisible = ref(false)
const dialog = ref('')
const form = reactive({ phone: '', address: '', note: '' })

const dialogMeta = {
  supplement: { title: '补充被申请人联系方式', confirm: '保存补充信息' },
  address: { title: '核验被申请人送达地址', confirm: '确认核验结果' },
  offline: { title: '改为线下送达', confirm: '确认改为线下送达' },
  suggest: { title: '建议失联转立案', confirm: '提交转立案建议' }
}
const dialogTitle = computed(() => dialogMeta[dialog.value]?.title || '')
const confirmText = computed(() => dialogMeta[dialog.value]?.confirm || '确定')
const notePlaceholder = computed(() => ({
  supplement: '信息来源，如：申请人提供的新号码、社区协查反馈等',
  address: '核验渠道与结论，如：公安户籍查询/社区协查，户籍地有效可留置送达',
  offline: '线下送达安排，如：交送达组三日内上门，留置送达并拍照留证',
  suggest: '建议转立案的补充说明（可选）'
}[dialog.value]))

const openDialog = type => {
  dialog.value = type
  form.phone = ''
  form.address = type === 'address' ? (c.value.respondentAddress || '') : ''
  form.note = ''
  dialogVisible.value = true
}

const submitWindow = async () => {
  const id = c.value.id
  if (dialog.value === 'supplement') {
    if (!form.phone && !form.address) { ElMessage.warning('请至少填写一项新的电话或地址'); return }
    await api.post(`/cases/${id}/contact-supplement`, { ...form })
    ElMessage.success('联系方式已补充，后续联系与送达使用最新信息')
  } else if (dialog.value === 'address') {
    await api.post(`/cases/${id}/address-verify`, { ...form })
    ElMessage.success('送达地址核验结果已记录，达阈值预警消除')
  } else if (dialog.value === 'offline') {
    await api.post(`/cases/${id}/offline-delivery`, { note: form.note })
    ElMessage.success('已登记改为线下送达并写入时间线')
  } else {
    await api.post(`/cases/${id}/suggest-transfer`, { note: form.note })
    ElMessage.success('已建议失联转立案，可前往生成转立案材料包')
  }
  dialogVisible.value = false
  emit('changed')
}
</script>

<style scoped>
.mb { margin-bottom: 14px; }
.ml { margin-left: 8px; }
.head { display: flex; justify-content: space-between; align-items: center; }
.quick { margin-top: 12px; display: flex; gap: 8px; flex-wrap: wrap; }
.notes { margin-top: 10px; background: #f7f9fc; border: 1px solid #e4e9f2; border-radius: 6px; padding: 8px 12px; }
.notes p { margin: 4px 0; font-size: 13px; color: #4a5568; }
.att { display: flex; align-items: center; }
.mv { margin: 6px 0 0; }
:deep(.el-timeline-item p) { margin: 4px 0; font-size: 13px; }
</style>
