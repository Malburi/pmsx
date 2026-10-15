import { useState } from 'react'
import { Button, TextField } from '@mui/material'

export default function CertDialog({ open, onClose, onSave }) {
  const [form, setForm] = useState({ certNm: '', issuer: '' })
  if (!open) return null
  return (
    <div className="dialog">
      <TextField id="dlgCertNm" label="자격증명" value={form.certNm} onChange={(e) => setForm({ ...form, certNm: e.target.value })} />
      <TextField id="dlgIssuer" label="발급기관" value={form.issuer} onChange={(e) => setForm({ ...form, issuer: e.target.value })} />
      <Button id="dlgCertSave" onClick={() => onSave(form)}>저장</Button>
      <Button id="dlgCertClose" onClick={onClose}>닫기</Button>
    </div>
  )
}
