<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { createSrApi, updateSrApi } from '@/api/smApi'
import UserLookupField from '@/components/common/UserLookupField.vue'
import ScreenPickerDialog from '@/components/sm/ScreenPickerDialog.vue'
import type { ApiError, SystemSummary } from '@/types/api'
import { SR_PRIORITIES, SR_TYPES, type ScreenPick, type Sr, type SrPayload } from '@/types/r3'

// SR 수기 등록·수정 (FR-SM-001·003) — 대상 화면은 메뉴 트리에서 고른다
const props = defineProps<{ modelValue: boolean; projectId: string | null; sr: Sr | null; systems: SystemSummary[]; canAssign: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; saved: [sr: Sr] }>()

const { t } = useI18n()
const form = reactive({
  data: {} as SrPayload,
  screenLabel: '' as string,
  saving: false,
  errors: {} as Record<string, string>,
  error: null as string | null,
})
const pickerOpen = ref(false)

// ─────────── 헬퍼 ───────────
const reset = () => {
  const s = props.sr
  form.data = s
    ? {
      type: s.type, priority: s.priority, title: s.title, description: s.description, channel: s.channel,
      requester: { name: s.requester.name, org: s.requester.org, contact: s.requester.contact },
      assigneeId: s.assignee?.userId ?? null, systemId: s.systemId, screenId: s.screenId, elementId: s.elementId,
    }
    : {
      type: 'INQUIRY', priority: 'P3', title: '', description: '', channel: 'MANUAL',
      requester: { name: '', org: null, contact: null }, assigneeId: null, systemId: null, screenId: null, elementId: null,
    }
  form.screenLabel = s?.screenName ?? ''
  Object.assign(form, { saving: false, errors: {}, error: null })
}

const onPick = (p: ScreenPick) => {
  Object.assign(form.data, { systemId: p.systemId, screenId: p.screenId, elementId: p.elementId })
  form.screenLabel = p.elementLabel ? `${p.screenName} › ${p.elementLabel}` : p.screenName
}

const clearScreen = () => {
  Object.assign(form.data, { systemId: null, screenId: null, elementId: null })
  form.screenLabel = ''
}

const validate = () => {
  const e: Record<string, string> = {}
  if (!form.data.title.trim()) e.title = t('validation.required')
  if (!form.data.description.trim()) e.description = t('validation.required')
  if (!form.data.requester?.name.trim()) e.requester = t('validation.required')
  form.errors = e
  return !Object.keys(e).length
}

const save = async () => {
  if (!validate() || !props.projectId) return
  form.saving = true
  form.error = null
  try {
    const payload: SrPayload = { ...form.data, title: form.data.title.trim(), description: form.data.description.trim() }
    const saved = props.sr ? await updateSrApi(props.sr.srId, payload) : await createSrApi(props.projectId, payload)
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

// ─────────── computed ───────────
const typeItems = computed(() => SR_TYPES.map((v) => ({ title: t(`sm.srType.${v}`), value: v })))
const priorityItems = computed(() => SR_PRIORITIES.map((v) => ({ title: t(`sm.priority.${v}`), value: v })))
const assigneeInitial = computed(() => props.sr?.assignee ?? null)

watch(() => props.modelValue, (open) => { if (open) reset() })
</script>

<template>
  <VDialog :model-value="modelValue" max-width="640" scrollable @update:model-value="emit('update:modelValue', $event)">
    <VCard :title="sr ? t('sm.sr.edit', { no: sr.srNo }) : t('sm.sr.create')">
      <VCardText>
        <VAlert v-if="form.error" type="error" variant="tonal" density="compact" class="mb-3">{{ form.error }}</VAlert>
        <VRow dense>
          <VCol cols="6" sm="4"><VSelect v-model="form.data.type" :items="typeItems" :label="t('sm.col.type')" data-test="sr-form-type" /></VCol>
          <VCol cols="6" sm="3"><VSelect v-model="form.data.priority" :items="priorityItems" :label="t('sm.col.priority')" /></VCol>
          <VCol cols="12" sm="5">
            <UserLookupField v-if="canAssign" :model-value="form.data.assigneeId ?? null" :label="t('sm.col.assignee')" :initial="assigneeInitial" @update:model-value="form.data.assigneeId = $event" />
          </VCol>
          <VCol cols="12"><VTextField v-model="form.data.title" :label="t('sm.col.title')" :error-messages="form.errors.title" counter="200" maxlength="200" data-test="sr-form-title" /></VCol>
          <VCol cols="12">
            <VTextarea v-model="form.data.description" :label="t('sm.sr.description')" :error-messages="form.errors.description" rows="4" auto-grow data-test="sr-form-desc" />
          </VCol>
          <VCol cols="12" sm="4"><VTextField v-model="form.data.requester!.name" :label="t('sm.sr.requesterName')" :error-messages="form.errors.requester" data-test="sr-form-requester" /></VCol>
          <VCol cols="6" sm="4"><VTextField v-model="form.data.requester!.org" :label="t('sm.sr.requesterOrg')" /></VCol>
          <VCol cols="6" sm="4"><VTextField v-model="form.data.requester!.contact" :label="t('sm.sr.requesterContact')" /></VCol>
          <VCol cols="12">
            <div class="d-flex align-center ga-2">
              <VTextField
                :model-value="form.screenLabel"
                :label="t('sm.sr.targetScreen')"
                :placeholder="t('sm.sr.targetScreenNone')"
                prepend-inner-icon="ri-window-line"
                readonly
                persistent-placeholder
                hide-details
                @click="pickerOpen = true"
              />
              <VBtn variant="tonal" prepend-icon="ri-node-tree" data-test="sr-form-pick" @click="pickerOpen = true">{{ t('sm.sr.pickScreen') }}</VBtn>
              <VBtn v-if="form.data.screenId" icon="ri-close-line" variant="text" size="small" :aria-label="t('common.reset')" @click="clearScreen" />
            </div>
          </VCol>
        </VRow>
      </VCardText>
      <VCardActions>
        <VSpacer />
        <VBtn variant="text" @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</VBtn>
        <VBtn color="primary" :loading="form.saving" data-test="sr-form-save" @click="save">{{ t('common.save') }}</VBtn>
      </VCardActions>
    </VCard>
    <ScreenPickerDialog
      v-model="pickerOpen"
      :systems="systems"
      :initial="{ systemId: form.data.systemId, screenId: form.data.screenId, elementId: form.data.elementId }"
      @pick="onPick"
    />
  </VDialog>
</template>
