<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { createProblemApi, getProblemCandidatesApi, getProblemsApi, updateProblemApi } from '@/api/smApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useElementWidth } from '@/composables/useElementWidth'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { Problem, ProblemCandidate, ProblemPayload } from '@/types/r3'
import { focusableRow } from '@/utils/a11y'
import { fitColumns } from '@/utils/columns'
import { SM_EDIT_ROLES } from '@/utils/permission'
import { showSuccessToast } from '@/utils/toast'

// SCR-SM-003 문제 (FR-SM-007) — 반복 SR·장애 묶음(후보)에서 근본 원인을 추적한다
const { t } = useI18n()
const auth = useAuthStore()

const problems = ref<Problem[]>([])
const candidates = ref<ProblemCandidate[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const form = reactive({ open: false, editing: null as Problem | null, data: {} as ProblemPayload, saving: false, error: null as string | null })

// ─────────── 헬퍼 ───────────
const load = async () => {
  if (!projectId.value) return
  loading.value = true
  error.value = null
  try {
    const [p, c] = await Promise.all([getProblemsApi(projectId.value), getProblemCandidatesApi(projectId.value)])
    problems.value = p
    candidates.value = c
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(() => load())

const openForm = (p: Problem | null, fromCandidate: ProblemCandidate | null = null) => {
  form.editing = p
  form.data = p
    ? { title: p.title, rootCause: p.rootCause, workaround: p.workaround, knownError: p.knownError, status: p.status, srIds: [...p.srIds], incidentIds: [...p.incidentIds] }
    : {
      title: fromCandidate ? t('sm.problem.fromCandidateTitle', { label: fromCandidate.label }) : '',
      rootCause: null, workaround: null, knownError: false, status: 'OPEN',
      srIds: fromCandidate?.srs.map((s) => s.srId) ?? [], incidentIds: fromCandidate?.incidents.map((i) => i.incidentId) ?? [],
    }
  Object.assign(form, { open: true, saving: false, error: null })
}

const save = async () => {
  if (!projectId.value) return
  if (!form.data.title.trim()) { form.error = t('validation.required'); return }
  form.saving = true
  try {
    if (form.editing) await updateProblemApi(form.editing.problemId, form.data)
    else await createProblemApi(projectId.value, form.data)
    form.open = false
    showSuccessToast('common.saved')
    void load()
  } catch (e) {
    form.error = (e as ApiError).message ?? t('common.saveFailed')
  } finally {
    form.saving = false
  }
}

const onRowClick = (_e: Event, p: { item: Problem }) => { if (canEdit.value) openForm(p.item) }
const reasonIcon = (r: ProblemCandidate['reason']) => (r === 'SAME_ELEMENT' ? 'ri-cursor-line' : r === 'SAME_SCREEN' ? 'ri-window-line' : 'ri-text')

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(SM_EDIT_ROLES))
const statusItems = computed(() => (['OPEN', 'ANALYZING', 'RESOLVED', 'CLOSED'] as const).map((s) => ({ title: t(`sm.problemStatus.${s}`), value: s })))
// 2단 화면에서 목록 자리가 좁으면 우선순위 낮은 열을 숨긴다 (마지막 열 잘림 방지)
const listCard = ref<HTMLElement | { $el: HTMLElement } | null>(null)
const listWidth = useElementWidth(listCard, 700)
const allHeaders = computed(() => [
  { title: t('sm.col.no'), key: 'problemNo', width: 100 },
  { title: t('sm.col.title'), key: 'title' },
  { title: t('sm.problem.knownError'), key: 'knownError', width: 90, hideBelow: 540 },
  { title: t('sm.problem.related'), key: 'related', width: 110, sortable: false, hideBelow: 640 },
  { title: t('sm.col.status'), key: 'status', width: 100 },
])
const headers = computed(() => fitColumns(allHeaders.value, listWidth.value))
// 근본 원인 한 줄 요약이 제목 칸을 넓히지 않도록 남는 너비만큼만 쓴다
const rootMax = computed(() => Math.max(160, Math.min(420, listWidth.value - 360)))
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.sm.problem')" :subtitle="t('sm.problem.subtitle')">
      <VBtn v-if="canEdit && projectId" color="primary" size="small" prepend-icon="ri-add-line" @click="openForm(null)">{{ t('sm.problem.add') }}</VBtn>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <VRow v-else>
      <VCol cols="12" lg="5">
        <VCard border flat :title="t('sm.problem.candidates')" :subtitle="t('sm.problem.candidatesHint')">
          <VProgressLinear v-if="loading" indeterminate color="primary" />
          <VCardText>
            <div v-if="!candidates.length && !loading" class="text-body-2 text-muted">{{ t('sm.problem.noCandidates') }}</div>
            <VCard v-for="c in candidates" :key="c.key" variant="outlined" class="mb-3" data-test="problem-candidate">
              <VCardText class="pb-2">
                <div class="d-flex align-center ga-2 mb-1">
                  <VIcon :icon="reasonIcon(c.reason)" size="16" />
                  <span class="font-weight-medium">{{ c.label }}</span>
                  <VSpacer />
                  <VChip size="x-small" color="warning" variant="flat" label>{{ t('sm.problem.countN', { n: c.count }) }}</VChip>
                </div>
                <div class="text-caption text-muted mb-1">{{ t(`sm.problem.reason.${c.reason}`) }}</div>
                <div v-for="s in c.srs" :key="s.srId" class="text-body-2">
                  <RouterLink :to="{ path: '/sm/srs', query: { id: s.srId } }" class="font-mono text-caption">{{ s.srNo }}</RouterLink> {{ s.title }}
                </div>
                <div v-for="i in c.incidents" :key="i.incidentId" class="text-body-2">
                  <RouterLink :to="{ path: '/sm/incidents', query: { id: i.incidentId } }" class="font-mono text-caption">{{ i.incidentNo }}</RouterLink> {{ i.title }}
                </div>
              </VCardText>
              <VCardActions v-if="canEdit">
                <VSpacer />
                <VBtn size="small" variant="tonal" color="primary" prepend-icon="ri-search-eye-line" data-test="problem-from-candidate" @click="openForm(null, c)">{{ t('sm.problem.register') }}</VBtn>
              </VCardActions>
            </VCard>
          </VCardText>
        </VCard>
      </VCol>
      <VCol cols="12" lg="7">
        <VCard ref="listCard" border flat :title="t('sm.problem.list')">
          <VAlert v-if="error" type="error" variant="tonal" density="compact" class="ma-3">{{ error }}</VAlert>
          <VDataTable :headers="headers" :items="problems" :loading="loading" item-value="problemId" density="compact" hover data-test="problem-table" :row-props="focusableRow" @click:row="onRowClick">
            <template #item.problemNo="{ item }"><span class="font-mono">{{ item.problemNo }}</span></template>
            <template #item.title="{ item }">
              <div>{{ item.title }}</div>
              <div v-if="item.rootCause" class="text-caption text-muted text-truncate root" :style="{ maxWidth: `${rootMax}px` }">{{ t('sm.problem.rootCause') }}: {{ item.rootCause }}</div>
            </template>
            <template #item.knownError="{ item }"><VIcon v-if="item.knownError" icon="ri-checkbox-circle-fill" color="warning" size="18" :title="t('sm.problem.knownError')" /></template>
            <template #item.related="{ item }">SR {{ item.srIds.length }} · {{ t('sm.problem.incidentShort') }} {{ item.incidentIds.length }}</template>
            <template #item.status="{ item }"><AppStatusChip :status="item.status" prefix="sm.problemStatus" size="x-small" /></template>
          </VDataTable>
        </VCard>
      </VCol>
    </VRow>

    <VDialog v-model="form.open" max-width="620" scrollable>
      <VCard :title="form.editing ? t('sm.problem.edit', { no: form.editing.problemNo }) : t('sm.problem.add')">
        <VCardText>
          <VAlert v-if="form.error" type="error" variant="tonal" density="compact" class="mb-3">{{ form.error }}</VAlert>
          <VRow dense>
            <VCol cols="12"><VTextField v-model="form.data.title" :label="t('sm.col.title')" data-test="problem-title" /></VCol>
            <VCol cols="6"><VSelect v-model="form.data.status" :items="statusItems" :label="t('sm.col.status')" /></VCol>
            <VCol cols="6"><VCheckbox v-model="form.data.knownError" :label="t('sm.problem.knownErrorHint')" density="compact" /></VCol>
            <VCol cols="12"><VTextarea :model-value="form.data.rootCause ?? ''" :label="t('sm.problem.rootCause')" rows="3" auto-grow @update:model-value="form.data.rootCause = $event || null" /></VCol>
            <VCol cols="12"><VTextarea :model-value="form.data.workaround ?? ''" :label="t('sm.problem.workaround')" rows="2" auto-grow @update:model-value="form.data.workaround = $event || null" /></VCol>
            <VCol cols="12" class="text-caption text-muted">{{ t('sm.problem.linkedCount', { sr: form.data.srIds.length, inc: form.data.incidentIds.length }) }}</VCol>
          </VRow>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="form.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="form.saving" data-test="problem-save" @click="save">{{ t('common.save') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
  </VContainer>
</template>

<style scoped>
.root {
  min-width: 0;
}

/* 카드 부제(안내 문구)는 잘리지 않게 줄바꿈 */
:deep(.v-card-subtitle) {
  white-space: normal;
}
</style>
