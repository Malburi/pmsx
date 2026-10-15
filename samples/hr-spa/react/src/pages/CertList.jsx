import { useEffect, useState } from 'react'
import { Button, TextField } from '@mui/material'
import { fetchCerts, createCert } from '../api/certApi'
import CertDialog from '../components/CertDialog'

export default function CertList() {
  const [keyword, setKeyword] = useState('')
  const [rows, setRows] = useState([])
  const [open, setOpen] = useState(false)

  const handleSearch = async () => {
    setRows(await fetchCerts({ keyword }))
  }

  const handleSave = async (cert) => {
    if (!window.confirm('등록하시겠습니까?')) return
    await createCert(cert)
    setOpen(false)
    await handleSearch()
  }

  useEffect(() => {
    handleSearch()
  }, [])

  return (
    <div className="page">
      <h2>자격증 관리</h2>
      <TextField id="certKeyword" label="자격증명" value={keyword} onChange={(e) => setKeyword(e.target.value)} />
      <Button id="btnCertSearch" onClick={handleSearch}>조회</Button>
      <Button id="btnCertNew" onClick={() => setOpen(true)}>신규 등록</Button>
      <table id="grdCert">
        <thead><tr><th>자격증명</th><th>발급기관</th><th>취득일</th></tr></thead>
        <tbody>{rows.map((r) => <tr key={r.certId}><td>{r.certNm}</td><td>{r.issuer}</td><td>{r.acqDt}</td></tr>)}</tbody>
      </table>
      <CertDialog open={open} onClose={() => setOpen(false)} onSave={handleSave} />
    </div>
  )
}
