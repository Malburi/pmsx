<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { createChangeRequestApi, getChangeRequestsApi, transitionChangeRequestApi, updateChangeRequestApi } from '@/api/qualityApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useTraceTargets, type TraceTarget } from '@/composables/useTraceTargets'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { ChangeRequest, ChangeRequestPayload, CrStatus } from '@/types/r2'
import { formatDateTime } from '@/utils/format'
import { CR_APPROVE_ROLES, ISSUE_EDIT_ROLES } from '@/utils/permission'
import { CR_APPROVAL_STATUSES, CR_TRANSITIONS } from '@/utils/quality'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-QA-003 변경요청 (FR-QA-004) — 요청 → 영향 산정 → 승인 → 반영
const { t } = useI18n()
const auth = useAuthStore()
const { load: loadTargets } = useTraceTargets()

const list = ref<ChangeRequest[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const reqs = ref<TraceTarget[]>([])
const wbs = ref<TraceTarget[]>([])
const blank = (): ChangeRequestPayload => ({ title: '', reason: '', requester: '', impact: { scope: '', mmDelta: 0, scheduleDeltaDays: 0 }, reqIds: [], wbsIds: [] })
const form = reactive({ open: false, editId: null as string | null, data: blank(), saving: false, error: null as string | null })
const trans = reactive({ open: false, target: null as ChangeRequest | null, to: 'ANALYZED' as CrStatus, comment: '', saving: false })

// ─────────── 헬퍼 ───────────
const load = async (projectId: string) => {
  loading.value = true
  error.value = null
  try {
    list.value = await getChangeRequestsApi(projectId)
    // 카드에 요구사항·WBS 이름을 보여 주기 위해 함께 읽는다
    if (!reqs.value.length) [reqs.value, wbs.value] = await Promise.all([loadTargets('REQ'), loadTargets('WBS')]).catch(() => [[], []])
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(load)
const reload = () => { if (projectId.value) void load(projectId.value) }

const openForm = async (c: ChangeRequest | null) => {
  Object.assign(form, {
    open: true, editId: c?.crId ?? null, saving: false, error: null,
    data: c ? { title: c.title, reason: c.reason, requester: c.requester, impact: { ...c.impact }, reqIds: [...c.reqIds], wbsIds: [...c.wbsIds] } : blank(),
  })
  if (!reqs.value.length) [reqs.value, wbs.value] = await Promise.all([loadTargets('REQ'), loadTargets('WBS')]).catch(() => [[], []])
}

const save = async () => {
  if (!projectId.value) return
  if (!form.data.title.trim()) { form.error = t('validation.required'); return }
  form.saving = true
  try {
    const payload = { ...form.data, impact: { ...form.data.impact, mmDelta: Number(form.data.impact.mmDelta) || 0, scheduleDeltaDays: Number(form.data.impact.scheduleDeltaDays) || 0 } }
    if (form.editId) await updateChangeRequestApi(form.editId, payload)
    else await createChangeRequestApi(projectId.value, payload)
    form.open = false
    showSuccessToast('common.saved')
    reload()
  } catch (e) {
    form.error = (e as ApiError).message ?? t('common.saveFailed')
  } finally {
    form.saving = false
  }
}

const openTrans = (c: ChangeRequest, to: CrStatus) => Object.assign(trans, { open: true, target: c, to, comment: '', saving: false })

const runTrans = async () => {
  if (!trans.target) return
  trans.saving = true
  try {
    await transitionChangeRequestApi(trans.target.crId, trans.to, trans.comment.trim() || undefined)
    trans.open = false
    showSuccessToast('qa.cr.transitioned')
    reload()
  } catch (e) {
    const err = e as ApiError
    showErrorToast(err.code === 'FORBIDDEN' ? 'common.forbiddenAction' : 'common.actionFailed')
  } finally {
    trans.saving = false
  }
}

const nexts = (c: ChangeRequest) => CR_TRANSITIONS[c.status]
  .filter((s) => !CR_APPROVAL_STATUSES.includes(s) || (c.status === 'ANALYZED' && auth.hasAnyRole(CR_APPROVE_ROLES)) || (c.status === 'REQUESTED' && s === 'REJECTED' && auth.hasAnyRole(CR_APPROVE_ROLES)))
const labelOf = (list2: TraceTarget[], id: string) => list2.find((x) => x.id === id)?.label ?? id

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(ISSUE_EDIT_ROLES))
const reqItems = computed(() => reqs.value.map((x) => ({ title: x.label, value: x.id })))
const wbsItems = computed(() => wbs.value.map((x) => ({ title: x.label, value: x.id })))
const sums = computed(() => {
  const approved = list.value.filter((c) => c.status === 'APPROVED' || c.status === 'APPLIED')
  return { mm: Math.round(approved.reduce((s, c) => s + c.impact.mmDelta, 0) * 10) / 10, days: approved.reduce((s, c) => s + c.impact.scheduleDeltaDays, 0) }
})
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.quality.cr')" :subtitle="t('qa.cr.subtitle')">
      <VBtn v-if="canEdit && projectId" color="primary" size="small" prepend-icon="ri-add-line" data-test="cr-add" @click="openForm(null)">{{ t('qa.cr.add') }}</VBtn>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <template v-else>
      <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mb-3">{{ error }}</VAlert>
      <VAlert type="info" variant="tonal" density="compact" class="mb-3">{{ t('qa.cr.approvedImpact', { mm: sums.mm, days: sums.days }) }}</VAlert>
      <VProgressLinear v-if="loading" indeterminate color="primary" class="mb-2" />
      <VRow>
        <VCol v-for="c in list" :key="c.crId" cols="12" md="6" xl="4">
          <VCard border flat class="h-100" :data-test="`cr-${c.crId}`">
            <VCardTitle class="d-flex align-center ga-2">
              <span class="font-mono text-body-2">{{ c.crNo }}</span>
              <AppStatusChip :status="c.status" prefix="qa.crStatus" size="x-small" />
              <VSpacer />
              <VBtn v-if="canEdit && (c.status === 'REQUESTED' || c.status === 'ANALYZED')" icon variant="text" size="small" :aria-label="t('common.edit')" @click="openForm(c)"><VIcon icon="ri-edit-line" /></VBtn>
            </VCardTitle>
            <VCardText>
              <div class="text-subtitle-1 font-weight-medium">{{ c.title }}</div>
              <div class="text-caption text-muted mb-2">{{ t('qa.cr.requester') }} {{ c.requester }} · {{ formatDateTime(c.updatedAt) }}</div>
              <p class="text-body-2 mb-3">{{ c.reason }}</p>
              <div class="impact">
                <div><span>{{ t('qa.cr.scope') }}</span><b>{{ c.impact.scope || '-' }}</b></div>
                <div><span>{{ t('qa.cr.mm') }}</span><b :class="{ 'text-error': c.impact.mmDelta > 1 }">{{ c.impact.mmDelta > 0 ? '+' : '' }}{{ c.impact.mmDelta }} MM</b></div>
                <div><span>{{ t('qa.cr.days') }}</span><b :class="{ 'text-error': c.impact.scheduleDeltaDays > 5 }">{{ c.impact.scheduleDeltaDays > 0 ? '+' : '' }}{{ c.impact.scheduleDeltaDays }}{{ t('qa.cr.dayUnit') }}</b></div>
              </div>
              <div class="d-flex flex-wrap ga-1 mt-2">
                <VChip v-for="r in c.reqIds" :key="r" size="x-small" variant="outlined">{{ labelOf(reqs, r) }}</VChip>
                <VChip v-for="w in c.wbsIds" :key="w" size="x-small" variant="outlined" prepend-icon="ri-node-tree">{{ labelOf(wbs, w) }}</VChip>
              </div>
            </VCardText>
            <VCardActions v-if="canEdit && nexts(c).length">
              <VSpacer />
              <VBtn
                v-for="s in nexts(c)"
                :key="s"
                size="small"
                variant="tonal"
                :color="s === 'REJECTED' ? 'error' : s === 'APPROVED' ? 'success' : 'primary'"
                :data-test="`cr-${c.crId}-${s}`"
                @click="openTrans(c, s)"
              >{{ t(`qa.crAction.${s}`) }}</VBtn>
            </VCardActions>
          </VCard>
        </VCol>
        <VCol v-if="!list.length && !loading" cols="12"><VAlert type="info" variant="tonal">{{ t('qa.cr.none') }}</VAlert></VCol>
      </VRow>
    </template>

    <VDialog v-model="form.open" max-width="640">
      <VCard :title="form.editId ? t('qa.cr.edit') : t('qa.cr.add')">
        <VCardText>
          <VRow dense>
            <VCol cols="12" sm="8"><VTextField v-model="form.data.title" :label="t('qa.col.title')" data-test="cr-title" /></VCol>
            <VCol cols="12" sm="4"><VTextField v-model="form.data.requester" :label="t('qa.cr.requester')" /></VCol>
            <VCol cols="12"><VTextarea v-model="form.data.reason" :label="t('qa.cr.reason')" rows="2" /></VCol>
            <VCol cols="12"><div class="text-subtitle-2">{{ t('qa.cr.impactTitle') }}</div></VCol>
            <VCol cols="12"><VTextField v-model="form.data.impact.scope" :label="t('qa.cr.scope')" /></VCol>
            <VCol cols="6"><VTextField v-model.number="form.data.impact.mmDelta" type="number" step="0.1" :label="t('qa.cr.mm')" suffix="MM" /></VCol>
            <VCol cols="6"><VTextField v-model.number="form.data.impact.scheduleDeltaDays" type="number" :label="t('qa.cr.days')" :suffix="t('qa.cr.dayUnit')" /></VCol>
            <VCol cols="12"><VAutocomplete v-model="form.data.reqIds" :items="reqItems" :label="t('qa.cr.reqs')" multiple chips closable-chips /></VCol>
            <VCol cols="12"><VAutocomplete v-model="form.data.wbsIds" :items="wbsItems" :label="t('qa.cr.wbs')" multiple chips closable-chips /></VCol>
          </VRow>
          <VAlert v-if="form.error" type="error" variant="tonal" density="compact" class="mt-2">{{ form.error }}</VAlert>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="form.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="form.saving" data-test="cr-save" @click="save">{{ t('common.save') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>

    <VDialog v-model="trans.open" max-width="440">
      <VCard :title="t(`qa.crAction.${trans.to}`)">
        <VCardText>
          <div class="text-body-2 mb-3">{{ trans.target?.crNo }} {{ trans.target?.title }}</div>
          <VTextarea v-model="trans.comment" :label="t('dl.comment')" rows="3" />
          <div v-if="trans.to === 'APPLIED'" class="text-caption text-muted">{{ t('qa.cr.applyHint') }}</div>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="trans.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn :color="trans.to === 'REJECTED' ? 'error' : 'primary'" :loading="trans.saving" data-test="cr-trans-run" @click="runTrans">{{ t(`qa.crAction.${trans.to}`) }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
  </VContainer>
</template>

<style scoped>
.impact {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr;
  gap: 8px;
  border: 1px solid rgb(var(--v-theme-line));
  border-radius: 8px;
  padding: 8px 12px;
}

.impact div {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.impact span {
  font-size: 11px;
  color: rgb(var(--v-theme-muted));
}

.impact b {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
</style>
