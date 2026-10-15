// 폼 검증용 순수 함수 (메시지는 화면에서 t('validation.*') 로 붙인다)

/** 비밀번호 정책(NFR-SEC-005): 9자 이상, 영문·숫자·특수문자 중 3종 */
export const isPasswordPolicyOk = (v: string): boolean => {
  if (!v || v.length < 9) return false
  const kinds = [/[A-Za-z]/, /[0-9]/, /[^A-Za-z0-9]/].filter((re) => re.test(v)).length
  return kinds >= 3
}

export const isEmail = (v: string): boolean => !v || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v)

/** 엑셀 열 문자 (A ~ XFD) */
export const isColumnRef = (v: string): boolean => !v || /^[A-Z]{1,3}$/.test(v)

/** 엑셀 셀 주소 (C3) */
export const isCellRef = (v: string): boolean => !v || /^[A-Z]{1,3}[1-9][0-9]{0,6}$/.test(v)

/** 공통코드 그룹/코드 값: 영문 대문자·숫자·_ */
export const isCodeValue = (v: string): boolean => /^[A-Z0-9_]{1,30}$/.test(v)
