<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { deleteAttachmentApi, downloadFileApi, getAttachmentsApi, previewFileApi, uploadFileApi } from '@/api/fileApi'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import type { ApiError } from '@/types/api'
import type { FileMeta } from '@/types/common'
import { saveBlob } from '@/utils/download'
import { checkFile, formatBytes, isPreviewable, MAX_FILE_SIZE } from '@/utils/files'
import { formatDateTime } from '@/utils/format'
import { showErrorToast } from '@/utils/toast'

const props = withDefaults(defineProps<{ targetType: string; targetId: string; readonly?: boolean }>(), { readonly: false })
const emit = defineEmits<{ changed: [count: number] }>()

interface QueueItem {
  key: number
  name: string
  size: number
  file: File | null
  progress: number
  status: 'waiting' | 'uploading' | 'done' | 'error'
  message: string | null
}

const { t } = useI18n()
const files = ref<FileMeta[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)
const picked = ref<File[]>([])
const queue = ref<QueueItem[]>([])
const uploading = ref(false)
const preview = ref<{ name: string; url: string; mime: string } | null>(null)
const deleteTarget = ref<FileMeta | null>(null)
const deleting = ref(false)
let queueSeq = 0

// ─────────── 헬퍼 ───────────
const load = async () => {
  if (!props.targetId) return
  loading.value = true
  loadError.value = null
  try {
    files.value = await getAttachmentsApi(props.targetType, props.targetId)
    emit('changed', files.value.length)
  } catch (e) {
    loadError.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}

const errorMessage = (e: unknown) => {
  const code = (e as ApiError)?.code
  if (code === 'FILE_TOO_LARGE') return t('common.attach.tooLarge', { max: formatBytes(MAX_FILE_SIZE) })
  if (code === 'FILE_TYPE_NOT_ALLOWED') return t('common.attach.typeNotAllowed')
  return (e as ApiError)?.message ?? t('common.error.unknown')
}

// 선택 직후 검증 결과를 큐에 바로 보여주고(file-upload §1), 통과한 파일만 순서대로 올린다
const enqueue = (list: File[] | File | null) => {
  const arr = Array.isArray(list) ? list : list ? [list] : []
  arr.forEach((f) => {
    const check = checkFile(f)
    queue.value.push({
      key: ++queueSeq, name: f.name, size: f.size, file: check === 'ok' ? f : null, progress: 0,
      status: check === 'ok' ? 'waiting' : 'error',
      message: check === 'tooLarge'
        ? t('common.attach.tooLarge', { max: formatBytes(MAX_FILE_SIZE) })
        : check === 'typeNotAllowed' ? t('common.attach.typeNotAllowed') : null,
    })
  })
  picked.value = []
  void runQueue()
}

const runQueue = async () => {
  if (uploading.value) return
  uploading.value = true
  try {
    for (const item of queue.value) {
      if (item.status !== 'waiting' || !item.file) continue
      item.status = 'uploading'
      try {
        await uploadFileApi(item.file, props.targetType, props.targetId, (r) => { item.progress = Math.round(r * 100) })
        item.progress = 100
        item.status = 'done'
      } catch (e) {
        item.status = 'error'
        item.message = errorMessage(e)
      }
    }
  } finally {
    uploading.value = false
  }
  await load()
}

const clearFinished = () => {
  queue.value = queue.value.filter((q) => q.status === 'waiting' || q.status === 'uploading')
}

const download = async (fileId: string, name: string) => {
  try {
    saveBlob(await downloadFileApi(fileId), name)
  } catch {
    showErrorToast('common.attach.downloadFailed')
  }
}

const closePreview = () => {
  if (preview.value) URL.revokeObjectURL(preview.value.url)
  preview.value = null
}

const openPreview = async (f: FileMeta) => {
  try {
    const blob = await previewFileApi(f.fileId)
    closePreview()
    // blob: URL 은 앱과 같은 오리진이므로 HTML 로 해석될 여지가 없게 형식을 고정한다.
    // 이미지는 <img> 로만(스크립트 실행 안 됨), 그 외(isPreviewable 통과 = PDF)는 PDF 로 연다.
    const mime = f.mime.startsWith('image/') ? f.mime : 'application/pdf'
    preview.value = { name: f.name, url: URL.createObjectURL(new Blob([blob], { type: mime })), mime }
  } catch {
    showErrorToast('common.attach.previewFailed')
  }
}

const confirmDelete = async () => {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await deleteAttachmentApi(deleteTarget.value.attachId)
    deleteTarget.value = null
    await load()
  } catch {
    showErrorToast('common.attach.deleteFailed')
  } finally {
    deleting.value = false
  }
}

const queueColor = (s: QueueItem['status']) =>
  ({ waiting: 'grey', uploading: 'primary', done: 'success', error: 'error' })[s]

// ─────────── watch ───────────
watch(() => [props.targetType, props.targetId], () => {
  queue.value = []
  void load()
}, { immediate: true })

// ─────────── 라이프사이클 ───────────
onBeforeUnmount(closePreview)
</script>

<template>
  <div class="attachment-panel">
    <VFileInput
      v-if="!readonly"
      v-model="picked"
      multiple
      :label="t('common.attach.pick')"
      :hint="t('common.attach.hint', { max: formatBytes(MAX_FILE_SIZE) })"
      persistent-hint
      prepend-icon=""
      prepend-inner-icon="ri-attachment-2"
      :disabled="!targetId"
      data-test="attach-input"
      @update:model-value="enqueue"
    />

    <div v-if="queue.length" class="mt-3" data-test="attach-queue">
      <div class="d-flex align-center text-caption text-muted mb-1">
        {{ t('common.attach.queue') }}
        <VSpacer />
        <VBtn v-if="!uploading" size="x-small" variant="text" @click="clearFinished">{{ t('common.attach.clearQueue') }}</VBtn>
      </div>
      <div v-for="q in queue" :key="q.key" class="queue-item py-1">
        <div class="d-flex align-center ga-2 text-body-2">
          <span class="text-truncate flex-grow-1">{{ q.name }}</span>
          <span class="text-caption text-muted">{{ formatBytes(q.size) }}</span>
          <VChip size="x-small" variant="tonal" :color="queueColor(q.status)" :data-test="`attach-status-${q.status}`">
            {{ t(`common.attach.status.${q.status}`) }}<template v-if="q.status === 'uploading'"> {{ q.progress }}%</template>
          </VChip>
        </div>
        <VProgressLinear v-if="q.status === 'uploading'" :model-value="q.progress" color="primary" height="4" rounded class="mt-1" />
        <div v-if="q.message" class="text-caption text-error">{{ q.message }}</div>
      </div>
    </div>

    <VProgressLinear v-if="loading" indeterminate color="primary" class="mt-3" />
    <VAlert v-if="loadError" type="error" variant="tonal" density="compact" class="mt-3">
      {{ loadError }}
      <VBtn variant="text" size="small" @click="load">{{ t('common.retry') }}</VBtn>
    </VAlert>

    <VList v-if="files.length" density="compact" class="mt-2 pa-0" data-test="attach-list">
      <VListItem v-for="f in files" :key="f.attachId" class="px-1">
        <template #prepend>
          <VIcon :icon="f.mime.startsWith('image/') ? 'ri-image-line' : 'ri-file-text-line'" size="18" class="mr-2" />
        </template>
        <VListItemTitle class="text-body-2">
          {{ f.name }}
          <VChip size="x-small" variant="outlined" class="ml-1">v{{ f.version }}</VChip>
        </VListItemTitle>
        <VListItemSubtitle class="text-caption">
          {{ formatBytes(f.size) }} · {{ f.uploadedBy.name }} · {{ formatDateTime(f.uploadedAt) }}
        </VListItemSubtitle>
        <template #append>
          <VBtn
            v-if="isPreviewable(f.mime)"
            icon="ri-eye-line" variant="text" size="small"
            :aria-label="t('common.attach.preview')" :title="t('common.attach.preview')"
            @click="openPreview(f)"
          />
          <VBtn
            icon="ri-download-2-line" variant="text" size="small"
            :aria-label="t('common.attach.download')" :title="t('common.attach.download')"
            @click="download(f.fileId, f.name)"
          />
          <VMenu v-if="f.versions.length > 1" location="bottom end">
            <template #activator="{ props: menuProps }">
              <VBtn v-bind="menuProps" icon="ri-history-line" variant="text" size="small" :aria-label="t('common.attach.versions')" :title="t('common.attach.versions')" />
            </template>
            <VList density="compact">
              <VListSubheader>{{ t('common.attach.versions') }}</VListSubheader>
              <VListItem v-for="v in f.versions" :key="v.fileId" @click="download(v.fileId, f.name)">
                <VListItemTitle class="text-body-2">v{{ v.version }} · {{ formatDateTime(v.uploadedAt) }}</VListItemTitle>
              </VListItem>
            </VList>
          </VMenu>
          <VBtn
            v-if="!readonly"
            icon="ri-delete-bin-line" variant="text" size="small" color="error"
            :aria-label="t('common.attach.delete')" :title="t('common.attach.delete')"
            @click="deleteTarget = f"
          />
        </template>
      </VListItem>
    </VList>
    <div v-else-if="!loading && !loadError" class="text-caption text-muted mt-3">{{ t('common.attach.empty') }}</div>

    <VDialog :model-value="!!preview" max-width="960" @update:model-value="(v: boolean) => { if (!v) closePreview() }">
      <VCard v-if="preview">
        <VCardTitle class="d-flex align-center">
          <span class="text-truncate">{{ preview.name }}</span>
          <VSpacer />
          <VBtn icon="ri-close-line" variant="text" size="small" :aria-label="t('common.close')" @click="closePreview" />
        </VCardTitle>
        <VDivider />
        <div class="preview-body">
          <img v-if="preview.mime.startsWith('image/')" :src="preview.url" :alt="preview.name" class="preview-img">
          <iframe v-else :src="preview.url" :title="preview.name" class="preview-frame" />
        </div>
      </VCard>
    </VDialog>

    <ConfirmDialog
      :model-value="!!deleteTarget"
      :title="t('common.attach.deleteTitle')"
      :message="t('common.attach.deleteMessage', { name: deleteTarget?.name ?? '' })"
      :confirm-text="t('common.delete')"
      :loading="deleting"
      @update:model-value="(v: boolean) => { if (!v) deleteTarget = null }"
      @confirm="confirmDelete"
    />
  </div>
</template>

<style scoped>
.queue-item + .queue-item {
  border-top: 1px dashed rgb(var(--v-theme-line));
}

.preview-body {
  max-height: 75vh;
  overflow: auto;
  text-align: center;
}

.preview-img {
  max-width: 100%;
}

.preview-frame {
  width: 100%;
  height: 72vh;
  border: 0;
}
</style>
