<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { createIssueApi, updateIssueApi } from '@/api/qualityApi'
import UserLookupField from '@/components/common/UserLookupField.vue'
import { useProjectStore } from '@/stores/project'
import type { ApiError } from '@/types/api'
import type { Issue, IssuePayload } from '@/types/r2'
import { ISSUE_STATUSES } from '@/utils/quality'

// 이슈 등록·수정 — 이슈 화면과 뷰어 핫스팟에서 함께 쓴다
const props = withDefaults(defineProps<{ modelValue: boolean; issue?: Issue | null; preset?: Partial<IssuePayload> & { context?: string } }>(), { issue: null, preset: () => ({}) })
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; saved: [i: Issue] }>()

const { t } = useI18n()
const store = useProjectStore()
const form = reactive({ data: {} as IssuePayload, saving: false, errors: {} as Record<string, string>, error: null as string | null })

const reset = () => {
  const i = props.issue
  form.data = i
    ? { title: i.title, description: i.description, priority: i.priority, status: i.status, ownerId: i.owner?.userId ?? null, dueDate: i.dueDate, elementId: i.elementId }
    : { title: '', description: '', priority: 'M', ownerId: null, dueDate: null, elementId: null, ...props.preset }
  Object.assign(form, { saving: false, errors: {}, error: null })
}

const save = async () => {
  form.errors = form.data.title.trim() ? {} : { title: t('validation.required') }
  if (Object.keys(form.errors).length || !store.currentProjectId) return
  form.saving = true
  try {
    const saved = props.issue ? await updateIssueApi(props.issue.issueId, form.data) : await createIssueApi(store.currentProjectId, form.data)
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

const priorityItems = computed(() => (['H', 'M', 'L'] as const).map((p) => ({ title: t(`qa.priority.${p}`), value: p })))
const statusItems = computed(() => ISSUE_STATUSES.map((s) => ({ title: t(`qa.issueStatus.${s}`), value: s })))

watch(() => props.modelValue, (open) => { if (open) reset() })
</script>

<template>
  <VDialog :model-value="modelValue" max-width="560" @update:model-value="emit('update:modelValue', $event)">
    <VCard :title="issue ? t('qa.issue.edit', { no: issue.issueNo }) : t('qa.issue.add')">
      <VCardText>
        <VAlert v-if="preset.context && !issue" type="info" variant="tonal" density="compact" class="mb-3">{{ preset.context }}</VAlert>
        <VTextField v-model="form.data.title" :label="t('qa.col.title')" :error-messages="form.errors.title" class="mb-2" data-test="issue-title" />
        <VTextarea v-model="form.data.description" :label="t('qa.issue.description')" rows="4" class="mb-2" />
        <VRow dense>
          <VCol cols="6" sm="3"><VSelect v-model="form.data.priority" :items="priorityItems" :label="t('qa.col.priority')" /></VCol>
          <VCol v-if="issue" cols="6" sm="3"><VSelect v-model="form.data.status" :items="statusItems" :label="t('qa.col.status')" /></VCol>
          <VCol cols="12" :sm="issue ? 3 : 5"><UserLookupField v-model="form.data.ownerId" :label="t('qa.col.owner')" :initial="issue?.owner ?? null" /></VCol>
          <VCol cols="12" :sm="issue ? 3 : 4"><VTextField v-model="form.data.dueDate" type="date" :label="t('qa.col.due')" /></VCol>
        </VRow>
        <VAlert v-if="form.error" type="error" variant="tonal" density="compact" class="mt-2">{{ form.error }}</VAlert>
      </VCardText>
      <VCardActions>
        <VSpacer />
        <VBtn variant="text" @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</VBtn>
        <VBtn color="primary" :loading="form.saving" data-test="issue-save" @click="save">{{ t('common.save') }}</VBtn>
      </VCardActions>
    </VCard>
  </VDialog>
</template>
