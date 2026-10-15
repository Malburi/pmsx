import { useUiStore } from '@/stores/ui'

// 메시지는 i18n 키로 받는다. 표시 시점에 번역한다.
export const showErrorToast = (messageKey: string) => {
  useUiStore().toast(messageKey, 'error')
}

export const showSuccessToast = (messageKey: string) => {
  useUiStore().toast(messageKey, 'success')
}
