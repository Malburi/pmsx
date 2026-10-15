<template>
  <div v-if="modelValue" class="dialog-mask">
    <div class="dialog">
      <h3>{{ form.courseId ? '교육과정 수정' : '교육과정 등록' }}</h3>
      <div class="row"><label for="dlgCourseNm">과정명</label><input id="dlgCourseNm" v-model="form.courseNm" /></div>
      <div class="row"><label for="dlgStartDt">시작일</label><input id="dlgStartDt" type="date" v-model="form.startDt" /></div>
      <div class="row"><label for="dlgEndDt">종료일</label><input id="dlgEndDt" type="date" v-model="form.endDt" /></div>
      <div class="row"><label for="dlgCapacity">정원</label><input id="dlgCapacity" type="number" v-model="form.capacity" /></div>
      <div class="btns">
        <button id="dlgBtnSave" type="button" @click="save">저장</button>
        <button id="dlgBtnClose" type="button" @click="close">닫기</button>
      </div>
    </div>
  </div>
</template>

<script>
import { saveCourseApi, getCourseApi } from '@/api/eduApi'

/** Options API 로 작성된 대화상자 (레거시 Vue2 스타일) */
export default {
  name: 'EduCourseDialog',
  props: { modelValue: Boolean, course: Object },
  emits: ['update:modelValue', 'saved'],
  data() {
    return { form: {} }
  },
  watch: {
    course: {
      immediate: true,
      handler(v) {
        this.load(v)
      },
    },
  },
  methods: {
    async load(v) {
      this.form = v && v.courseId ? await getCourseApi(v.courseId) : {}
    },
    async save() {
      if (!this.form.courseNm) {
        alert('과정명을 입력하세요.')
        return
      }
      if (!confirm('저장하시겠습니까?')) return
      await saveCourseApi(this.form)
      this.$emit('saved')
      this.close()
    },
    close() {
      this.$emit('update:modelValue', false)
    },
  },
}
</script>
