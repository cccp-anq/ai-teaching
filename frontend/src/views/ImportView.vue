<script setup lang="ts">
import { computed, ref } from 'vue'
import { ApiClientError } from '../api/client'
import { importSupervisionEvaluations, SupervisionImportFailure } from '../api/supervision'
import type { SupervisionImportResult } from '../api/types'

type Phase = 'idle' | 'reading' | 'uploading' | 'result' | 'unauthorized' | 'forbidden' | 'error'

const MAX_TEXT_BYTES = 5 * 1024 * 1024
const text = ref('')
const sourceName = ref('manual-input.txt')
const fileInput = ref<HTMLInputElement | null>(null)
const phase = ref<Phase>('idle')
const result = ref<SupervisionImportResult | null>(null)
const message = ref<string | null>(null)
const busy = computed(() => phase.value === 'reading' || phase.value === 'uploading')

async function onTextFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  message.value = null
  result.value = null
  if (file === undefined) return
  if (!file.name.toLocaleLowerCase().endsWith('.txt')) {
    phase.value = 'error'
    message.value = '请选择 TXT 文本文件。'
    input.value = ''
    return
  }
  if (file.size > MAX_TEXT_BYTES) {
    phase.value = 'error'
    message.value = 'TXT 文件超过 5 MiB 上限。'
    input.value = ''
    return
  }
  phase.value = 'reading'
  try {
    text.value = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer())
    sourceName.value = file.name
    phase.value = 'idle'
  } catch {
    phase.value = 'error'
    message.value = 'TXT 文件不是有效的 UTF-8 文本。'
    input.value = ''
  }
}

async function submit(): Promise<void> {
  if (busy.value) return
  message.value = null
  result.value = null
  if (text.value.trim() === '') {
    phase.value = 'error'
    message.value = '请粘贴评价内容，或上传 TXT 文件。'
    return
  }
  if (new Blob([text.value]).size > MAX_TEXT_BYTES) {
    phase.value = 'error'
    message.value = '评价文本超过 5 MiB 上限。'
    return
  }
  phase.value = 'uploading'
  try {
    result.value = await importSupervisionEvaluations(text.value, sourceName.value)
    phase.value = 'result'
  } catch (cause) {
    if (cause instanceof SupervisionImportFailure) {
      result.value = cause.result
      phase.value = 'result'
      return
    }
    if (cause instanceof ApiClientError) {
      phase.value = cause.status === 401 ? 'unauthorized' : cause.status === 403 ? 'forbidden' : 'error'
      message.value = cause.message
      return
    }
    phase.value = 'error'
    message.value = '督导评价录入失败，请稍后重试。'
  }
}

function reset(): void {
  text.value = ''
  sourceName.value = 'manual-input.txt'
  result.value = null
  message.value = null
  phase.value = 'idle'
  if (fileInput.value !== null) fileInput.value.value = ''
}
</script>

<template>
  <section class="page evaluation-entry-page">
    <p class="eyebrow">教学评价 · DAT-04</p>
    <h1>督导评价录入</h1>
    <p class="lead">可以直接粘贴日常记录，也可以上传 UTF-8 TXT。系统会适配常见写法，并在提交前保留原文供检查。</p>

    <form class="card evaluation-form" aria-label="督导评价录入" @submit.prevent="submit">
      <div class="text-toolbar">
        <div>
          <label for="evaluation-text">评价内容</label>
          <p class="field-help">每行一条记录；支持制表符、逗号、中文逗号、竖线、空格和“字段名：值”的常见写法。</p>
        </div>
        <div class="file-picker">
          <label for="evaluation-file" class="button-like">上传 TXT 文件</label>
          <input id="evaluation-file" ref="fileInput" type="file" accept=".txt,text/plain" @change="onTextFileChange">
        </div>
      </div>
      <textarea
        id="evaluation-text"
        v-model="text"
        rows="13"
        placeholder="例如：&#10;101 已完成 2026-09-15 10:30&#10;开课编号：102，状态：已记录&#10;103 | COMPLETED | 2026/09/15 14:00"
      />
      <p v-if="sourceName !== 'manual-input.txt'" class="selected-file">已载入：{{ sourceName }}（可继续编辑）</p>
      <p class="fact-note">“已完成 / 完成 / COMPLETED”会统一识别为已覆盖；其他状态按原文保存。任一行有误时整批不写入。</p>
      <div class="actions">
        <button type="submit" :disabled="busy">{{ phase === 'uploading' ? '正在录入…' : '提交评价' }}</button>
        <button v-if="text !== '' || result !== null" type="button" class="secondary" :disabled="busy" @click="reset">清空</button>
      </div>
    </form>

    <div v-if="phase === 'reading'" class="state-note" role="status">正在读取 TXT 文件…</div>
    <div v-else-if="phase === 'unauthorized'" class="state-error" role="alert">登录状态已失效，请重新登录。</div>
    <div v-else-if="phase === 'forbidden'" class="state-error" role="alert">当前账号没有录入督导评价的权限。</div>
    <div v-else-if="phase === 'error'" class="state-error" role="alert">{{ message }}</div>

    <section v-if="result !== null" class="card result-card" aria-live="polite">
      <h2 v-if="result.status === 'SUCCESS'">督导评价录入成功</h2>
      <h2 v-else-if="result.status === 'ALREADY_IMPORTED'">这些内容已经录入过</h2>
      <h2 v-else>督导评价录入失败：本次全部未写入</h2>
      <dl class="result-facts">
        <div><dt>批次号</dt><dd>{{ result.batchId ?? '—' }}</dd></div>
        <div><dt>识别记录</dt><dd>{{ result.totalRows }}</dd></div>
        <div><dt>成功录入</dt><dd>{{ result.successRows }}</dd></div>
        <div><dt>失败记录</dt><dd>{{ result.failureRows }}</dd></div>
      </dl>
      <div v-if="result.errors.length > 0" class="error-detail">
        <h3>需要修改的内容</h3>
        <div class="table-wrap">
          <table>
            <thead><tr><th scope="col">行号</th><th scope="col">位置</th><th scope="col">原因</th></tr></thead>
            <tbody>
              <tr v-for="(error, index) in result.errors" :key="index">
                <td>{{ error.sourceIndex ?? '—' }}</td>
                <td>{{ error.field ?? '整行' }}</td>
                <td>{{ error.reason }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>
  </section>
</template>
