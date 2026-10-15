import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import * as excelApi from '@/api/excelApi'
import ExcelImportDialog from '@/components/common/ExcelImportDialog.vue'
import { i18n } from '@/plugins/i18n'
import type { ImportPreview } from '@/types/common'

vi.mock('@/api/excelApi')

const vuetify = createVuetify({ components, directives })

const PREVIEW: ImportPreview = {
  token: 'tk-1', validCount: 2, errorCount: 1,
  rows: [
    { rowNo: 2, values: { code: 'RPT', name: '보고서' }, errors: {} },
    { rowNo: 3, values: { code: '', name: '설계서' }, errors: { code: '코드는 필수입니다.' } },
    { rowNo: 4, values: { code: 'EST', name: '견적서' }, errors: {} },
  ],
}

const pickFile = async (name: string) => {
  const input = document.body.querySelector('[data-test="import-file"] input[type="file"]') as HTMLInputElement
  Object.defineProperty(input, 'files', { value: [new File(['x'], name)], configurable: true })
  input.dispatchEvent(new Event('change'))
  await flushPromises()
}

const click = async (testId: string) => {
  (document.body.querySelector(`[data-test="${testId}"]`) as HTMLElement).click()
  await flushPromises()
}

describe('ExcelImportDialog', () => {
  it('검증 결과의 오류 셀을 표시하고, 정상 행만 반영한 뒤 결과를 알린다', async () => {
    // Arrange
    vi.mocked(excelApi.getExcelDatasetsApi).mockResolvedValue([{
      datasetId: 'codes', name: '공통코드', importable: true, params: ['groupCode'],
      columns: [
        { field: 'code', header: '코드', required: true, type: 'string' },
        { field: 'name', header: '코드명', required: true, type: 'string' },
      ],
    }])
    vi.mocked(excelApi.validateExcelImportApi).mockResolvedValue(PREVIEW)
    vi.mocked(excelApi.commitExcelImportApi).mockResolvedValue({ inserted: 2, updated: 0 })
    const wrapper = mount(ExcelImportDialog, {
      props: { modelValue: false, datasetId: 'codes', params: { groupCode: 'DOC_KIND' } },
      global: { plugins: [vuetify, i18n] },
      attachTo: document.body,
    })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    // Act — 파일 선택 후 검증
    await pickFile('codes.xlsx')
    await click('import-validate')

    // Assert — 오류 셀 1개, 반영 버튼은 정상 2행
    expect(excelApi.validateExcelImportApi).toHaveBeenCalledWith('codes', expect.any(File), { groupCode: 'DOC_KIND' })
    expect(document.body.querySelectorAll('[data-test="import-cell-error"]').length).toBe(1)
    expect(document.body.querySelector('[data-test="import-commit"]')?.textContent).toContain('2')

    // Act — 반영
    await click('import-commit')

    // Assert
    expect(excelApi.commitExcelImportApi).toHaveBeenCalledWith('codes', 'tk-1')
    expect(wrapper.emitted('committed')?.[0]).toEqual([{ inserted: 2, updated: 0 }])
    expect(document.body.querySelector('[data-test="import-result"]')).not.toBeNull()
    wrapper.unmount()
  })
})
