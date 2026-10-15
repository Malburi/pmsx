<route lang="yaml">
meta:
  layout: portal
  roles: [CUSTOMER]
</route>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { addPortalCommentApi, confirmPortalSrApi, getPortalSrApi, reopenPortalSrApi } from '@/api/portalApi'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import AttachmentPanel from '@/components/common/AttachmentPanel.vue'
import type { ApiError } from '@/types/api'
import type { Sr } from '@/types/r3'
import { formatDateTime } from '@/utils/format'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-SM-010 고객 SR 상세 — 공개 코멘트만 보이고, 처리 완료(RESOLVED)면 확인 또는 재요청
const { t } = useI18n()
const route = useRoute()

const sr = ref<Sr | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const comment = reactive({ text: '', posting: false })
const reopen = reactive({ open: false, reason: '', saving: false })
const confirming = ref(false)

// ─────────── 헬퍼 ───────────
const srId = computed(() => String((route.params as { srId?: string }).srId ?? ''))

const load = async () => {
  if (!srId.value) return
  loading.value = true
  error.value = null
  try {
    sr.value = await getPortalSrApi(srId.value)
  } catch (e) {
    error.value = (e as ApiError).code === 'NOT_FOUND' ? t('portal.notFound') : ((e as ApiError).message ?? t('common.error.unknown'))
  } finally {
    loading.value = false
  }
}

const post = async () => {
  if (!sr.value || !comment.text.trim()) return
  comment.posting = true
  try {
    const c = await addPortalCommentApi(sr.value.srId, comment.text.trim())
    sr.value.comments = [...(sr.value.comments ?? []), c]
    comment.text = ''
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    comment.posting = false
  }
}

const confirm = async () => {
  if (!sr.value) return
  confirming.value = true
  try {
    sr.value = { ...sr.value, ...(await confirmPortalSrApi(sr.value.srId)) }
    showSuccessToast('portal.confirmed')
  } catch {
    showErrorToast('common.actionFailed')
  } finally {
    confirming.value = false
  }
}

const submitReopen = async () => {
  if (!sr.value || !reopen.reason.trim()) return
  reopen.saving = true
  try {
    sr.value = { ...sr.value, ...(await reopenPortalSrApi(sr.value.srId, reopen.reason.trim())) }
    reopen.open = false
    showSuccessToast('portal.reopened')
    void load()
  } catch {
    showErrorToast('common.actionFailed')
  } finally {
    reopen.saving = false
  }
}

// ─────────── computed ───────────
const publicComments = computed(() => (sr.value?.comments ?? []).filter((c) => !c.internal))
const canComment = computed(() => !!sr.value && !['CLOSED', 'REJECTED'].includes(sr.value.status))

watch(srId, () => { void load() }, { immediate: true })
</script>

<template>
  <VContainer class="pa-4 pa-md-6 portal-wrap">
    <VBtn variant="text" prepend-icon="ri-arrow-left-line" to="/portal" class="mb-2 px-1">{{ t('portal.back') }}</VBtn>
    <VProgressLinear v-if="loading" indeterminate color="primary" />
    <VAlert v-if="error" type="error" variant="tonal">{{ error }}</VAlert>
    <VCard v-if="sr" border flat data-test="portal-detail">
      <VCardTitle class="d-flex flex-wrap align-center ga-2">
        <span class="font-mono text-body-2">{{ sr.srNo }}</span>
        <AppStatusChip :status="sr.status" prefix="sm.srStatus" size="small" />
        <VChip size="small" variant="outlined" label>{{ t(`sm.srType.${sr.type}`) }}</VChip>
      </VCardTitle>
      <VCardText>
        <div class="text-h6 mb-1">{{ sr.title }}</div>
        <div class="text-caption text-muted mb-3">
          {{ t('portal.requestedAt') }} {{ formatDateTime(sr.createdAt) }}
          <template v-if="sr.assignee"> · {{ t('portal.handler') }} {{ sr.assignee.name }}</template>
          <template v-if="sr.screenName"> · {{ t('portal.screen') }} {{ sr.screenName }}</template>
        </div>
        <p class="text-body-1 pre mb-4">{{ sr.description }}</p>

        <VAlert v-if="sr.status === 'RESOLVED'" type="success" variant="tonal" class="mb-4" data-test="portal-resolved">
          <div class="mb-2">{{ t('portal.resolvedHint') }}</div>
          <div class="d-flex flex-wrap ga-2">
            <VBtn color="success" :loading="confirming" prepend-icon="ri-check-double-line" data-test="portal-confirm" @click="confirm">{{ t('portal.confirm') }}</VBtn>
            <VBtn variant="tonal" prepend-icon="ri-arrow-go-back-line" data-test="portal-reopen" @click="Object.assign(reopen, { open: true, reason: '' })">{{ t('portal.reopen') }}</VBtn>
          </div>
        </VAlert>

        <div class="text-subtitle-1 mb-2">{{ t('portal.conversation') }}</div>
        <VTimeline density="compact" side="end" truncate-line="both">
          <VTimelineItem v-for="c in publicComments" :key="c.commentId" size="x-small" dot-color="primary">
            <div class="text-caption text-muted">{{ c.author.name }} · {{ formatDateTime(c.createdAt) }}</div>
            <div class="text-body-2 pre">{{ c.content }}</div>
          </VTimelineItem>
        </VTimeline>
        <div v-if="!publicComments.length" class="text-body-2 text-muted mb-3">{{ t('portal.noComments') }}</div>
        <div v-if="canComment" class="mt-2">
          <VTextarea v-model="comment.text" :label="t('portal.addComment')" rows="2" auto-grow hide-details data-test="portal-comment" />
          <div class="d-flex justify-end mt-2">
            <VBtn color="primary" :loading="comment.posting" :disabled="!comment.text.trim()" @click="post">{{ t('portal.send') }}</VBtn>
          </div>
        </div>

        <div class="text-subtitle-1 mt-6 mb-2">{{ t('sm.sr.attachments') }}</div>
        <AttachmentPanel target-type="SM_SR" :target-id="sr.srId" :readonly="!canComment" />
      </VCardText>
    </VCard>

    <VDialog v-model="reopen.open" max-width="440">
      <VCard :title="t('portal.reopen')">
        <VCardText>
          <VTextarea v-model="reopen.reason" :label="t('portal.reopenReason')" rows="3" auto-grow data-test="portal-reopen-reason" />
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="reopen.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="reopen.saving" :disabled="!reopen.reason.trim()" @click="submitReopen">{{ t('portal.reopen') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
  </VContainer>
</template>

<style scoped>
.portal-wrap {
  max-width: 900px;
}

.pre {
  white-space: pre-wrap;
}
</style>
