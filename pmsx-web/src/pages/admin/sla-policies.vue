<route lang="yaml">
meta:
  roles: [SYS_ADMIN, PMO, PM, OPERATOR]
</route>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getCodesApi } from '@/api/codeApi'
import { createSlaPolicyApi, deleteSlaPolicyApi, getSlaPoliciesApi, updateSlaPolicyApi } from '@/api/smApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { Code } from '@/types/common'
import { SR_PRIORITIES, SR_TYPES, type SlaPolicy } from '@/types/r3'
import { SLA_POLICY_EDIT_ROLES } from '@/utils/permission'
import { formatMinutes } from '@/utils/sm'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

type PolicyForm = Omit<SlaPolicy, 'policyId'>

// SCR-CM-007 SLA 정책·업무 캘린더 (FR-SM-004) — 유형·우선순위별 응답/해결 목표, 업무시간, 공휴일(HOLIDAY 코드)
const { t } = useI18n()
const auth = useAuthStore()

const policies = ref<SlaPolicy[]>([])
const holidays = ref<Code[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const form = reactive({
  open: false, editing: null as SlaPolicy | null, data: {} as PolicyForm,
  responseH: 0, responseM: 0, resolveH: 0, resolveM: 0, saving: false, error: null as string | null,
})
const removeTarget = ref<SlaPolicy | null>(null)

// ─────────── 헬퍼 ───────────
const load = async () => {
  if (!projectId.value) return
  loading.value = true
  error.value = null
  try {
    const [p, h] = await Promise.all([getSlaPoliciesApi(projectId.value), getCodesApi('HOLIDAY').catch(() => [] as Code[])])
    policies.value = [...p].sort((a, b) => a.order - b.order)
    holidays.value = h.filter((c) => c.useYn).sort((a, b) => a.code.localeCompare(b.code))
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(() => load())

const durText = (minutes: number) => {
  const { days, hours, mins } = formatMinutes(minutes)
  return [days ? t('sm.sla.unit.day', { n: days }) : '', hours ? t('sm.sla.unit.hour', { n: hours }) : '', mins ? t('sm.sla.unit.min', { n: mins }) : '']
    .filter(Boolean).join(' ') || t('sm.sla.unit.min', { n: 0 })
}

const openForm = (p: SlaPolicy | null) => {
  form.editing = p
  form.data = p
    ? { name: p.name, types: [...p.types], priorities: [...p.priorities], responseMinutes: p.responseMinutes, resolveMinutes: p.resolveMinutes, businessHours: { ...p.businessHours, days: [...p.businessHours.days] }, twentyFourSeven: p.twentyFourSeven, order: p.order }
    : { name: '', types: [], priorities: [], responseMinutes: 240, resolveMinutes: 1440, businessHours: { start: '09:00', end: '18:00', days: [1, 2, 3, 4, 5] }, twentyFourSeven: false, order: (policies.value.at(-1)?.order ?? 0) + 1 }
  Object.assign(form, {
    responseH: Math.floor(form.data.responseMinutes / 60), responseM: form.data.responseMinutes % 60,
    resolveH: Math.floor(form.data.resolveMinutes / 60), resolveM: form.data.resolveMinutes % 60,
    open: true, saving: false, error: null,
  })
}

const save = async () => {
  if (!projectId.value) return
  const response = Number(form.responseH) * 60 + Number(form.responseM)
  const resolve = Number(form.resolveH) * 60 + Number(form.resolveM)
  if (!form.data.name.trim()) { form.error = t('validation.required'); return }
  if (response <= 0 || resolve <= 0 || resolve < response) { form.error = t('sm.slaPolicy.invalidTimes'); return }
  if (!form.data.twentyFourSeven && form.data.businessHours.start >= form.data.businessHours.end) { form.error = t('sm.slaPolicy.invalidHours'); return }
  form.saving = true
  try {
    const payload: PolicyForm = { ...form.data, responseMinutes: response, resolveMinutes: resolve }
    if (form.editing) await updateSlaPolicyApi(form.editing.policyId, payload)
    else await createSlaPolicyApi(projectId.value, payload)
    form.open = false
    showSuccessToast('common.saved')
    void load()
  } catch (e) {
    form.error = (e as ApiError).message ?? t('common.saveFailed')
  } finally {
    form.saving = false
  }
}

const remove = async () => {
  if (!removeTarget.value) return
  try {
    await deleteSlaPolicyApi(removeTarget.value.policyId)
    removeTarget.value = null
    void load()
  } catch {
    showErrorToast('common.deleteFailed')
  }
}

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(SLA_POLICY_EDIT_ROLES))
const typeItems = computed(() => SR_TYPES.map((v) => ({ title: t(`sm.srType.${v}`), value: v })))
const priorityItems = computed(() => SR_PRIORITIES.map((v) => ({ title: t(`sm.priority.${v}`), value: v })))
const dayItems = computed(() => [1, 2, 3, 4, 5, 6, 0].map((d) => ({ title: t(`sm.weekday.${d}`), value: d })))
const headers = computed(() => [
  { title: t('sm.slaPolicy.order'), key: 'order', width: 60 },
  { title: t('sm.slaPolicy.name'), key: 'name' },
  { title: t('sm.slaPolicy.match'), key: 'match', sortable: false },
  { title: t('sm.slaPolicy.response'), key: 'responseMinutes', width: 120 },
  { title: t('sm.slaPolicy.resolve'), key: 'resolveMinutes', width: 120 },
  { title: t('sm.slaPolicy.hours'), key: 'hours', sortable: false, width: 170 },
  { title: '', key: 'actions', sortable: false, width: 90, align: 'end' as const },
])
const hoursText = (p: SlaPolicy) => (p.twentyFourSeven ? '24×7' : `${p.businessHours.days.map((d) => t(`sm.weekday.${d}`)).join('')} ${p.businessHours.start}~${p.businessHours.end}`)
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.admin.sla')" :subtitle="t('sm.slaPolicy.subtitle')">
      <VBtn v-if="canEdit && projectId" color="primary" size="small" prepend-icon="ri-add-line" data-test="sla-policy-add" @click="openForm(null)">{{ t('sm.slaPolicy.add') }}</VBtn>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <VRow v-else>
      <VCol cols="12" lg="8">
        <VCard border flat :title="t('sm.slaPolicy.list')" :subtitle="t('sm.slaPolicy.matchHint')">
          <VAlert v-if="error" type="error" variant="tonal" density="compact" class="ma-3">{{ error }}</VAlert>
          <VDataTable :headers="headers" :items="policies" :loading="loading" item-value="policyId" density="compact" :items-per-page="-1" hide-default-footer data-test="sla-policy-table">
            <template #item.match="{ item }">
              <VChip v-for="ty in item.types" :key="ty" size="x-small" variant="tonal" class="mr-1">{{ t(`sm.srType.${ty}`) }}</VChip>
              <VChip v-for="p in item.priorities" :key="p" size="x-small" variant="outlined" class="mr-1">{{ p }}</VChip>
              <span v-if="!item.types.length && !item.priorities.length" class="text-caption text-muted">{{ t('sm.slaPolicy.matchAll') }}</span>
            </template>
            <template #item.responseMinutes="{ item }">{{ durText(item.responseMinutes) }}</template>
            <template #item.resolveMinutes="{ item }">{{ durText(item.resolveMinutes) }}</template>
            <template #item.hours="{ item }"><span class="text-caption">{{ hoursText(item) }}</span></template>
            <template #item.actions="{ item }">
              <template v-if="canEdit">
                <VBtn icon="ri-edit-line" variant="text" size="x-small" :aria-label="t('common.edit')" @click="openForm(item)" />
                <VBtn icon="ri-delete-bin-line" variant="text" size="x-small" :aria-label="t('common.delete')" @click="removeTarget = item" />
              </template>
            </template>
          </VDataTable>
        </VCard>
      </VCol>
      <VCol cols="12" lg="4">
        <VCard border flat :title="t('sm.slaPolicy.calendar')" :subtitle="t('sm.slaPolicy.calendarHint')">
          <VList density="compact" data-test="sla-holidays">
            <VListItem v-for="h in holidays" :key="h.code" :title="h.name" :subtitle="h.code" />
            <VListItem v-if="!holidays.length" :title="t('sm.slaPolicy.noHolidays')" disabled />
          </VList>
          <VCardActions v-if="auth.hasAnyRole(['SYS_ADMIN', 'PM'])">
            <VBtn size="small" variant="tonal" prepend-icon="ri-calendar-event-line" :to="{ path: '/admin/codes', query: { group: 'HOLIDAY' } }">{{ t('sm.slaPolicy.editHolidays') }}</VBtn>
          </VCardActions>
        </VCard>
      </VCol>
    </VRow>

    <VDialog v-model="form.open" max-width="620" scrollable>
      <VCard :title="form.editing ? t('sm.slaPolicy.edit') : t('sm.slaPolicy.add')">
        <VCardText>
          <VAlert v-if="form.error" type="error" variant="tonal" density="compact" class="mb-3">{{ form.error }}</VAlert>
          <VRow dense>
            <VCol cols="9"><VTextField v-model="form.data.name" :label="t('sm.slaPolicy.name')" data-test="sla-policy-name" /></VCol>
            <VCol cols="3"><VTextField v-model.number="form.data.order" type="number" min="1" :label="t('sm.slaPolicy.order')" /></VCol>
            <VCol cols="12" sm="6"><VSelect v-model="form.data.types" :items="typeItems" :label="t('sm.slaPolicy.types')" :hint="t('sm.slaPolicy.emptyAll')" persistent-hint multiple chips /></VCol>
            <VCol cols="12" sm="6"><VSelect v-model="form.data.priorities" :items="priorityItems" :label="t('sm.slaPolicy.priorities')" :hint="t('sm.slaPolicy.emptyAll')" persistent-hint multiple chips /></VCol>
            <VCol cols="12" class="text-subtitle-2 mt-2">{{ t('sm.slaPolicy.response') }}</VCol>
            <VCol cols="6"><VTextField v-model.number="form.responseH" type="number" min="0" :suffix="t('sm.slaPolicy.hourSuffix')" /></VCol>
            <VCol cols="6"><VTextField v-model.number="form.responseM" type="number" min="0" max="59" :suffix="t('sm.slaPolicy.minSuffix')" /></VCol>
            <VCol cols="12" class="text-subtitle-2">{{ t('sm.slaPolicy.resolve') }}</VCol>
            <VCol cols="6"><VTextField v-model.number="form.resolveH" type="number" min="0" :suffix="t('sm.slaPolicy.hourSuffix')" /></VCol>
            <VCol cols="6"><VTextField v-model.number="form.resolveM" type="number" min="0" max="59" :suffix="t('sm.slaPolicy.minSuffix')" /></VCol>
            <VCol cols="12"><VSwitch v-model="form.data.twentyFourSeven" :label="t('sm.slaPolicy.twentyFourSeven')" color="primary" hide-details /></VCol>
            <template v-if="!form.data.twentyFourSeven">
              <VCol cols="6"><VTextField v-model="form.data.businessHours.start" type="time" :label="t('sm.slaPolicy.start')" /></VCol>
              <VCol cols="6"><VTextField v-model="form.data.businessHours.end" type="time" :label="t('sm.slaPolicy.end')" /></VCol>
              <VCol cols="12"><VSelect v-model="form.data.businessHours.days" :items="dayItems" :label="t('sm.slaPolicy.days')" multiple chips /></VCol>
            </template>
            <VCol cols="12" class="text-caption text-muted">{{ t('sm.slaPolicy.businessHint') }}</VCol>
          </VRow>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="form.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="form.saving" data-test="sla-policy-save" @click="save">{{ t('common.save') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>

    <ConfirmDialog
      :model-value="!!removeTarget"
      :title="t('common.delete')"
      :message="t('sm.slaPolicy.removeMessage', { name: removeTarget?.name ?? '' })"
      @update:model-value="(v: boolean) => { if (!v) removeTarget = null }"
      @confirm="remove"
    />
  </VContainer>
</template>
