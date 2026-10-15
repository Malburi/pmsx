<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { createNoteApi, deleteNoteApi, getNotesApi, updateNoteApi } from '@/api/analysisApi'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import MarkdownEditor from '@/components/common/MarkdownEditor.vue'
import MarkdownView from '@/components/common/MarkdownView.vue'
import { useAuthStore } from '@/stores/auth'
import type { ApiError, Note, NoteTargetType } from '@/types/api'
import { formatDateTime } from '@/utils/format'
import { ASSIGN_ROLES, NOTE_ROLES } from '@/utils/permission'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// 업무 규칙 메모 (FR-AS-021) — 화면·요소·노드 공통
const props = defineProps<{ targetType: NoteTargetType; targetId: string }>()

const { t } = useI18n()
const auth = useAuthStore()
const MAX = 20_000
const notes = ref<Note[]>([])
const loading = ref(false)
const loadError = ref(false)
const draft = ref('')
const writing = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const editText = ref('')
const deleteTarget = ref<Note | null>(null)
const deleting = ref(false)

// ─────────── 헬퍼 ───────────
const load = async () => {
  loading.value = true
  loadError.value = false
  try {
    notes.value = await getNotesApi(props.targetType, props.targetId)
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

const failMessage = (e: unknown) => ((e as ApiError).code === 'FORBIDDEN' ? 'common.forbiddenAction' : 'common.saveFailed')

const add = async () => {
  const content = draft.value.trim()
  if (!content || content.length > MAX) return
  saving.value = true
  try {
    const note = await createNoteApi({ targetType: props.targetType, targetId: props.targetId, content })
    notes.value = [note, ...notes.value]
    draft.value = ''
    writing.value = false
    showSuccessToast('asis.notes.saved')
  } catch (e) {
    showErrorToast(failMessage(e))
  } finally {
    saving.value = false
  }
}

const startEdit = (n: Note) => {
  editingId.value = n.noteId
  editText.value = n.content
}

const saveEdit = async () => {
  const id = editingId.value
  const content = editText.value.trim()
  if (!id || !content || content.length > MAX) return
  saving.value = true
  try {
    const updated = await updateNoteApi(id, content)
    notes.value = notes.value.map((n) => (n.noteId === id ? updated : n))
    editingId.value = null
    showSuccessToast('asis.notes.saved')
  } catch (e) {
    showErrorToast(failMessage(e))
  } finally {
    saving.value = false
  }
}

const confirmDelete = async () => {
  const target = deleteTarget.value
  if (!target) return
  deleting.value = true
  try {
    await deleteNoteApi(target.noteId)
    notes.value = notes.value.filter((n) => n.noteId !== target.noteId)
    deleteTarget.value = null
  } catch (e) {
    showErrorToast((e as ApiError).code === 'FORBIDDEN' ? 'common.forbiddenAction' : 'common.deleteFailed')
  } finally {
    deleting.value = false
  }
}

// 작성자 본인 또는 PM·PL 만 수정·삭제
const canManage = (n: Note) => n.author.userId === auth.userId || auth.hasAnyRole(ASSIGN_ROLES)

// ─────────── computed ───────────
const canWrite = computed(() => auth.hasAnyRole(NOTE_ROLES))

// ─────────── watch ───────────
watch(() => [props.targetType, props.targetId], () => {
  writing.value = false
  editingId.value = null
  draft.value = ''
  void load()
}, { immediate: true })
</script>

<template>
  <div class="notes">
    <div class="d-flex align-center mb-2">
      <span class="text-caption text-muted">{{ t('asis.notes.title') }}</span>
      <VSpacer />
      <VBtn
        v-if="canWrite && !writing"
        size="small"
        variant="tonal"
        prepend-icon="ri-sticky-note-add-line"
        data-test="note-add"
        @click="writing = true"
      >{{ t('asis.notes.add') }}</VBtn>
    </div>

    <div v-if="writing" class="mb-3">
      <MarkdownEditor v-model="draft" :placeholder="t('asis.notes.placeholder')" :max-length="MAX" data-test="note-editor" />
      <div class="d-flex justify-end ga-2 mt-2">
        <VBtn size="small" variant="text" @click="writing = false; draft = ''">{{ t('common.cancel') }}</VBtn>
        <VBtn size="small" color="primary" :loading="saving" :disabled="!draft.trim() || draft.length > MAX" data-test="note-save" @click="add">
          {{ t('common.save') }}
        </VBtn>
      </div>
    </div>

    <VProgressLinear v-if="loading" indeterminate color="primary" />
    <VAlert v-else-if="loadError" type="warning" variant="tonal" density="compact">
      {{ t('asis.notes.loadFailed') }}
      <VBtn variant="text" size="small" @click="load">{{ t('common.retry') }}</VBtn>
    </VAlert>
    <div v-else-if="notes.length === 0 && !writing" class="text-body-2 text-muted py-2">{{ t('asis.notes.empty') }}</div>

    <div v-for="n in notes" :key="n.noteId" class="note border rounded-lg pa-3 mb-2" data-test="note-item">
      <div class="d-flex align-center text-caption text-muted mb-1 ga-1">
        <VIcon icon="ri-user-line" size="13" />
        <span>{{ n.author.name }}</span>
        <span>· {{ formatDateTime(n.createdAt) }}</span>
        <span v-if="n.updatedAt !== n.createdAt">· {{ t('asis.notes.edited') }}</span>
        <VSpacer />
        <template v-if="canManage(n) && editingId !== n.noteId">
          <VBtn icon="ri-edit-line" size="x-small" variant="text" :aria-label="t('common.edit')" @click="startEdit(n)" />
          <VBtn icon="ri-delete-bin-line" size="x-small" variant="text" :aria-label="t('common.delete')" @click="deleteTarget = n" />
        </template>
      </div>
      <template v-if="editingId === n.noteId">
        <MarkdownEditor v-model="editText" :max-length="MAX" />
        <div class="d-flex justify-end ga-2 mt-2">
          <VBtn size="small" variant="text" @click="editingId = null">{{ t('common.cancel') }}</VBtn>
          <VBtn size="small" color="primary" :loading="saving" :disabled="!editText.trim() || editText.length > MAX" @click="saveEdit">
            {{ t('common.save') }}
          </VBtn>
        </div>
      </template>
      <MarkdownView v-else :source="n.content" />
    </div>

    <ConfirmDialog
      :model-value="!!deleteTarget"
      :title="t('asis.notes.deleteTitle')"
      :message="t('asis.notes.deleteConfirm')"
      :confirm-text="t('common.delete')"
      :loading="deleting"
      @update:model-value="(v: boolean) => { if (!v) deleteTarget = null }"
      @confirm="confirmDelete"
    />
  </div>
</template>

<style scoped>
.note {
  background: rgb(var(--v-theme-surface-2));
}
</style>
