<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import {
  createCodeGroupApi, deleteCodeGroupApi, getCodeGroupsApi, getCodesApi, saveCodesApi, updateCodeGroupApi,
} from '@/api/codeApi'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import ExcelImportDialog from '@/components/common/ExcelImportDialog.vue'
import { clearCodeCache } from '@/composables/useCodes'
import type { ApiError } from '@/types/api'
import type { Code, CodeGroup } from '@/types/common'
import { showErrorToast, showSuccessToast } from '@/utils/toast'
import { isCodeValue } from '@/utils/validators'

const props = defineProps<{ readonly: boolean }>()
// ?group=HOLIDAY 처럼 열 그룹을 지정할 수 있다 (SLA 업무 캘린더 → 공휴일 편집)
const route = useRoute()

interface CodeRow extends Code { rowKey: number }

const { t } = useI18n()
const groups = ref<CodeGroup[]>([])
const groupsLoading = ref(false)
const selected = ref<CodeGroup | null>(null)
const rows = ref<CodeRow[]>([])
const original = ref('')
const codesLoading = ref(false)
const saving = ref(false)
const saveError = ref<string | null>(null)
let rowSeq = 0

const groupDialog = ref(false)
const editingGroup = ref<CodeGroup | null>(null)
const groupFormRef = ref<{ validate: () => Promise<{ valid: boolean }> } | null>(null)
const groupForm = reactive({ groupCode: '', name: '', description: '' })
const groupSaving = ref(false)
const groupError = ref<string | null>(null)
const deleteTarget = ref<CodeGroup | null>(null)
const deleting = ref(false)
const pendingGroup = ref<CodeGroup | null>(null)
const importOpen = ref(false)

// ─────────── 헬퍼 ───────────
const required = (v: unknown) => !!String(v ?? '').trim() || t('validation.required')
const codeFormat = (v: string) => isCodeValue(v) || t('validation.codeValue')

const snapshot = (list: Code[]) => JSON.stringify(list.map(({ code, name, sortOrder, useYn, attr1, attr2 }) => ({ code, name, sortOrder, useYn, attr1, attr2 })))

const loadGroups = async () => {
  groupsLoading.value = true
  try {
    groups.value = await getCodeGroupsApi()
    const wanted = typeof route.query.group === 'string' ? groups.value.find((g) => g.groupCode === route.query.group) : undefined
    if (!selected.value && groups.value.length) await openGroup(wanted ?? groups.value[0])
  } catch {
    showErrorToast('common.error.unknown')
  } finally {
    groupsLoading.value = false
  }
}

const openGroup = async (g: CodeGroup) => {
  selected.value = g
  saveError.value = null
  codesLoading.value = true
  try {
    const codes = await getCodesApi(g.groupCode)
    rows.value = codes.map((c) => ({ ...c, rowKey: ++rowSeq }))
    original.value = snapshot(codes)
  } catch {
    rows.value = []
    original.value = snapshot([])
    showErrorToast('common.error.unknown')
  } finally {
    codesLoading.value = false
  }
}

// 저장하지 않은 변경이 있으면 그룹 전환 전에 확인한다
const selectGroup = (g: CodeGroup) => {
  if (g.groupCode === selected.value?.groupCode) return
  if (dirty.value) pendingGroup.value = g
  else void openGroup(g)
}

const discardAndOpen = () => {
  const g = pendingGroup.value
  pendingGroup.value = null
  if (g) void openGroup(g)
}

const addRow = () => {
  const maxOrder = rows.value.reduce((m, r) => Math.max(m, r.sortOrder), 0)
  rows.value.push({ code: '', name: '', sortOrder: maxOrder + 1, useYn: true, attr1: null, attr2: null, rowKey: ++rowSeq })
}

const removeRow = (row: CodeRow) => {
  rows.value = rows.value.filter((r) => r.rowKey !== row.rowKey)
}

const saveCodes = async () => {
  if (!selected.value || hasErrors.value) return
  saving.value = true
  saveError.value = null
  try {
    const payload: Code[] = rows.value.map(({ rowKey: _k, ...c }) => ({ ...c, code: c.code.trim(), name: c.name.trim(), sortOrder: Number(c.sortOrder) || 0 }))
    const saved = await saveCodesApi(selected.value.groupCode, payload)
    rows.value = saved.map((c) => ({ ...c, rowKey: ++rowSeq }))
    original.value = snapshot(saved)
    clearCodeCache([selected.value.groupCode])
    showSuccessToast('admin.codes.saved')
  } catch (e) {
    saveError.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    saving.value = false
  }
}

const openGroupDialog = (g: CodeGroup | null) => {
  editingGroup.value = g
  Object.assign(groupForm, { groupCode: g?.groupCode ?? '', name: g?.name ?? '', description: g?.description ?? '' })
  groupError.value = null
  groupDialog.value = true
}

const saveGroup = async () => {
  const result = await groupFormRef.value?.validate()
  if (!result?.valid) return
  groupSaving.value = true
  groupError.value = null
  const payload: CodeGroup = {
    groupCode: groupForm.groupCode.trim(), name: groupForm.name.trim(),
    description: groupForm.description.trim() || null, system: editingGroup.value?.system ?? false,
  }
  try {
    const saved = editingGroup.value
      ? await updateCodeGroupApi(editingGroup.value.groupCode, payload)
      : await createCodeGroupApi(payload)
    groupDialog.value = false
    await loadGroups()
    const fresh = groups.value.find((g) => g.groupCode === saved.groupCode)
    if (fresh && !editingGroup.value) await openGroup(fresh)
    else if (fresh) selected.value = fresh
    showSuccessToast('admin.codes.groupSaved')
  } catch (e) {
    const err = e as ApiError
    groupError.value = err.code === 'CONFLICT' ? t('admin.codes.groupConflict') : (err.message ?? t('common.error.unknown'))
  } finally {
    groupSaving.value = false
  }
}

const deleteGroup = async () => {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await deleteCodeGroupApi(deleteTarget.value.groupCode)
    if (selected.value?.groupCode === deleteTarget.value.groupCode) {
      selected.value = null
      rows.value = []
      original.value = snapshot([])
    }
    clearCodeCache([deleteTarget.value.groupCode])
    deleteTarget.value = null
    await loadGroups()
  } catch {
    showErrorToast('common.error.unknown')
  } finally {
    deleting.value = false
  }
}

const onImported = () => {
  if (selected.value) {
    clearCodeCache([selected.value.groupCode])
    void openGroup(selected.value)
  }
}

// ─────────── computed ───────────
const dirty = computed(() => snapshot(rows.value) !== original.value)

const rowErrors = computed(() => {
  const seen = new Map<string, number>()
  rows.value.forEach((r) => { const c = r.code.trim(); seen.set(c, (seen.get(c) ?? 0) + 1) })
  return new Map(rows.value.map((r) => {
    const code = r.code.trim()
    const codeErr = !code ? t('validation.required')
      : !isCodeValue(code) ? t('validation.codeValue')
      : (seen.get(code) ?? 0) > 1 ? t('admin.codes.duplicate')
      : null
    const nameErr = r.name.trim() ? null : t('admin.codes.nameRequired')
    return [r.rowKey, { code: codeErr, name: nameErr }]
  }))
})

const fieldError = (rowKey: number, field: 'code' | 'name'): string[] => {
  const e = rowErrors.value.get(rowKey)?.[field]
  return e ? [e] : []
}

const hasErrors = computed(() => [...rowErrors.value.values()].some((e) => !!e.code || !!e.name))

const headers = computed(() => [
  { title: t('admin.codes.code'), key: 'code', width: 180 },
  { title: t('admin.codes.name'), key: 'name' },
  { title: t('admin.codes.sortOrder'), key: 'sortOrder', width: 100 },
  { title: t('admin.codes.useYn'), key: 'useYn', width: 90 },
  { title: t('admin.codes.attr1'), key: 'attr1', width: 140 },
  { title: t('admin.codes.attr2'), key: 'attr2', width: 140 },
  ...(props.readonly ? [] : [{ title: '', key: 'actions', sortable: false, width: 56 }]),
])

// ─────────── 라이프사이클 ───────────
onMounted(loadGroups)
</script>

<template>
  <VRow>
    <VCol cols="12" md="4" lg="3">
      <VCard border flat>
        <VCardTitle class="d-flex align-center">
          {{ t('admin.codes.groups') }}
          <VSpacer />
          <VBtn v-if="!readonly" icon="ri-add-line" size="small" variant="text" :aria-label="t('admin.codes.addGroup')" :title="t('admin.codes.addGroup')" data-test="group-add" @click="openGroupDialog(null)" />
        </VCardTitle>
        <VProgressLinear v-if="groupsLoading" indeterminate color="primary" />
        <VList density="compact" nav class="group-list" data-test="group-list">
          <VListItem
            v-for="g in groups"
            :key="g.groupCode"
            :active="g.groupCode === selected?.groupCode"
            color="primary"
            @click="selectGroup(g)"
          >
            <VListItemTitle>{{ g.name }}</VListItemTitle>
            <VListItemSubtitle class="font-mono">{{ g.groupCode }}</VListItemSubtitle>
            <template #append>
              <VChip v-if="g.system" size="x-small" variant="tonal" class="mr-1">{{ t('admin.codes.system') }}</VChip>
            </template>
          </VListItem>
        </VList>
      </VCard>
    </VCol>

    <VCol cols="12" md="8" lg="9">
      <VCard v-if="selected" border flat>
        <VCardTitle class="d-flex flex-wrap align-center ga-2">
          <span>{{ selected.name }}</span>
          <VChip size="small" variant="outlined" label class="font-mono">{{ selected.groupCode }}</VChip>
          <VChip v-if="dirty" size="small" color="warning" variant="tonal" data-test="codes-dirty">{{ t('admin.codes.unsaved') }}</VChip>
          <VSpacer />
          <template v-if="!readonly">
            <VBtn icon="ri-pencil-line" size="small" variant="text" :aria-label="t('admin.codes.editGroup')" :title="t('admin.codes.editGroup')" @click="openGroupDialog(selected)" />
            <VBtn
              icon="ri-delete-bin-line" size="small" variant="text" color="error" :disabled="selected.system"
              :aria-label="t('admin.codes.deleteGroup')" :title="selected.system ? t('admin.codes.systemNoDelete') : t('admin.codes.deleteGroup')"
              @click="deleteTarget = selected"
            />
          </template>
        </VCardTitle>
        <VCardSubtitle v-if="selected.description">{{ selected.description }}</VCardSubtitle>
        <div class="d-flex flex-wrap ga-2 px-4 py-2">
          <VBtn v-if="!readonly" size="small" variant="tonal" prepend-icon="ri-add-line" data-test="code-add-row" @click="addRow">{{ t('admin.codes.addRow') }}</VBtn>
          <VSpacer />
          <ExcelExportButton dataset-id="codes" :file-name="`${t('admin.codes.exportName')}_${selected.groupCode}`" :params="{ groupCode: selected.groupCode }" />
          <VBtn v-if="!readonly" size="small" variant="tonal" prepend-icon="ri-file-upload-line" data-test="codes-import" @click="importOpen = true">{{ t('common.excel.import') }}</VBtn>
          <VBtn v-if="!readonly" size="small" color="primary" prepend-icon="ri-save-3-line" :loading="saving" :disabled="!dirty || hasErrors" data-test="codes-save" @click="saveCodes">
            {{ t('admin.codes.saveAll') }}
          </VBtn>
        </div>
        <VAlert v-if="saveError" type="error" variant="tonal" density="compact" class="mx-4 mb-2">{{ saveError }}</VAlert>
        <VDataTable
          :headers="headers"
          :items="rows"
          :loading="codesLoading"
          item-value="rowKey"
          density="compact"
          :items-per-page="-1"
          hide-default-footer
          class="code-table"
        >
          <template #item.code="{ item }">
            <VTextField
              v-if="!readonly"
              v-model="item.code"
              variant="plain"
              class="font-mono"
              :error-messages="fieldError(item.rowKey, 'code')"
              data-test="code-cell"
              @update:model-value="(v: string) => (item.code = v.toUpperCase())"
            />
            <span v-else class="font-mono">{{ item.code }}</span>
          </template>
          <template #item.name="{ item }">
            <VTextField
              v-if="!readonly"
              v-model="item.name"
              variant="plain"
              :error-messages="fieldError(item.rowKey, 'name')"
            />
            <span v-else>{{ item.name }}</span>
          </template>
          <template #item.sortOrder="{ item }">
            <VTextField v-if="!readonly" v-model.number="item.sortOrder" type="number" variant="plain" />
            <span v-else>{{ item.sortOrder }}</span>
          </template>
          <template #item.useYn="{ item }">
            <VSwitch v-model="item.useYn" :disabled="readonly" color="primary" density="compact" hide-details inset :aria-label="t('admin.codes.useYn')" />
          </template>
          <template #item.attr1="{ item }">
            <VTextField v-if="!readonly" v-model="item.attr1" variant="plain" />
            <span v-else>{{ item.attr1 }}</span>
          </template>
          <template #item.attr2="{ item }">
            <VTextField v-if="!readonly" v-model="item.attr2" variant="plain" />
            <span v-else>{{ item.attr2 }}</span>
          </template>
          <template #item.actions="{ item }">
            <VBtn icon="ri-close-line" size="x-small" variant="text" color="error" :aria-label="t('common.delete')" :title="t('common.delete')" @click="removeRow(item)" />
          </template>
          <template #no-data>
            <div class="py-6 text-muted">{{ t('admin.codes.noCodes') }}</div>
          </template>
        </VDataTable>
      </VCard>
      <div v-else class="pa-8 text-center text-muted">{{ t('admin.codes.selectGroup') }}</div>
    </VCol>

    <VDialog v-model="groupDialog" max-width="480" persistent>
      <VCard :title="editingGroup ? t('admin.codes.editGroup') : t('admin.codes.addGroup')">
        <VForm ref="groupFormRef" @submit.prevent="saveGroup">
          <VCardText>
            <VRow>
              <VCol cols="12" md="12">
                <VTextField
                  v-model="groupForm.groupCode"
                  :label="t('admin.codes.groupCode')"
                  :rules="editingGroup ? [] : [required, codeFormat]"
                  :disabled="!!editingGroup"
                  class="font-mono"
                  data-test="group-code"
                  @update:model-value="(v: string) => (groupForm.groupCode = v.toUpperCase())"
                />
              </VCol>
              <VCol cols="12" md="12">
                <VTextField v-model="groupForm.name" :label="t('admin.codes.groupName')" :rules="[required]" data-test="group-name" />
              </VCol>
              <VCol cols="12" md="12">
                <VTextarea v-model="groupForm.description" :label="t('admin.codes.description')" rows="2" auto-grow density="compact" hide-details="auto" />
              </VCol>
              <VCol v-if="groupError" cols="12" md="12">
                <VAlert type="error" variant="tonal" density="compact" role="alert">{{ groupError }}</VAlert>
              </VCol>
            </VRow>
          </VCardText>
          <VCardActions>
            <VSpacer />
            <VBtn variant="text" @click="groupDialog = false">{{ t('common.cancel') }}</VBtn>
            <VBtn type="submit" color="primary" :loading="groupSaving" data-test="group-save">{{ t('common.save') }}</VBtn>
          </VCardActions>
        </VForm>
      </VCard>
    </VDialog>

    <ConfirmDialog
      :model-value="!!deleteTarget"
      :title="t('admin.codes.deleteGroup')"
      :message="t('admin.codes.deleteGroupConfirm', { name: deleteTarget?.name ?? '' })"
      :confirm-text="t('common.delete')"
      :loading="deleting"
      @update:model-value="(v: boolean) => { if (!v) deleteTarget = null }"
      @confirm="deleteGroup"
    />

    <ConfirmDialog
      :model-value="!!pendingGroup"
      :title="t('admin.codes.discardTitle')"
      :message="t('admin.codes.discardMessage')"
      :confirm-text="t('admin.codes.discard')"
      color="warning"
      @update:model-value="(v: boolean) => { if (!v) pendingGroup = null }"
      @confirm="discardAndOpen"
    />

    <ExcelImportDialog
      v-if="selected"
      v-model="importOpen"
      dataset-id="codes"
      :params="{ groupCode: selected.groupCode }"
      :title="t('admin.codes.importTitle', { name: selected.name })"
      @committed="onImported"
    />
  </VRow>
</template>

<style scoped>
.group-list {
  max-height: 60vh;
  overflow-y: auto;
}

.code-table :deep(.v-field__input) {
  padding-top: 4px;
  padding-bottom: 4px;
  min-height: 32px;
}
</style>
