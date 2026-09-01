/// Waffo 要 PEM 格式的私钥，而后台复制出来的往往是裸 base64。
/// 两种都收，省得因为少了头尾两行排查半天。

export function toPem(key: string): string {
  const k = key.trim()
  if (k.includes('BEGIN')) return k
  const lines = k.replace(/\s+/g, '').match(/.{1,64}/g) ?? []
  return `-----BEGIN PRIVATE KEY-----\n${lines.join('\n')}\n-----END PRIVATE KEY-----`
}
