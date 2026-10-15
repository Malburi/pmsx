import { client } from './client'

export async function fetchCerts(params) {
  const res = await client.get('/certs', { params })
  return res.data
}

export const createCert = (cert) => client.post('/certs', cert)
