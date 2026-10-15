<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { previewHandoverApi, runHandoverApi } from '@/api/impactApi'
import { getMembersApi, getSystemsApi } from '@/api/projectApi'
import UserLookupField from '@/components/common/UserLookupField.vue'
import { useProjectStore } from '@/stores/project'
import type { ApiError, Project, SystemSummary } from '@/types/api'
import type { Member } from '@/types/common'
import type { HandoverPreview, HandoverRequest, HandoverResult } from '@/types/r3'

// SI → SM 인수인계 마법사 (FR-SM-013, docs/13 §7) — 대상 → 범위 → 미리보기 → 실행 → 결과
const props = defineProps<{ modelValue: boolean; siProject: Project | null }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; done: [smProjectId: string] }>()

const { t } = useI18n()
const projectStore = useProjectStore()

const step = ref(1)
const systems = ref<SystemSummary[]>([])
const members = ref<Member[]>([])
const target = reactive({
  mode: 'new' as 'new' | 'existing',
  existingId: null as string | null,
  name: '', customer: '', startDate: '', endDate: '', pmUserId: null as string | null,
})
const scope = reactive({
  systemIds: [] as string[], memberUserIds: [] as string[],
  include: { notes: true, traceLinks: true, deliverables: true, openDefectsAsSr: true },
})
const preview = ref<HandoverPreview | null>(null)
const result = ref<HandoverResult | null>(null)
const busy = ref(false)
const error = ref<string | null>(null)

// ─────────── 헬퍼 ───────────
const reset = async () => {
  const si = props.siProject
  step.value = 1
  preview.value = null
  result.value = null
  error.value = null
  Object.assign(target, {
    mode: 'new', existingId: null, name: si ? t('handover.defaultName', { name: si.name }) : '', customer: si?.customer ?? '',
    startDate: si?.endDate ?? '', endDate: '', pmUserId: si?.pmUserId ?? null,
  })
  if (!si) return
  const [s, m] = await Promise.all([getSystemsApi(si.projectId).catch(() => []), getMembersApi(si.projectId).catch(() => [])])
  systems.value = s
  members.value = m
  Object.assign(scope, { systemIds: s.map((x) => x.systemId), memberUserIds: m.filter((x) => !['PARTNER', 'CUSTOMER'].includes(x.roleId)).map((x) => x.userId) })
}

const request = (): HandoverRequest => ({
  target: target.mode === 'existing'
    ? { projectId: target.existingId as string }
    : { newProject: { name: target.name.trim(), customer: target.customer.trim(), startDate: target.startDate, endDate: target.endDate, pmUserId: target.pmUserId as string } },
  systemIds: [...scope.systemIds],
  memberUserIds: [...scope.memberUserIds],
  include: { ...scope.include },
})

const validateTarget = (): string | null => {
  if (target.mode === 'existing') return target.existingId ? null : t('handover.pickExisting')
  if (!target.name.trim() || !target.customer.trim() || !target.startDate || !target.endDate || !target.pmUserId) return t('handover.fillAll')
  if (target.startDate > target.endDate) return t('validation.dateOrder')
  return null
}

const next = async () => {
  error.value = null
  if (step.value === 1) {
    const msg = validateTarget()
    if (msg) { error.value = msg; return }
    step.value = 2
  } else if (step.value === 2) {
    if (!scope.systemIds.length) { error.value = t('handover.pickSystem'); return }
    if (!props.siProject) return
    busy.value = true
    try {
      preview.value = await previewHandoverApi(props.siProject.projectId, request())
      step.value = 3
    } catch (e) {
      error.value = (e as ApiError).message ?? t('common.actionFailed')
    } finally {
      busy.value = false
    }
  }
}

const run = async () => {
  if (!props.siProject) return
  busy.value = true
  error.value = null
  try {
    result.value = await runHandoverApi(props.siProject.projectId, request())
    step.value = 4
    await projectStore.loadProjects()
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.actionFailed')
  } finally {
    busy.value = false
  }
}

const goToSm = async () => {
  if (!result.value) return
  await projectStore.selectProject(result.value.smProjectId)
  emit('done', result.value.smProjectId)
  emit('update:modelValue', false)
}

// ─────────── computed ───────────
const smProjects = computed(() => projectStore.projects.filter((p) => p.type === 'SM').map((p) => ({ title: p.name, value: p.projectId })))
const steps = computed(() => [t('handover.step.target'), t('handover.step.scope'), t('handover.step.preview'), t('handover.step.result')])
const memberItems = computed(() => members.value.map((m) => ({ title: `${m.name} (${m.roleId})`, value: m.userId })))
const previewTotals = computed(() => {
  const p = preview.value
  if (!p) return null
  return p.systems.reduce((acc, s) => ({ screens: acc.screens + s.screens, elements: acc.elements + s.elements, edges: acc.edges + s.edges }), { screens: 0, elements: 0, edges: 0 })
})

watch(() => props.modelValue, (open) => { if (open) void reset() })
</script>

<template>
  <VDialog :model-value="modelValue" max-width="760" scrollable persistent @update:model-value="emit('update:modelValue', $event)">
    <VCard :title="t('handover.title')" :subtitle="siProject?.name">
      <div class="d-flex flex-wrap ga-2 px-6 pb-2" role="list" :aria-label="t('handover.title')">
        <VChip
          v-for="(s, i) in steps"
          :key="s"
          role="listitem"
          :color="step === i + 1 ? 'primary' : step > i + 1 ? 'success' : undefined"
          :variant="step === i + 1 ? 'flat' : 'tonal'"
          size="small"
          label
        >
          <VIcon v-if="step > i + 1" icon="ri-check-line" start size="14" />{{ i + 1 }}. {{ s }}
        </VChip>
      </div>
      <VDivider />
      <VCardText>
        <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mb-3">{{ error }}</VAlert>

        <!-- 1. 대상 -->
        <template v-if="step === 1">
          <VRadioGroup v-model="target.mode" inline hide-details class="mb-3">
            <VRadio value="new" :label="t('handover.newProject')" data-test="handover-mode-new" />
            <VRadio value="existing" :label="t('handover.existingProject')" :disabled="!smProjects.length" />
          </VRadioGroup>
          <VSelect v-if="target.mode === 'existing'" v-model="target.existingId" :items="smProjects" :label="t('handover.existingProject')" />
          <VRow v-else dense>
            <VCol cols="12"><VTextField v-model="target.name" :label="t('admin.projects.name')" data-test="handover-name" /></VCol>
            <VCol cols="12" sm="6"><VTextField v-model="target.customer" :label="t('admin.projects.customer')" /></VCol>
            <VCol cols="12" sm="6"><UserLookupField v-model="target.pmUserId" :label="t('admin.projects.pm')" :initial="siProject?.pmUserId ? { userId: siProject.pmUserId, name: siProject.pmName ?? siProject.pmUserId } : null" :clearable="false" /></VCol>
            <VCol cols="6"><VTextField v-model="target.startDate" type="date" :label="t('admin.projects.startDate')" data-test="handover-start" /></VCol>
            <VCol cols="6"><VTextField v-model="target.endDate" type="date" :label="t('admin.projects.endDate')" data-test="handover-end" /></VCol>
          </VRow>
        </template>

        <!-- 2. 범위 -->
        <template v-else-if="step === 2">
          <div class="text-subtitle-2 mb-1">{{ t('handover.systems') }}</div>
          <div class="text-caption text-muted mb-2">{{ t('handover.systemsHint') }}</div>
          <VCheckbox
            v-for="s in systems"
            :key="s.systemId"
            v-model="scope.systemIds"
            :value="s.systemId"
            :label="`${s.name} (${s.code}) — ${t('handover.screensN', { n: s.screenCount, done: s.doneCount })}`"
            density="compact"
            hide-details
          />
          <div v-if="!systems.length" class="text-body-2 text-muted">{{ t('handover.noSystems') }}</div>
          <VAutocomplete v-model="scope.memberUserIds" :items="memberItems" :label="t('handover.members')" :hint="t('handover.membersHint')" persistent-hint multiple chips closable-chips class="mt-4" />
          <div class="text-subtitle-2 mt-4 mb-1">{{ t('handover.options') }}</div>
          <VCheckbox v-model="scope.include.notes" :label="t('handover.opt.notes')" density="compact" hide-details />
          <VCheckbox v-model="scope.include.traceLinks" :label="t('handover.opt.traceLinks')" density="compact" hide-details />
          <VCheckbox v-model="scope.include.deliverables" :label="t('handover.opt.deliverables')" density="compact" hide-details />
          <VCheckbox v-model="scope.include.openDefectsAsSr" :label="t('handover.opt.openDefectsAsSr')" density="compact" hide-details />
        </template>

        <!-- 3. 미리보기 -->
        <template v-else-if="step === 3 && preview">
          <VAlert type="info" variant="tonal" density="compact" class="mb-3">{{ t('handover.previewHint') }}</VAlert>
          <div class="table-scroll">
            <VTable density="compact" class="border rounded mb-3" data-test="handover-preview">
              <thead><tr><th>{{ t('handover.system') }}</th><th class="text-end">{{ t('handover.screens') }}</th><th class="text-end">{{ t('handover.elements') }}</th><th class="text-end">{{ t('handover.edges') }}</th></tr></thead>
              <tbody>
                <tr v-for="s in preview.systems" :key="s.systemId"><td>{{ s.name }}</td><td class="text-end font-mono">{{ s.screens }}</td><td class="text-end font-mono">{{ s.elements }}</td><td class="text-end font-mono">{{ s.edges }}</td></tr>
                <tr v-if="previewTotals" class="font-weight-bold"><td>{{ t('handover.total') }}</td><td class="text-end font-mono">{{ previewTotals.screens }}</td><td class="text-end font-mono">{{ previewTotals.elements }}</td><td class="text-end font-mono">{{ previewTotals.edges }}</td></tr>
              </tbody>
            </VTable>
          </div>
          <div class="d-flex flex-wrap ga-4 text-body-2">
            <span>{{ t('handover.members') }} <b>{{ preview.members }}</b></span>
            <span>{{ t('handover.opt.traceLinks') }} <b>{{ preview.traceLinks }}</b></span>
            <span>{{ t('handover.opt.notes') }} <b>{{ preview.notes }}</b></span>
            <span>{{ t('handover.opt.deliverables') }} <b>{{ preview.deliverables }}</b></span>
          </div>
        </template>

        <!-- 4. 결과 -->
        <template v-else-if="step === 4 && result">
          <VAlert type="success" variant="tonal" class="mb-3" data-test="handover-result">{{ t('handover.done') }}</VAlert>
          <VTable density="compact" class="border rounded">
            <tbody>
              <tr v-for="(n, k) in result.copied" :key="k"><td>{{ t(`handover.copied.${k}`, String(k)) }}</td><td class="text-end font-mono">{{ n }}</td></tr>
            </tbody>
          </VTable>
        </template>
      </VCardText>
      <VCardActions>
        <VBtn v-if="step > 1 && step < 4" variant="text" :disabled="busy" @click="step--">{{ t('handover.prev') }}</VBtn>
        <VSpacer />
        <VBtn v-if="step < 4" variant="text" :disabled="busy" @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</VBtn>
        <VBtn v-if="step < 3" color="primary" :loading="busy" data-test="handover-next" @click="next">{{ t('handover.next') }}</VBtn>
        <VBtn v-if="step === 3" color="primary" :loading="busy" prepend-icon="ri-send-plane-line" data-test="handover-run" @click="run">{{ t('handover.run') }}</VBtn>
        <template v-if="step === 4">
          <VBtn variant="text" @click="emit('update:modelValue', false)">{{ t('common.close') }}</VBtn>
          <VBtn color="primary" data-test="handover-go" @click="goToSm">{{ t('handover.goToSm') }}</VBtn>
        </template>
      </VCardActions>
    </VCard>
  </VDialog>
</template>

<style scoped>
.table-scroll {
  overflow-x: auto;
}
</style>
