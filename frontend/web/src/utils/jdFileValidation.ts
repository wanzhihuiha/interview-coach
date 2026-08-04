export const JD_MAX_CODE_POINTS = 2000
export const JD_MAX_FILE_SIZE = 10 * 1024 * 1024
export const JD_MAX_PDF_PAGES = 20
const PDF_SIGNATURE_SCAN_BYTES = 1024
const PDF_SIGNATURE = new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2D])

export interface JdFileValidationResult {
  fileType: 'PDF' | 'TXT'
  codePointCount: number
  pdfPages?: number
}

export function normalizeJdContent(content: string): string {
  return content
    .replace(/^\uFEFF+/, '')
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n')
    .trim()
}

export function countUnicodeCodePoints(content: string): number {
  return Array.from(content).length
}

export function validateJdText(content: string): number {
  const normalized = normalizeJdContent(content)
  if (!normalized) {
    throw new Error('JD 描述不能为空')
  }
  const codePointCount = countUnicodeCodePoints(normalized)
  if (codePointCount > JD_MAX_CODE_POINTS) {
    throw new Error(`JD 描述不能超过 ${JD_MAX_CODE_POINTS} 个完整字符`)
  }
  return codePointCount
}

export async function validateJdFile(file: File): Promise<JdFileValidationResult> {
  if (file.size === 0) {
    throw new Error('文件为空，请重新选择')
  }
  if (file.size > JD_MAX_FILE_SIZE) {
    throw new Error('文件不能超过 10 MiB')
  }

  const extension = file.name.split('.').pop()?.toLowerCase()
  if (extension === 'txt') {
    return validateTxtFile(file)
  }
  if (extension === 'pdf') {
    return validatePdfFile(file)
  }
  throw new Error('仅支持 PDF 或 TXT 文件')
}

async function validateTxtFile(file: File): Promise<JdFileValidationResult> {
  if (file.type && !['text/plain', 'application/octet-stream'].includes(file.type)) {
    throw new Error('文件扩展名与实际类型不一致')
  }

  const bytes = new Uint8Array(await file.arrayBuffer())
  if (bytes.includes(0)) {
    throw new Error('TXT 文件包含无效的二进制内容')
  }

  let text: string
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    throw new Error('TXT 文件必须使用 UTF-8 编码')
  }
  return {
    fileType: 'TXT',
    codePointCount: validateJdText(text)
  }
}

async function validatePdfFile(file: File): Promise<JdFileValidationResult> {
  if (file.type && !['application/pdf', 'application/octet-stream'].includes(file.type)) {
    throw new Error('文件扩展名与实际类型不一致')
  }

  const bytes = new Uint8Array(await file.arrayBuffer())
  if (!hasPdfSignature(bytes)) {
    throw new Error('文件实际内容不是 PDF')
  }

  const [pdfjs, workerModule] = await Promise.all([
    import('pdfjs-dist'),
    import('pdfjs-dist/build/pdf.worker.min.mjs?url')
  ])
  pdfjs.GlobalWorkerOptions.workerSrc = workerModule.default

  const loadingTask = pdfjs.getDocument({ data: bytes })
  let pdfDocument: Awaited<typeof loadingTask.promise> | undefined
  try {
    pdfDocument = await loadingTask.promise
    if (pdfDocument.numPages > JD_MAX_PDF_PAGES) {
      throw new Error(`PDF 不能超过 ${JD_MAX_PDF_PAGES} 页`)
    }

    const pages: string[] = []
    for (let pageNumber = 1; pageNumber <= pdfDocument.numPages; pageNumber += 1) {
      const page = await pdfDocument.getPage(pageNumber)
      const textContent = await page.getTextContent()
      pages.push(textContent.items
        .map(item => ('str' in item ? item.str : ''))
        .join(' '))
      page.cleanup()
    }

    return {
      fileType: 'PDF',
      codePointCount: validateJdText(pages.join('\n')),
      pdfPages: pdfDocument.numPages
    }
  } catch (error) {
    if (error instanceof Error && error.name === 'PasswordException') {
      throw new Error('PDF 已加密，无法读取岗位内容')
    }
    throw error
  } finally {
    if (pdfDocument) {
      await pdfDocument.destroy()
    } else {
      await loadingTask.destroy()
    }
  }
}

function hasPdfSignature(bytes: Uint8Array): boolean {
  const scanLength = Math.min(bytes.length, PDF_SIGNATURE_SCAN_BYTES)
  for (let offset = 0; offset <= scanLength - PDF_SIGNATURE.length; offset += 1) {
    let matches = true
    for (let index = 0; index < PDF_SIGNATURE.length; index += 1) {
      if (bytes[offset + index] !== PDF_SIGNATURE[index]) {
        matches = false
        break
      }
    }
    if (matches) return true
  }
  return false
}
