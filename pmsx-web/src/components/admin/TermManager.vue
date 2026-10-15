<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { createTermApi, deleteTermApi, getTermsApi, updateTermApi } from '@/api/codeApi'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import ExcelImportDialog from '@/components/common/ExcelImportDialog.vue'
import type { ApiError } from '@/types/api'
import type { Term } from '@/types/common'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

defineProps<{ readonly: boolean }>()

const { t } = useI18n()
const q = ref('')
const page = ref(1)
const itemsPerPage = ref(20)
const terms = ref<Term[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)

const dialog = ref(false)
const editing = ref<Term | null>(null)
const formRef = ref<{ validate: () => Promise<{ valid: boolean }> } | null>(null)
const form = reactive({ term: '', abbr: '', definition: '', domain: '' })
const saving = ref(false)
const formError = ref<string | null>(null)
const deleteTarget = ref<Term | null>(null)
const deleting = ref(false)
const importOpen = ref(false)

// ─────────── 헬퍼 ───────────
const required = (v: string) => !!v?.trim() || t('validation.required')

const load = async () => {
  loading.value = true
  loadError.value = null
  try {
    const res = await getTermsApi({ q: q.value.trim() || undefined, page: page.value - 1, size: itemsPerPage.value })
    terms.value = res.content
    total.value = res.totalElements
  } catch (e) {
    loadError.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}

const onOptions = (opts: { page: number; itemsPerPage: number }) => {
  page.value = opts.page
  itemsPerPage.value = opts.itemsPerPage
  void load()
}

const search = () => {
  page.value = 1
  void load()
}

const openDialog = (term: Term | null) => {
  editing.value = term
  Object.assign(form, { term: term?.term ?? '', abbr: term?.abbr ?? '', definition: term?.definition ?? '', domain: term?.domain ?? '' })
  formError.value = null
  dialog.value = true
}

const save = async () => {
  const result = await formRef.value?.validate()
  if (!result?.valid) return
  saving.value = true
  formError.value = null
  const payload = { term: form.term.trim(), abbr: form.abbr.trim() || null, definition: form.definition.trim(), domain: form.domain.trim() || null }
  try {
    if (editing.value) await updateTermApi(editing.value.termId, payload)
    else await createTermApi(payload)
    dialog.value = false
    showSuccessToast('admin.terms.saved')
    await load()
  } catch (e) {
    const err = e as ApiError
    formError.value = err.code === 'CONFLICT' ? t('admin.terms.conflict') : (err.message ?? t('common.error.unknown'))
  } finally {
    saving.value = false
  }
}

const remove = async () => {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await deleteTermApi(deleteTarget.value.termId)
    deleteTarget.value = null
    await load()
  } catch {
    showErrorToast('common.error.unknown')
  } finally {
    deleting.value = false
  }
}

// ─────────── computed ───────────
const headers = computed(() => [
  { title: t('admin.terms.term'), key: 'term', width: 180 },
  { title: t('admin.terms.abbr'), key: 'abbr', width: 140 },
  { title: t('admin.terms.domain'), key: 'domain', width: 120 },
  { title: t('admin.terms.definition'), key: 'definition', sortable: false },
  { title: '', key: 'actions', sortable: false, align: 'end' as const, width: 100 },
])
</script>

<template>
  <VCard border flat>
    <div class="pa-3 d-flex flex-wrap ga-2 align-center">
      <VTextField
        v-model="q"
        :label="t('admin.terms.search')"
        prepend-inner-icon="ri-search-line"
        clearable
        class="search-box"
        @keyup.enter="search"
        @click:clear="() => { q = ''; search() }"
      />
      <VBtn variant="tonal" @click="search">{{ t('common.search') }}</VBtn>
      <VSpacer />
      <ExcelExportButton dataset-id="terms" :file-name="t('admin.terms.exportName')" />
      <template v-if="!readonly">
        <VBtn size="small" variant="tonal" prepend-icon="ri-file-upload-line" @click="importOpen = true">{{ t('common.excel.import') }}</VBtn>
        <VBtn size="small" color="primary" prepend-icon="ri-add-line" data-test="term-add" @click="openDialog(null)">{{ t('admin.terms.add') }}</VBtn>
      </template>
    </div>
    <VAlert v-if="loadError" type="error" variant="tonal" class="mx-3 mb-3">
      {{ loadError }}
      <VBtn variant="text" size="small" @click="load">{{ t('common.retry') }}</VBtn>
    </VAlert>
    <VDataTableServer
      :headers="headers"
      :items="terms"
      :items-length="total"
      :loading="loading"
      :items-per-page="itemsPerPage"
      :page="page"
      item-value="termId"
      density="comfortable"
      @update:options="onOptions"
    >
      <template #item.term="{ item }"><span class="font-weight-medium">{{ item.term }}</span></template>
      <template #item.abbr="{ item }"><span class="font-mono">{{ item.abbr ?? '-' }}</span></template>
      <template #item.domain="{ item }">{{ item.domain ?? '-' }}</template>
      <template #item.actions="{ item }">
        <div v-if="!readonly" class="d-flex justify-end flex-nowrap">
          <VBtn icon="ri-pencil-line" variant="text" size="small" :aria-label="t('common.edit')" :title="t('common.edit')" @click="openDialog(item)" />
          <VBtn icon="ri-delete-bin-line" variant="text" size="small" color="error" :aria-label="t('common.delete')" :title="t('common.delete')" @click="deleteTarget = item" />
        </div>
      </template>
      <template #no-data>
        <div class="py-8 text-muted">{{ t('common.noData') }}</div>
      </template>
    </VDataTableServer>

    <VDialog v-model="dialog" max-width="560" persistent>
      <VCard :title="editing ? t('admin.terms.editTitle') : t('admin.terms.add')">
        <VForm ref="formRef" @submit.prevent="save">
          <VCardText>
            <VRow>
              <VCol cols="12" sm="6">
                <VTextField v-model="form.term" :label="t('admin.terms.term')" :rules="[required]" data-test="term-term" />
              </VCol>
              <VCol cols="12" sm="6">
                <VTextField v-model="form.abbr" :label="t('admin.terms.abbr')" class="font-mono" />
              </VCol>
              <VCol cols="12" sm="6">
                <VTextField v-model="form.domain" :label="t('admin.terms.domain')" />
              </VCol>
              <VCol cols="12" md="12">
                <VTextarea v-model="form.definition" :label="t('admin.terms.definition')" :rules="[required]" rows="3" auto-grow density="compact" hide-details="auto" />
              </VCol>
              <VCol v-if="formError" cols="12" md="12">
                <VAlert type="error" variant="tonal" density="compact" role="alert">{{ formError }}</VAlert>
              </VCol>
            </VRow>
          </VCardText>
          <VCardActions>
            <VSpacer />
            <VBtn variant="text" @click="dialog = false">{{ t('common.cancel') }}</VBtn>
            <VBtn type="submit" color="primary" :loading="saving">{{ t('common.save') }}</VBtn>
          </VCardActions>
        </VForm>
      </VCard>
    </VDialog>

    <ConfirmDialog
      :model-value="!!deleteTarget"
      :title="t('admin.terms.deleteTitle')"
      :message="t('admin.terms.deleteConfirm', { term: deleteTarget?.term ?? '' })"
      :confirm-text="t('common.delete')"
      :loading="deleting"
      @update:model-value="(v: boolean) => { if (!v) deleteTarget = null }"
      @confirm="remove"
    />

    <ExcelImportDialog v-model="importOpen" dataset-id="terms" :title="t('admin.terms.importTitle')" @committed="load" />
  </VCard>
</template>

<style scoped>
.search-box {
  max-width: 320px;
}
</style>
