<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { createDefectApi, updateDefectApi } from '@/api/qualityApi'
import UserLookupField from '@/components/common/UserLookupField.vue'
import { useProjectStore } from '@/stores/project'
import type { ApiError } from '@/types/api'
import type { Defect, DefectPayload, DefectSeverity } from '@/types/r2'

// 결함 등록·수정 — 결함 화면, 테스트 FAIL, 뷰어 핫스팟(FR-QA-008)에서 함께 쓴다
const props = withDefaults(defineProps<{
  modelValue: boolean
  defect?: Defect | null
  /** 새 결함의 기본값 (화면·요소·케이스에서 바로 등록할 때) */
  preset?: Partial<DefectPayload> & { context?: string }
}>(), { defect: null, preset: () => ({}) })
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; saved: [d: Defect] }>()

const { t } = useI18n()
const store = useProjectStore()
const SEVERITIES: DefectSeverity[] = ['CRITICAL', 'MAJOR', 'MINOR', 'TRIVIAL']
const form = reactive({ data: {} as DefectPayload, saving: false, errors: {} as Record<string, string>, error: null as string | null })

const reset = () => {
  const d = props.defect
  form.data = d
    ? { title: d.title, description: d.description, severity: d.severity, priority: d.priority, assigneeId: d.assignee?.userId ?? null, caseId: d.caseId, screenId: d.screenId, elementId: d.elementId }
    : { title: '', description: '', severity: 'MAJOR', priority: 'M', assigneeId: null, caseId: null, screenId: null, elementId: null, ...props.preset }
  Object.assign(form, { saving: false, errors: {}, error: null })
}

const save = async () => {
  form.errors = form.data.title.trim() ? {} : { title: t('validation.required') }
  if (Object.keys(form.errors).length || !store.currentProjectId) return
  form.saving = true
  form.error = null
  try {
    const saved = props.defect ? await updateDefectApi(props.defect.defectId, form.data) : await createDefectApi(store.currentProjectId, form.data)
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

const severityItems = computed(() => SEVERITIES.map((s) => ({ title: t(`qa.severity.${s}`), value: s })))
const priorityItems = computed(() => (['H', 'M', 'L'] as const).map((p) => ({ title: t(`qa.priority.${p}`), value: p })))

watch(() => props.modelValue, (open) => { if (open) reset() })
</script>

<template>
  <VDialog :model-value="modelValue" max-width="560" @update:model-value="emit('update:modelValue', $event)">
    <VCard :title="defect ? t('qa.defect.edit', { no: defect.defectNo }) : t('qa.defect.add')">
      <VCardText>
        <VAlert v-if="preset.context && !defect" type="info" variant="tonal" density="compact" class="mb-3">{{ preset.context }}</VAlert>
        <VTextField v-model="form.data.title" :label="t('qa.col.title')" :error-messages="form.errors.title" class="mb-2" data-test="defect-title" />
        <VTextarea v-model="form.data.description" :label="t('qa.defect.description')" :placeholder="t('qa.defect.descriptionPh')" rows="4" class="mb-2" />
        <VRow dense>
          <VCol cols="6" sm="4"><VSelect v-model="form.data.severity" :items="severityItems" :label="t('qa.col.severity')" /></VCol>
          <VCol cols="6" sm="3"><VSelect v-model="form.data.priority" :items="priorityItems" :label="t('qa.col.priority')" /></VCol>
          <VCol cols="12" sm="5"><UserLookupField v-model="form.data.assigneeId" :label="t('qa.col.assignee')" :initial="defect?.assignee ?? null" /></VCol>
        </VRow>
        <VAlert v-if="form.error" type="error" variant="tonal" density="compact" class="mt-2">{{ form.error }}</VAlert>
      </VCardText>
      <VCardActions>
        <VSpacer />
        <VBtn variant="text" @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</VBtn>
        <VBtn color="primary" :loading="form.saving" data-test="defect-save" @click="save">{{ t('common.save') }}</VBtn>
      </VCardActions>
    </VCard>
  </VDialog>
</template>
