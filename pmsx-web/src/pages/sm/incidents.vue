<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { addIncidentTimelineApi, createIncidentApi, getIncidentsApi, updateIncidentApi } from '@/api/smApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useAuthStore } from '@/stores/auth'
import { useProjectStore } from '@/stores/project'
import type { ApiError } from '@/types/api'
import type { Incident, IncidentPayload, IncidentStatus } from '@/types/r3'
import { formatDateTime } from '@/utils/format'
import { SM_EDIT_ROLES } from '@/utils/permission'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-SM-002 장애 (FR-SM-006) — 등급·타임라인·영향·RCA·재발방지, 장애보고서 엑셀
const { t } = useI18n()
const route = useRoute()
const auth = useAuthStore()
const projectStore = useProjectStore()

const list = ref<Incident[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const selectedId = ref<string | null>(null)
const statusFilter = ref<IncidentStatus | null>(null)
const form = reactive({ open: false, editing: null as Incident | null, data: {} as IncidentPayload, saving: false, error: null as string | null })
const timeline = reactive({ at: '', content: '', posting: false })

// ─────────── 헬퍼 ───────────
const load = async () => {
  if (!projectId.value) return
  loading.value = true
  error.value = null
  try {
    list.value = await getIncidentsApi(projectId.value)
    if (!selectedId.value && list.value.length) selectedId.value = list.value[0].incidentId
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(() => { selectedId.value = null; void load() })

const localInput = (iso: string | null) => (iso ? formatDateTime(iso).replace(' ', 'T') : '')
const toIso = (local: string) => (local ? `${local}:00+09:00` : null)

const openForm = (inc: Incident | null) => {
  form.editing = inc
  form.data = inc
    ? {
      title: inc.title, grade: inc.grade, status: inc.status, startedAt: localInput(inc.startedAt), detectedAt: localInput(inc.detectedAt),
      recoveredAt: localInput(inc.recoveredAt), impact: inc.impact, systemIds: [...inc.systemIds], cause: inc.cause, rca: inc.rca,
      prevention: inc.prevention, srIds: [...inc.srIds],
    }
    : {
      title: '', grade: 3, status: 'OPEN', startedAt: localInput(new Date().toISOString()), detectedAt: null, recoveredAt: null,
      impact: '', systemIds: [], cause: null, rca: null, prevention: null, srIds: [],
    }
  Object.assign(form, { open: true, saving: false, error: null })
}

const save = async () => {
  if (!projectId.value) return
  if (!form.data.title.trim() || !form.data.startedAt) { form.error = t('validation.required'); return }
  form.saving = true
  try {
    const payload: IncidentPayload = {
      ...form.data,
      startedAt: toIso(form.data.startedAt) as string,
      detectedAt: toIso(form.data.detectedAt ?? ''),
      recoveredAt: toIso(form.data.recoveredAt ?? ''),
    }
    const saved = form.editing ? await updateIncidentApi(form.editing.incidentId, payload) : await createIncidentApi(projectId.value, payload)
    form.open = false
    selectedId.value = saved.incidentId
    showSuccessToast('common.saved')
    void load()
  } catch (e) {
    form.error = (e as ApiError).message ?? t('common.saveFailed')
  } finally {
    form.saving = false
  }
}

const addTimeline = async () => {
  if (!selected.value || !timeline.content.trim()) return
  timeline.posting = true
  try {
    const at = toIso(timeline.at) ?? new Date().toISOString()
    const updated = await addIncidentTimelineApi(selected.value.incidentId, { at, content: timeline.content.trim() })
    list.value = list.value.map((i) => (i.incidentId === updated.incidentId ? updated : i))
    Object.assign(timeline, { at: '', content: '' })
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    timeline.posting = false
  }
}

const durationLabel = (m: number | null) => {
  if (m === null || m === undefined) return t('sm.incident.ongoing')
  return m >= 60 ? t('sm.incident.durationH', { h: Math.floor(m / 60), m: m % 60 }) : t('sm.incident.durationM', { m })
}

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(SM_EDIT_ROLES))
const filtered = computed(() => list.value.filter((i) => !statusFilter.value || i.status === statusFilter.value))
const selected = computed(() => list.value.find((i) => i.incidentId === selectedId.value) ?? null)
const systemName = (id: string) => projectStore.systems.find((s) => s.systemId === id)?.name ?? id
const systemItems = computed(() => projectStore.systems.map((s) => ({ title: s.name, value: s.systemId })))
const statusItems = computed(() => (['OPEN', 'RECOVERED', 'CLOSED'] as const).map((s) => ({ title: t(`sm.incidentStatus.${s}`), value: s })))
const gradeItems = computed(() => [1, 2, 3, 4].map((g) => ({ title: t('sm.incident.gradeN', { n: g }), value: g })))
const sortedTimeline = computed(() => [...(selected.value?.timeline ?? [])].sort((a, b) => a.at.localeCompare(b.at)))
const gradeColor = (g: number) => (g === 1 ? 'error' : g === 2 ? 'warning' : g === 3 ? 'info' : 'secondary')

watch(() => route.query.id, (id) => { if (typeof id === 'string') selectedId.value = id }, { immediate: true })
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.sm.incident')" :subtitle="t('sm.incident.subtitle')">
      <VBtn v-if="canEdit && projectId" color="primary" size="small" prepend-icon="ri-add-line" data-test="incident-add" @click="openForm(null)">{{ t('sm.incident.add') }}</VBtn>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <VRow v-else>
      <VCol cols="12" md="5" lg="4">
        <VCard border flat>
          <VCardText class="pb-2"><VSelect v-model="statusFilter" :items="statusItems" :label="t('sm.col.status')" clearable hide-details /></VCardText>
          <VProgressLinear v-if="loading" indeterminate color="primary" />
          <VAlert v-if="error" type="error" variant="tonal" density="compact" class="ma-3">{{ error }}</VAlert>
          <VList density="compact" nav data-test="incident-list">
            <VListItem v-for="i in filtered" :key="i.incidentId" :active="i.incidentId === selectedId" color="primary" @click="selectedId = i.incidentId">
              <template #prepend><VAvatar :color="gradeColor(i.grade)" size="28" class="text-caption font-weight-bold">{{ i.grade }}</VAvatar></template>
              <VListItemTitle>{{ i.title }}</VListItemTitle>
              <VListItemSubtitle>
                <span class="font-mono">{{ i.incidentNo }}</span> · {{ formatDateTime(i.startedAt) }} · {{ durationLabel(i.durationMinutes) }}
              </VListItemSubtitle>
              <template #append><AppStatusChip :status="i.status" prefix="sm.incidentStatus" size="x-small" /></template>
            </VListItem>
            <VListItem v-if="!filtered.length && !loading" :title="t('common.noData')" disabled />
          </VList>
        </VCard>
      </VCol>
      <VCol cols="12" md="7" lg="8">
        <VCard v-if="selected" border flat data-test="incident-detail">
          <VCardTitle class="d-flex flex-wrap align-center ga-2">
            <span class="font-mono text-body-2">{{ selected.incidentNo }}</span>
            <VChip :color="gradeColor(selected.grade)" size="x-small" variant="flat" label>{{ t('sm.incident.gradeN', { n: selected.grade }) }}</VChip>
            <AppStatusChip :status="selected.status" prefix="sm.incidentStatus" size="x-small" />
            <VSpacer />
            <ExcelExportButton dataset-id="incident-report" :file-name="`${t('sm.incident.report')}_${selected.incidentNo}`" :params="{ incidentId: selected.incidentId }" :label="t('sm.incident.report')" />
            <VBtn v-if="canEdit" icon variant="text" size="small" :aria-label="t('common.edit')" @click="openForm(selected)"><VIcon icon="ri-edit-line" /></VBtn>
          </VCardTitle>
          <VCardText>
            <div class="text-subtitle-1 font-weight-medium mb-2">{{ selected.title }}</div>
            <VRow dense class="mb-2 text-body-2">
              <VCol cols="6" sm="3"><div class="text-caption text-muted">{{ t('sm.incident.startedAt') }}</div>{{ formatDateTime(selected.startedAt) }}</VCol>
              <VCol cols="6" sm="3"><div class="text-caption text-muted">{{ t('sm.incident.detectedAt') }}</div>{{ formatDateTime(selected.detectedAt) }}</VCol>
              <VCol cols="6" sm="3"><div class="text-caption text-muted">{{ t('sm.incident.recoveredAt') }}</div>{{ formatDateTime(selected.recoveredAt) }}</VCol>
              <VCol cols="6" sm="3"><div class="text-caption text-muted">{{ t('sm.incident.duration') }}</div><b>{{ durationLabel(selected.durationMinutes) }}</b></VCol>
            </VRow>
            <div class="text-caption text-muted">{{ t('sm.incident.systems') }}</div>
            <div class="mb-2">
              <VChip v-for="s in selected.systemIds" :key="s" size="small" variant="outlined" class="mr-1">{{ systemName(s) }}</VChip>
              <span v-if="!selected.systemIds.length">-</span>
            </div>
            <div class="text-caption text-muted">{{ t('sm.incident.impact') }}</div>
            <p class="text-body-2 pre mb-3">{{ selected.impact || '-' }}</p>

            <div class="text-subtitle-2 mb-2">{{ t('sm.incident.timeline') }}</div>
            <VTimeline density="compact" side="end" truncate-line="both" data-test="incident-timeline">
              <VTimelineItem v-for="(e, i) in sortedTimeline" :key="i" size="x-small" dot-color="primary">
                <div class="text-caption font-mono text-muted">{{ formatDateTime(e.at) }}</div>
                <div class="text-body-2 pre">{{ e.content }}</div>
              </VTimelineItem>
            </VTimeline>
            <div v-if="canEdit" class="d-flex flex-wrap ga-2 mb-4">
              <VTextField v-model="timeline.at" type="datetime-local" :label="t('sm.incident.at')" hide-details class="tl-at" />
              <VTextField v-model="timeline.content" :label="t('sm.incident.addTimeline')" hide-details class="flex-grow-1" data-test="incident-timeline-input" @keyup.enter="addTimeline" />
              <VBtn color="primary" :loading="timeline.posting" :disabled="!timeline.content.trim()" @click="addTimeline">{{ t('common.add') }}</VBtn>
            </div>

            <VRow dense>
              <VCol cols="12" md="4">
                <div class="text-caption text-muted">{{ t('sm.incident.cause') }}</div>
                <p class="text-body-2 pre">{{ selected.cause || '-' }}</p>
              </VCol>
              <VCol cols="12" md="4">
                <div class="text-caption text-muted">{{ t('sm.incident.rca') }}</div>
                <p class="text-body-2 pre">{{ selected.rca || '-' }}</p>
              </VCol>
              <VCol cols="12" md="4">
                <div class="text-caption text-muted">{{ t('sm.incident.prevention') }}</div>
                <p class="text-body-2 pre">{{ selected.prevention || '-' }}</p>
              </VCol>
            </VRow>
            <div v-if="selected.srIds.length" class="text-caption text-muted mt-2">
              {{ t('sm.incident.relatedSrs') }}
              <RouterLink v-for="id in selected.srIds" :key="id" :to="{ path: '/sm/srs', query: { id } }" class="ml-2">{{ id }}</RouterLink>
            </div>
          </VCardText>
        </VCard>
        <VAlert v-else type="info" variant="tonal">{{ t('sm.incident.pick') }}</VAlert>
      </VCol>
    </VRow>

    <VDialog v-model="form.open" max-width="720" scrollable>
      <VCard :title="form.editing ? t('sm.incident.edit') : t('sm.incident.add')">
        <VCardText>
          <VAlert v-if="form.error" type="error" variant="tonal" density="compact" class="mb-3">{{ form.error }}</VAlert>
          <VRow dense>
            <VCol cols="12"><VTextField v-model="form.data.title" :label="t('sm.col.title')" data-test="incident-title" /></VCol>
            <VCol cols="6" sm="3"><VSelect v-model="form.data.grade" :items="gradeItems" :label="t('sm.incident.grade')" /></VCol>
            <VCol cols="6" sm="3"><VSelect v-model="form.data.status" :items="statusItems" :label="t('sm.col.status')" /></VCol>
            <VCol cols="12" sm="6"><VSelect v-model="form.data.systemIds" :items="systemItems" :label="t('sm.incident.systems')" multiple chips /></VCol>
            <VCol cols="12" sm="4"><VTextField v-model="form.data.startedAt" type="datetime-local" :label="t('sm.incident.startedAt')" /></VCol>
            <VCol cols="12" sm="4"><VTextField :model-value="form.data.detectedAt ?? ''" type="datetime-local" :label="t('sm.incident.detectedAt')" @update:model-value="form.data.detectedAt = $event || null" /></VCol>
            <VCol cols="12" sm="4"><VTextField :model-value="form.data.recoveredAt ?? ''" type="datetime-local" :label="t('sm.incident.recoveredAt')" @update:model-value="form.data.recoveredAt = $event || null" /></VCol>
            <VCol cols="12"><VTextarea v-model="form.data.impact" :label="t('sm.incident.impact')" rows="2" auto-grow /></VCol>
            <VCol cols="12" md="4"><VTextarea :model-value="form.data.cause ?? ''" :label="t('sm.incident.cause')" rows="3" auto-grow @update:model-value="form.data.cause = $event || null" /></VCol>
            <VCol cols="12" md="4"><VTextarea :model-value="form.data.rca ?? ''" :label="t('sm.incident.rca')" rows="3" auto-grow @update:model-value="form.data.rca = $event || null" /></VCol>
            <VCol cols="12" md="4"><VTextarea :model-value="form.data.prevention ?? ''" :label="t('sm.incident.prevention')" rows="3" auto-grow @update:model-value="form.data.prevention = $event || null" /></VCol>
          </VRow>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="form.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="form.saving" data-test="incident-save" @click="save">{{ t('common.save') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
  </VContainer>
</template>

<style scoped>
.pre {
  white-space: pre-wrap;
}

.tl-at {
  max-width: 230px;
}
</style>
