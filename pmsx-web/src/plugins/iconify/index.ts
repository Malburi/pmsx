import { h, type PropType } from 'vue'
import { Icon, addCollection } from '@iconify/vue/offline'
// 사용하는 아이콘만 추린 묶음 (scripts/build-icons.mjs 가 생성)
import riIcons from './ri-subset.json'
import type { IconAliases, IconSet } from 'vuetify'

// 폐쇄망 전제: 아이콘 데이터를 번들에 포함하고(offline), 외부 Iconify API 를 호출하지 않는다.
addCollection(riIcons as Parameters<typeof addCollection>[0])

// 코드에서는 'ri-check-line' 처럼 쓰고(component-architecture §2-4), Iconify 이름 'ri:check-line' 으로 바꾼다.
const toIconifyName = (icon: string) => icon.replace(/^ri-/, 'ri:')

const IconifyVIcon = {
  name: 'IconifyVIcon',
  props: {
    icon: { type: [String, Function, Object, Array] as PropType<unknown>, required: true },
    tag: { type: String, required: true },
  },
  setup(props: { icon: unknown; tag: string }) {
    return () => h(props.tag, { class: 'iconify-v-icon' }, [
      h(Icon, { icon: toIconifyName(String(props.icon)), width: '1em', height: '1em' }),
    ])
  },
}

export const iconifySet: IconSet = { component: IconifyVIcon as unknown as IconSet['component'] }

export const iconifyAliases = {
  collapse: 'ri-arrow-up-s-line',
  complete: 'ri-check-line',
  cancel: 'ri-close-circle-line',
  close: 'ri-close-line',
  delete: 'ri-close-circle-fill',
  clear: 'ri-close-circle-line',
  success: 'ri-checkbox-circle-line',
  info: 'ri-information-line',
  warning: 'ri-alert-line',
  error: 'ri-error-warning-line',
  prev: 'ri-arrow-left-s-line',
  next: 'ri-arrow-right-s-line',
  checkboxOn: 'ri-checkbox-fill',
  checkboxOff: 'ri-checkbox-blank-line',
  checkboxIndeterminate: 'ri-checkbox-indeterminate-line',
  delimiter: 'ri-circle-fill',
  sortAsc: 'ri-arrow-up-line',
  sortDesc: 'ri-arrow-down-line',
  expand: 'ri-arrow-down-s-line',
  menu: 'ri-menu-line',
  subgroup: 'ri-arrow-down-s-fill',
  dropdown: 'ri-arrow-down-s-fill',
  radioOn: 'ri-radio-button-fill',
  radioOff: 'ri-checkbox-blank-circle-line',
  edit: 'ri-pencil-line',
  ratingEmpty: 'ri-star-line',
  ratingFull: 'ri-star-fill',
  ratingHalf: 'ri-star-half-line',
  loading: 'ri-loader-4-line',
  first: 'ri-skip-back-line',
  last: 'ri-skip-forward-line',
  unfold: 'ri-expand-up-down-line',
  file: 'ri-attachment-2',
  plus: 'ri-add-line',
  minus: 'ri-subtract-line',
  calendar: 'ri-calendar-line',
  treeviewCollapse: 'ri-arrow-down-s-fill',
  treeviewExpand: 'ri-arrow-right-s-fill',
  tableGroupCollapse: 'ri-arrow-down-s-fill',
  tableGroupExpand: 'ri-arrow-right-s-fill',
  eyeDropper: 'ri-drop-line',
  upload: 'ri-upload-cloud-2-line',
  color: 'ri-palette-line',
  search: 'ri-search-line',
} as unknown as IconAliases
