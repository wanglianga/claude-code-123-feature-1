<template>
  <div>
    <!-- 失联状态与阈值预警 -->
    <el-card shadow="never" class="mb">
      <template #header>
        <div class="head">
          <b>被申请人失联处置</b>
          <el-tag v-if="c.respondentMissing" type="danger" size="small">失联中</el-tag>
          <el-tag v-else-if="mh.attemptCount > 0" type="success" size="small">已取得联系</el-tag>
          <el-button v-if="canLog" type="primary" size="small" @click="openAttempt">
            <el-icon><Phone /></el-icon>登记联系尝试
          </el-button>
        </div>
      </template>

      <el-descriptions :column="4" border size="small">
        <el-descriptions-item label="联系尝试总数">{{ mh.attemptCount }} 次</el-descriptions-item>
        <el-descriptions-item label="累计失败">
          <span :class="{ danger: mh.failedCount > 0 }">{{ mh.failedCount }} 次</span>
        </el-descriptions-item>
        <el-descriptions-item label="本轮失败 / 阈值">
          <el-tag size="small" :type="mh.roundFailedCount >= mh.failThreshold ? 'danger' : 'info'">
            {{ mh.roundFailedCount }} / {{ mh.failThreshold }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="当前电话/地址">
          {{ c.respondentPhone || '无电话' }}；{{ c.respondentAddress || '无地址' }}
        </el-descriptions-item>
        <el-descriptions-item label="送达地址核验" :span="2">
          <el-tag v-if="c.addressVerifiedAt" type="success" size="small">
            已核验（{{ fmt(c.addressVerifiedAt) }}，{{ c.addressVerifiedBy }}）
          </el-tag>
          <el-tag v-else-if="remind" type="danger" size="small">待窗口核验</el-tag>
          <el-tag v-else type="info" size="small">未核验</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="线下送达" :span="2">
          <el-tag v-if="c.offlineServiceAt" type="warning" size="small">
            已改为线下送达（{{ fmt(c.offlineServiceAt) }}）
          </el-tag>
          <el-tag v-else type="info" size="small">电子送达</el-tag>
        </el-descriptions-item>
        <el-descriptions-item v-if="c.verifiedServiceAddress" label="核验后送达地址" :span="4">
          {{ c.verifiedServiceAddress }}
        </el-descriptions-item>
      </el-descriptions>

      <!-- 达阈值：平台提醒窗口核验送达地址 -->
      <el-alert v-if="remind" class="mt" type="error" show-icon :closable="false"
                title="被申请人多次联系失败已达阈值，请立案窗口核验送达地址">
        <template #default>
          <div>
            本轮已记录 <b>{{ mh.roundFailedCount }}</b> 次电话/短信/地址核验/送达失败（阈值 {{ mh.failThreshold }} 次）。
            窗口可：
            <el-button v-if="isWindow" size="small" @click="dlg.contact = true">补充联系方式</el-button>
            <el-button v-if="isWindow" size="small" @click="dlg.verify = true">核验送达地址</el-button>
            <el-button v-if="isWindow" size="small" type="warning" @click="dlg.offline = true">改为线下送达</el-button>
            <el-button v-if="isWindow" size="small" type="danger" @click="dlg.transfer = true">建议转立案</el-button>
            <span v-if="!isWindow" class="tip">（请等待立案窗口处置：补充联系方式 / 改线下送达 / 建议转立案）</span>
          </div>
        </template>
      </el-alert>

      <!-- 窗口处置记录提示 -->
      <el-alert v-if="c.missingTransferSuggested" class="mt" type="warning" show-icon :closable="false"
                :title="`窗口已于 ${fmt(c.missingTransferSuggestedAt)} 建议转立案，前期联系证据已自动汇编入材料包`" />
    </el-card>

    <!-- 全部联系尝试：每次结果随卷留痕 -->
    <el-card shadow="never">
      <template #header><b>联系 / 送达尝试记录（全部写入案件时间线，转立案时逐条进入材料包）</b></template>
      <el-table :data="mh.attempts" stripe size="small">
        <el-table-column label="#" width="46" type="index" :index="i => i + 1" />
        <el-table-column label="尝试时间" width="140">
          <template #default="{ row }">{{ fmt(row.attemptTime) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ CONTACT_TYPES[row.attemptType] }}</template>
        </el-table-column>
        <el-table-column label="方式" width="150">
          <template #default="{ row }">{{ CONTACT_CHANNELS[row.channel] || row.channel }}</template>
        </el-table-column>
        <el-table-column label="联系对象/地址" prop="target" min-width="160" show-overflow-tooltip />
        <el-table-column label="结果" width="140">
          <template #default="{ row }">
            <el-tag size="small" :type="CONTACT_RESULT_TAG[row.result] || 'info'">
              {{ CONTACT_RESULTS[row.result] || row.result }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="情况说明" prop="note" min-width="200" show-overflow-tooltip />
        <el-table-column label="登记人" width="100" prop="operatorName" />
        <template #empty><el-empty description="尚无联系尝试，调解员可登记电话/短信/地址核验/送达尝试" :image-size="70" /></template>
      </el-table>
    </el-card>

    <!-- 登记联系尝试 -->
    <el-dialog v-model="dlg.attempt" title="登记联系尝试" width="560px">
      <el-form :model="form" label-width="96px">
        <el-form-item label="尝试类型">
          <el-radio-group v-model="form.attemptType" @change="onTypeChange">
            <el-radio-button v-for="(l, k) in CONTACT_TYPES" :key="k" :value="k">{{ l }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="具体方式">
          <el-select v-model="form.channel" style="width:100%">
            <el-option v-for="ch in channelOptions" :key="ch" :value="ch" :label="CONTACT_CHANNELS[ch]" />
          </el-select>
        </el-form-item>
        <el-form-item label="联系对象">
          <el-input v-model="form.target" :placeholder="isAddressType ? '送达/核验地址' : '电话号码'" />
        </el-form-item>
        <el-form-item label="尝试结果">
          <el-select v-model="form.result" style="width:100%">
            <el-option v-for="(l, k) in resultOptions" :key="k" :value="k" :label="l" />
          </el-select>
        </el-form-item>
        <el-form-item label="尝试时间">
          <el-date-picker v-model="form.attemptTime" type="datetime" value-format="YYYY-MM-DD HH:mm"
                          placeholder="默认当前时间（可补登记历史尝试）" style="width:100%" />
        </el-form-item>
        <el-form-item label="情况说明">
          <el-input v-model="form.note" type="textarea" :rows="3"
                    placeholder="如：连续两日不同时段拨打5次均无人接听；或上门无人、邻居反映已搬迁等" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.attempt = false">取消</el-button>
        <el-button type="primary" @click="saveAttempt">保存尝试并写入时间线</el-button>
      </template>
    </el-dialog>

    <!-- 窗口：补充联系方式 -->
    <el-dialog v-model="dlg.contact" title="窗口补充被申请人联系方式" width="480px">
      <el-alert type="info" :closable="false" show-icon class="mb"
                title="补充后通知调解员据此重新联系；前期联系记录仍连续留痕，本轮失败次数重新计数。" />
      <el-form :model="cf" label-width="96px">
        <el-form-item label="联系电话"><el-input v-model="cf.phone" placeholder="新的联系电话（可选）" /></el-form-item>
        <el-form-item label="送达地址"><el-input v-model="cf.address" type="textarea" :rows="2" placeholder="补充/修正的送达地址（可选）" /></el-form-item>
        <el-form-item label="信息来源"><el-input v-model="cf.source" placeholder="如：申请人提供 / 社区网格反馈 / 12368协查" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.contact = false">取消</el-button>
        <el-button type="primary" @click="doWindow('contact')">保存并通知调解员</el-button>
      </template>
    </el-dialog>

    <!-- 窗口：核验送达地址 -->
    <el-dialog v-model="dlg.verify" title="窗口核验送达地址" width="480px">
      <el-form :model="vf" label-width="96px">
        <el-form-item label="核验后地址">
          <el-input v-model="vf.verifiedAddress" type="textarea" :rows="2" :placeholder="c.respondentAddress || '请输入核验后的送达地址'" />
        </el-form-item>
        <el-form-item label="核验情况"><el-input v-model="vf.note" type="textarea" :rows="3" placeholder="如：户籍地/经常居住地核查情况、社区或物业反馈" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.verify = false">取消</el-button>
        <el-button type="primary" @click="doWindow('verify')">确认核验</el-button>
      </template>
    </el-dialog>

    <!-- 窗口：改为线下送达 -->
    <el-dialog v-model="dlg.offline" title="改为线下送达" width="480px">
      <el-alert type="warning" :closable="false" show-icon class="mb"
                title="电子送达未果，改为线下送达；送达尝试与结果继续登记并随卷留痕。" />
      <el-form :model="of" label-width="96px">
        <el-form-item label="送达方式">
          <el-radio-group v-model="of.offlineWay">
            <el-radio v-for="(l, k) in OFFLINE_WAYS" :key="k" :value="k">{{ l }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="送达地址">
          <el-input v-model="of.serviceAddress" type="textarea" :rows="2" :placeholder="c.verifiedServiceAddress || c.respondentAddress || '线下送达地址'" />
        </el-form-item>
        <el-form-item label="情况说明"><el-input v-model="of.note" type="textarea" :rows="3" placeholder="如：上门无人依法留置、同住成年家属情况等" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.offline = false">取消</el-button>
        <el-button type="warning" @click="doWindow('offline')">确认改线下送达</el-button>
      </template>
    </el-dialog>

    <!-- 窗口：建议转立案 -->
    <el-dialog v-model="dlg.transfer" title="建议转立案（被申请人失联）" width="500px">
      <el-alert type="error" :closable="false" show-icon class="mb"
                title="系统将以“被申请人失联”为原因自动汇编转立案材料包，前期全部电话/短信/地址核验/送达尝试证据逐条标明结果进入材料包，窗口可在“司法确认/转立案”页直接接收。" />
      <el-form :model="tf" label-width="96px">
        <el-form-item label="处置说明">
          <el-input v-model="tf.note" type="textarea" :rows="3"
                    placeholder="如：电话停机、短信无回应、户籍地查无此人、留置送达未果，建议转立案" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.transfer = false">取消</el-button>
        <el-button type="danger" @click="doWindow('transfer')">建议并自动汇编材料包</el-button>
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
  CONTACT_TYPES, CONTACT_CHANNELS, CONTACT_CHANNEL_OPTIONS,
  CONTACT_RESULTS, CONTACT_RESULT_TAG, OFFLINE_WAYS
} from '../../constants.js'

const props = defineProps({ data: Object })
const emit = defineEmits(['changed'])
const c = computed(() => props.data.case)
const mh = computed(() => props.data.missingHandle || {
  attempts: [], attemptCount: 0, failedCount: 0, roundFailedCount: 0,
  failThreshold: 3, addressVerifyRemind: false, addressVerifyPending: false
})

const isWindow = computed(() => auth.is('WINDOW', 'ADMIN'))
const canLog = computed(() => auth.is('MEDIATOR', 'WINDOW', 'ADMIN'))
// 已达阈值：本轮失败数到阈值，或平台已提醒而地址尚未核验
const remind = computed(() => mh.value.addressVerifyPending
  || (mh.value.roundFailedCount >= mh.value.failThreshold && !c.value.addressVerifiedAt))

const isAddressType = computed(() => ['ADDRESS_CHECK', 'SERVICE'].includes(form.attemptType))
const channelOptions = computed(() => CONTACT_CHANNEL_OPTIONS[form.attemptType] || ['PHONE_CALL'])
const resultOptions = computed(() => {
  // 送达尝试突出送达类结果，其余突出联系类结果
  if (form.attemptType === 'SERVICE') {
    return { DELIVERED: CONTACT_RESULTS.DELIVERED, REFUSED: CONTACT_RESULTS.REFUSED, FAILED: CONTACT_RESULTS.FAILED, CONNECTED: CONTACT_RESULTS.CONNECTED }
  }
  const all = { ...CONTACT_RESULTS }
  delete all.DELIVERED
  return all
})

const dlg = reactive({ attempt: false, contact: false, verify: false, offline: false, transfer: false })
const form = reactive({ attemptType: 'PHONE', channel: 'PHONE_CALL', target: '', result: 'NO_ANSWER', note: '', attemptTime: '' })
const cf = reactive({ phone: '', address: '', source: '' })
const vf = reactive({ verifiedAddress: '', note: '' })
const of = reactive({ offlineWay: 'DIRECT', serviceAddress: '', note: '' })
const tf = reactive({ note: '' })

const openAttempt = () => {
  Object.assign(form, {
    attemptType: 'PHONE', channel: 'PHONE_CALL',
    target: c.value.respondentPhone || '',
    result: 'NO_ANSWER', note: '', attemptTime: ''
  })
  dlg.attempt = true
}
const onTypeChange = t => {
  form.channel = (CONTACT_CHANNEL_OPTIONS[t] || ['PHONE_CALL'])[0]
  form.target = ['ADDRESS_CHECK', 'SERVICE'].includes(t) ? (c.value.respondentAddress || '') : (c.value.respondentPhone || '')
  form.result = t === 'SERVICE' ? 'DELIVERED' : 'NO_ANSWER'
}

const saveAttempt = async () => {
  const r = await api.post(`/cases/${c.value.id}/contact-attempts`, form)
  dlg.attempt = false
  if (r.recovered) ElMessage.success('已联系上被申请人，失联状态解除并写入时间线')
  else if (r.thresholdReached) ElMessage.warning('已达失败阈值，平台已提醒窗口核验送达地址')
  else ElMessage.success('联系尝试已记录并写入案件时间线')
  emit('changed')
}

const doWindow = async kind => {
  if (kind === 'contact') {
    if (!cf.phone && !cf.address) { ElMessage.warning('请至少填写补充电话或送达地址'); return }
    await api.post(`/cases/${c.value.id}/missing/contact`, cf)
    dlg.contact = false
    ElMessage.success('联系方式已补充，已通知调解员重新联系，失败次数重新计数')
  } else if (kind === 'verify') {
    await api.post(`/cases/${c.value.id}/missing/verify-address`, vf)
    dlg.verify = false
    ElMessage.success('送达地址核验完成并写入时间线')
  } else if (kind === 'offline') {
    await api.post(`/cases/${c.value.id}/missing/offline-service`, of)
    dlg.offline = false
    ElMessage.success('已改为线下送达，后续送达结果请继续登记')
  } else if (kind === 'transfer') {
    await api.post(`/cases/${c.value.id}/missing/suggest-transfer`, tf)
    dlg.transfer = false
    ElMessage.success('已建议转立案，含每次联系结果的材料包已自动汇编')
  }
  emit('changed')
}

const fmt = t => t ? t.replace('T', ' ').substring(0, 16) : ''
</script>

<style scoped>
.mb { margin-bottom: 14px; }
.mt { margin-top: 10px; }
.head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.danger { color: #f56c6c; font-weight: 700; }
.tip { color: #909399; font-size: 12px; margin-left: 6px; }
</style>
