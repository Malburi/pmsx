<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { createProjectApi, getMethodologiesApi, updateProjectApi } from '@/api/scheduleApi'
import UserLookupField from '@/components/common/UserLookupField.vue'
import type { ApiError, Project } from '@/types/api'
import type { Methodology, ProjectPayload } from '@/types/r2'

// 프로젝트 등록·수정 (docs/12 §1, v0.2 — 조회 응답의 계약 MM·PM 으로 수정 폼을 채운다)
const props = defineProps<{ modelValue: boolean; project: Project | null }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; saved: [p: Project] }>()

const { t } = useI18n()
const methodologies = ref<Methodology[]>([])
const form = reactive({ data: {} as ProjectPayload, saving: false, errors: {} as Record<string, string>, error: null as string | null })

const reset = async () => {
  const p = props.project
  form.data = p
    ? { type: p.type, name: p.name, customer: p.customer, startDate: p.startDate, endDate: p.endDate, contractMm: p.contractMm ?? 0, pmUserId: p.pmUserId ?? '', methodologyId: null }
    : { type: 'SI', name: '', customer: '', startDate: '', endDate: '', contractMm: 0, pmUserId: '', methodologyId: null }
  Object.assign(form, { saving: false, errors: {}, error: null })
  if (!methodologies.value.length) methodologies.value = await getMethodologiesApi().catch(() => [])
}

const validate = () => {
  const e: Record<string, string> = {}
  if (!form.data.name.trim()) e.name = t('validation.required')
  if (!form.data.customer.trim()) e.customer = t('validation.required')
  if (!form.data.startDate) e.startDate = t('validation.required')
  if (!form.data.endDate) e.endDate = t('validation.required')
  if (form.data.startDate && form.data.endDate && form.data.startDate > form.data.endDate) e.endDate = t('validation.dateOrder')
  if (!form.data.pmUserId) e.pmUserId = t('validation.required')
  form.errors = e
  return !Object.keys(e).length
}

const save = async () => {
  if (!validate()) return
  form.saving = true
  form.error = null
  try {
    const payload = { ...form.data, contractMm: Number(form.data.contractMm) || 0 }
    const saved = props.project ? await updateProjectApi(props.project.projectId, payload) : await createProjectApi(payload)
    emit('saved', saved)
    emit('update:modelValue', false)
  } catch (e) {
    const err = e as ApiError
    form.errors = err.fields ?? {}
    form.error = err.fields ? null : (err.message ?? t('common.saveFailed'))
  } finally {
    form.saving = false
  }
}

const methodologyItems = computed(() => methodologies.value.map((m) => ({ title: m.name, value: m.methodologyId })))
const typeItems = computed(() => (['SI', 'SM'] as const).map((v) => ({ title: v, value: v })))
const pmInitial = computed(() => (props.project?.pmUserId ? { userId: props.project.pmUserId, name: props.project.pmName ?? props.project.pmUserId } : null))

watch(() => props.modelValue, (open) => { if (open) void reset() })
</script>

<template>
  <VDialog :model-value="modelValue" max-width="560" @update:model-value="emit('update:modelValue', $event)">
    <VCard :title="project ? t('admin.projects.edit') : t('admin.projects.create')">
      <VCardText>
        <VRow dense>
          <VCol cols="4" sm="3"><VSelect v-model="form.data.type" :items="typeItems" :label="t('admin.projects.type')" /></VCol>
          <VCol cols="8" sm="9"><VTextField v-model="form.data.name" :label="t('admin.projects.name')" :error-messages="form.errors.name" data-test="project-name" /></VCol>
          <VCol cols="12" sm="6"><VTextField v-model="form.data.customer" :label="t('admin.projects.customer')" :error-messages="form.errors.customer" /></VCol>
          <VCol cols="12" sm="6"><VTextField v-model.number="form.data.contractMm" type="number" step="0.1" :label="t('admin.projects.contractMm')" suffix="MM" /></VCol>
          <VCol cols="6"><VTextField v-model="form.data.startDate" type="date" :label="t('admin.projects.startDate')" :error-messages="form.errors.startDate" /></VCol>
          <VCol cols="6"><VTextField v-model="form.data.endDate" type="date" :label="t('admin.projects.endDate')" :error-messages="form.errors.endDate" /></VCol>
          <VCol cols="12"><UserLookupField v-model="form.data.pmUserId" :label="t('admin.projects.pm')" :initial="pmInitial" :clearable="false" /></VCol>
          <VCol v-if="form.errors.pmUserId" cols="12"><div class="text-caption text-error">{{ form.errors.pmUserId }}</div></VCol>
          <VCol v-if="!project" cols="12">
            <VSelect v-model="form.data.methodologyId" :items="methodologyItems" :label="t('admin.projects.methodology')" :hint="t('admin.projects.methodologyHint')" persistent-hint clearable />
          </VCol>
        </VRow>
        <VAlert v-if="form.error" type="error" variant="tonal" density="compact" class="mt-2">{{ form.error }}</VAlert>
      </VCardText>
      <VCardActions>
        <VSpacer />
        <VBtn variant="text" @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</VBtn>
        <VBtn color="primary" :loading="form.saving" data-test="project-save" @click="save">{{ t('common.save') }}</VBtn>
      </VCardActions>
    </VCard>
  </VDialog>
</template>
