#!/usr/bin/env node
/**
 * FaceBatch Web Gateway
 *
 * Keeps provider-specific signing material and restricted HTTP headers off the
 * public GitHub Pages client. It exposes a small, token-protected API to the
 * standalone FaceBatch HTML file.
 *
 * Node.js 22+ only. No third-party packages are required.
 */
import http from 'node:http';
import {
  constants as cryptoConstants,
  createCipheriv,
  createHash,
  createHmac,
  publicEncrypt,
  randomBytes,
  randomInt,
  randomUUID,
} from 'node:crypto';

const PORT = Number.parseInt(process.env.PORT || '8787', 10);
const HOST = process.env.HOST || '127.0.0.1';
const ACCESS_TOKEN = process.env.FACEBATCH_GATEWAY_TOKEN || '';
const ALLOWED_ORIGINS = new Set(
  (process.env.FACEBATCH_ALLOWED_ORIGINS || '')
    .split(',')
    .map((value) => value.trim())
    .filter(Boolean),
);
const MOCK_MODE = process.env.FACEBATCH_MOCK === '1';
const MAX_BODY_BYTES = Number.parseInt(process.env.FACEBATCH_MAX_BODY_BYTES || String(35 * 1024 * 1024), 10);
const DEFAULT_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36';

const AIFACE = Object.freeze({
  base: 'https://aifaceswap.io/',
  upload: 'https://aifaceswap.io/api/upload_file',
  single: 'https://aifaceswap.io/api/generate_face_v1',
  extract: 'https://aifaceswap.io/api/extract_url_face',
  multi: 'https://aifaceswap.io/api/generate_multi_face_v1',
  status: 'https://aifaceswap.io/api/check_status',
  resultBase: 'https://art-global.faceai.art/',
  appId: 'aifaceswap_v1',
  secret: '1H5tRtzsBkqXcaJ',
  publicKeyDerBase64:
    'MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCwlO+boC6cwRo3UfXVBadaYwcX' +
    '0zKS2fuVNY2qZ0dgwb1NJ+/Q9FeAosL4ONiosD71on3PVYqRUlL5045mvH2K9i8b' +
    'AFVMEip7E6RMK6tKAAif7xzZrXnP1GZ5Rijtqdgwh+YmzTo39cuBCsZqK9oEoeQ3' +
    'r/myG9S+9cR5huTuFQIDAQAB',
});

const FACEOVER = Object.freeze({
  login: 'https://faceover.tech/api/faceover2/users/android/v1/login.php',
  originalAppKey: '34673AF0DEDD5664B1D225E03696191C89CD4DABCBE730278E42D4A1250C230D',
});

const FJOY = Object.freeze({
  packageName: 'com.video.reface.app.faceplay.deepface.photo',
  appVersion: '1.1.8.1',
  appId: 'TX014',
  channel: 'GOOGLEPLAY',
  lang: 'en-US',
  country: 'US',
  version: '59',
  functionTag: 'changefacepic',
  s3Directory: 'changefacepic/TX014',
  base: 'https://aicup-v2.magicutapp.com/',
  register: 'https://analytics.enjoymobiserver.com/vsAnalytics/1.0.1/clientDevice/registerNewDevice.html?osType=1',
  getRegister: 'https://apis.videoshowapp.com/zone/1.0.1/point/user/getNewUserPointInfo.htm?osType=1',
  addCoin: 'https://apis.videoshowapp.com/zone/1.0.1/point/task/doAddTaskPoint.htm?osType=1',
  getUuid: 'https://aicup-v2.magicutapp.com/v4/getUuid',
  changeFace: 'https://aicup-v2.magicutapp.com/v3/changeFacePic',
  statusBase: 'https://aicup-v2.magicutapp.com/v4/downLoad/',
  desEdeKey: 'NTMyMzExc2RmXXXXXXXXXXXX',
  publicKeyDerBase64:
    'MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCblAv5qv/3dlYilRI23jRWhJIWivzvVEtyOEVpIUp9JGQC8479Me0pRb/ZFUzm1U7rqoBI0ByaN+SfEbhpCAaPGuR7E71qe18NNDKUhgUsOGUHr6clTPjzjHl46wS8I8hOzioH6Z9Op3hkbkPJC469EyulfvH8BuEH9myuSzaf/wIDAQAB',
});

const TAO_DEFAULT = Object.freeze({
  endpoint: 'https://api.taoanhdep.com/doi-mat',
  sourceField: 'source',
  targetField: 'target',
  enhancerField: 'enhancer',
  safetyField: 'check-nsfw',
  resultPath: 'result.image',
  origin: 'https://taoanhdep.com',
});

let aiSession = null;
let faceOverRouteCache = null;
let fjoySession = null;
let fjoySuccessCount = 0;
let fjoyCooldownUntil = 0;
let currentBatchId = '';

class GatewayError extends Error {
  constructor(message, status = 500, details = undefined) {
    super(message);
    this.name = 'GatewayError';
    this.status = status;
    this.details = details;
  }
}

function sha256(data) {
  return createHash('sha256').update(data).digest();
}
function sha256Hex(data) {
  return sha256(data).toString('hex');
}
function md5Hex(data) {
  return createHash('md5').update(data).digest('hex');
}
function hmacSha256(key, data) {
  return createHmac('sha256', key).update(data).digest();
}
function randomAlphaNumeric(length) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let value = '';
  for (let index = 0; index < length; index += 1) value += alphabet[randomInt(alphabet.length)];
  return value;
}
function pemFromDer(base64) {
  const lines = base64.match(/.{1,64}/g) || [];
  return `-----BEGIN PUBLIC KEY-----\n${lines.join('\n')}\n-----END PUBLIC KEY-----`;
}
function rsaPkcs1Encrypt(base64Der, data) {
  return publicEncrypt(
    { key: pemFromDer(base64Der), padding: cryptoConstants.RSA_PKCS1_PADDING },
    data,
  );
}
function rsaPkcs1EncryptChunks(base64Der, data, chunkSize = 117) {
  const chunks = [];
  for (let offset = 0; offset < data.length; offset += chunkSize) {
    chunks.push(rsaPkcs1Encrypt(base64Der, data.subarray(offset, offset + chunkSize)));
  }
  return Buffer.concat(chunks);
}
function aesCbcBase64(plain, keyAndIv) {
  const key = Buffer.from(keyAndIv, 'utf8');
  const cipher = createCipheriv('aes-128-cbc', key, key);
  return Buffer.concat([cipher.update(Buffer.from(plain, 'utf8')), cipher.final()]).toString('base64');
}
function encryptAIFacePayload(json, themeVersion) {
  const key = sha256(Buffer.from(themeVersion, 'utf8'));
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  const ciphertext = Buffer.concat([cipher.update(Buffer.from(json, 'utf8')), cipher.final()]);
  return Buffer.concat([iv, ciphertext, cipher.getAuthTag()]).toString('base64');
}
function jsonCompact(value) {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  return text.replace(/\s+/g, ' ').trim().slice(0, 900);
}
function baseName(path) {
  const tail = String(path || '').split('/').pop() || '';
  const dot = tail.lastIndexOf('.');
  return dot > 0 ? tail.slice(0, dot) : tail;
}
function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
function asBoolean(value, fallback = false) {
  if (typeof value === 'boolean') return value;
  if (value === 'true' || value === '1' || value === 1) return true;
  if (value === 'false' || value === '0' || value === 0) return false;
  return fallback;
}
function requireFile(form, name) {
  const value = form.get(name);
  if (!(value instanceof File)) throw new GatewayError(`Missing image field: ${name}`, 400);
  return value;
}
async function fileBuffer(file) {
  return Buffer.from(await file.arrayBuffer());
}
function parseJson(text, label) {
  try {
    return JSON.parse(text);
  } catch {
    throw new GatewayError(`${label} returned non-JSON data: ${jsonCompact(text)}`, 502);
  }
}
function readPath(value, path) {
  if (!path) return value;
  const tokens = String(path)
    .replace(/\[(\d+)\]/g, '.$1')
    .split('.')
    .map((token) => token.trim())
    .filter(Boolean);
  let current = value;
  for (const token of tokens) {
    if (current == null || !(token in Object(current))) return undefined;
    current = current[token];
  }
  return current;
}
function headersObject(headers) {
  const result = {};
  for (const [key, value] of headers.entries()) result[key] = value;
  return result;
}
async function fetchWithTimeout(url, options = {}, timeoutMs = 150000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new Error('Request timed out')), timeoutMs);
  try {
    return await fetch(url, { redirect: 'follow', ...options, signal: controller.signal });
  } catch (error) {
    if (error?.name === 'AbortError') throw new GatewayError(`Request timed out: ${url}`, 504);
    throw error;
  } finally {
    clearTimeout(timer);
  }
}
async function fetchTextChecked(url, options, stage, timeoutMs = 150000) {
  const response = await fetchWithTimeout(url, options, timeoutMs);
  const text = await response.text();
  if (!response.ok) {
    const error = new GatewayError(`${stage} returned HTTP ${response.status}${text ? `: ${jsonCompact(text)}` : ''}`, response.status);
    error.upstreamStatus = response.status;
    throw error;
  }
  return { response, text };
}
async function fetchBufferChecked(url, options, stage, timeoutMs = 150000) {
  const response = await fetchWithTimeout(url, options, timeoutMs);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (!response.ok) {
    throw new GatewayError(`${stage} returned HTTP ${response.status}${bytes.length ? `: ${jsonCompact(bytes.toString('utf8'))}` : ''}`, response.status);
  }
  return { response, bytes };
}
function contentTypeForImage(bytes, fallback = 'application/octet-stream') {
  if (bytes.length >= 2 && bytes[0] === 0xff && bytes[1] === 0xd8) return 'image/jpeg';
  if (bytes.length >= 8 && bytes[0] === 0x89 && bytes.slice(1, 4).toString('ascii') === 'PNG') return 'image/png';
  if (bytes.length >= 6 && bytes.slice(0, 3).toString('ascii') === 'GIF') return 'image/gif';
  if (bytes.length >= 12 && bytes.slice(0, 4).toString('ascii') === 'RIFF' && bytes.slice(8, 12).toString('ascii') === 'WEBP') return 'image/webp';
  return fallback;
}
function ensureImage(bytes, label) {
  const type = contentTypeForImage(bytes);
  if (!type.startsWith('image/')) throw new GatewayError(`${label} did not return a recognized image.`, 502);
  return type;
}

function aiHeaders(session, extra = {}) {
  return {
    accept: '*/*',
    'user-agent': DEFAULT_UA,
    referer: AIFACE.base,
    origin: 'https://aifaceswap.io',
    'theme-version': session.themeVersion,
    'x-code': String(Date.now()),
    ...(session.cookies ? { cookie: session.cookies } : {}),
    ...extra,
  };
}
function collectCookies(headers) {
  const raw = typeof headers.getSetCookie === 'function' ? headers.getSetCookie() : [];
  const fallback = headers.get('set-cookie');
  const values = raw.length ? raw : fallback ? [fallback] : [];
  const map = new Map();
  for (const header of values) {
    for (const part of String(header).split(/,(?=[^;]+?=)/g)) {
      const pair = part.split(';', 1)[0].trim();
      const equals = pair.indexOf('=');
      if (equals > 0) map.set(pair.slice(0, equals).trim(), pair.slice(equals + 1).trim());
    }
  }
  return [...map.entries()].map(([key, value]) => `${key}=${value}`).join('; ');
}
function parseThemeVersion(html) {
  const patterns = [
    /<[^>]*id\s*=\s*['"]theme-version['"][^>]*data-kt-theme-version\s*=\s*['"]([^'"]+)['"]/is,
    /<[^>]*data-kt-theme-version\s*=\s*['"]([^'"]+)['"][^>]*id\s*=\s*['"]theme-version['"]/is,
    /data-kt-theme-version\s*=\s*['"]([^'"]+)['"]/is,
    /theme-version\s*[:=]\s*['"]([^'"]+)['"]/is,
  ];
  for (const pattern of patterns) {
    const match = pattern.exec(html);
    if (match) return match[1].replaceAll('&quot;', '"').replaceAll('&#39;', "'").replaceAll('&amp;', '&').trim();
  }
  return '';
}
async function getAIFaceSession(force = false) {
  if (!force && aiSession && Date.now() - aiSession.createdAt < 8 * 60 * 1000) return aiSession;
  const { response, text } = await fetchTextChecked(
    AIFACE.base,
    { headers: { 'user-agent': DEFAULT_UA, accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8' } },
    'AIFaceSwap setup',
    120000,
  );
  const themeVersion = parseThemeVersion(text);
  if (!themeVersion) throw new GatewayError(`AIFaceSwap setup did not expose a theme version: ${jsonCompact(text)}`, 502);
  aiSession = { themeVersion, cookies: collectCookies(response.headers), createdAt: Date.now() };
  return aiSession;
}
async function aiPostJson(url, body, session, extraHeaders = {}, stage = 'AIFaceSwap request') {
  const { text } = await fetchTextChecked(
    url,
    { method: 'POST', headers: aiHeaders(session, { 'content-type': 'application/json; charset=UTF-8', ...extraHeaders }), body: JSON.stringify(body) },
    stage,
  );
  return parseJson(text, stage);
}
function aiApiError(prefix, response) {
  const code = Number(response?.code || 0);
  const message = response?.message || response?.msg || response?.error?.message || jsonCompact(response);
  return new GatewayError(`${prefix}${code ? ` (code ${code})` : ''}: ${message}`, 502, response);
}
async function aiRequestUpload(session) {
  const response = await aiPostJson(
    AIFACE.upload,
    { file_name: `${randomBytes(16).toString('hex')}.jpg`, type: 'image' },
    session,
    {},
    'AIFaceSwap upload-URL request',
  );
  const url = response?.data?.url || '';
  if (![0, 200].includes(Number(response?.code || 0)) || !url) throw aiApiError('AIFaceSwap did not provide an upload URL', response);
  return url;
}
function aiRelativeUploadPath(url) {
  const match = /(aifaceswap\/upload_res\/[^?]+)/.exec(url);
  if (match) return match[1];
  const parsed = new URL(url);
  const path = parsed.pathname.replace(/^\/+/, '');
  if (!path) throw new GatewayError('AIFaceSwap returned an unreadable upload URL.', 502);
  return path;
}
async function aiUploadImage(bytes, session) {
  const signedUrl = await aiRequestUpload(session);
  await fetchBufferChecked(
    signedUrl,
    {
      method: 'PUT',
      headers: aiHeaders(session, {
        'content-type': 'image/jpg',
        'x-oss-storage-class': 'Standard',
      }),
      body: bytes,
    },
    'AIFaceSwap image upload',
  );
  return aiRelativeUploadPath(signedUrl);
}
function aiSignature() {
  const aesSecret = randomAlphaNumeric(16);
  const xGuide = rsaPkcs1Encrypt(AIFACE.publicKeyDerBase64, Buffer.from(aesSecret, 'utf8')).toString('base64');
  const signingPlaintext = `${AIFACE.appId}:${AIFACE.secret}:${Math.floor(Date.now() / 1000)}:${randomUUID()}:${xGuide}`;
  return { aesSecret, xGuide, xSign: aesCbcBase64(signingPlaintext, aesSecret) };
}
function aiGenerationHeaders(session, nonce) {
  const signature = aiSignature();
  const fp = md5Hex(`${process.platform}:${process.arch}:${process.version}:${randomUUID()}`);
  return aiHeaders(session, {
    'x-guide': signature.xGuide,
    'x-sign': signature.xSign,
    nonce,
    fp,
    fp1: aesCbcBase64(`${AIFACE.appId}:${fp}`, signature.aesSecret),
  });
}
async function aiSubmit(url, requestType, plainPayload, nonce, session) {
  const body = { request_type: requestType, data: encryptAIFacePayload(JSON.stringify(plainPayload), session.themeVersion) };
  const { text } = await fetchTextChecked(
    url,
    { method: 'POST', headers: { ...aiGenerationHeaders(session, nonce), 'content-type': 'application/json; charset=UTF-8' }, body: JSON.stringify(body) },
    'AIFaceSwap generation request',
  );
  const response = parseJson(text, 'AIFaceSwap generation request');
  const taskId = response?.data?.task_id || '';
  if (![0, 200].includes(Number(response?.code || 0)) || !taskId) throw aiApiError('AIFaceSwap rejected the generation request', response);
  return taskId;
}
function aiTerminal(response) {
  const code = Number(response?.code || 0);
  const status = String(response?.status || response?.data?.status || '').toLowerCase();
  return code >= 400 || ['failed', 'error', 'cancelled', 'canceled', 'refunded'].includes(status);
}
function aiResultUrl(value) {
  const result = String(value || '').trim();
  if (result.startsWith('https://')) return result;
  if (result.startsWith('http://')) throw new GatewayError('AIFaceSwap returned an insecure result URL.', 502);
  if (result.startsWith('//')) return `https:${result}`;
  return new URL(result.replace(/^\/+/, ''), AIFACE.resultBase).toString();
}
async function aiPoll(taskId, nonce, session) {
  for (let attempt = 0; attempt < 90; attempt += 1) {
    await sleep(2000);
    const response = await aiPostJson(AIFACE.status, { task_id: taskId, nonce }, session, {}, 'AIFaceSwap status request');
    const result = response?.data?.result_image || '';
    if (Number(response?.code || 0) === 200 && result) {
      const { response: imageResponse, bytes } = await fetchBufferChecked(
        aiResultUrl(result),
        { headers: { accept: '*/*', 'user-agent': DEFAULT_UA } },
        'AIFaceSwap result download',
      );
      return { bytes, contentType: ensureImage(bytes, 'AIFaceSwap result') || imageResponse.headers.get('content-type') };
    }
    if (aiTerminal(response)) throw aiApiError('AIFaceSwap generation failed', response);
  }
  throw new GatewayError('AIFaceSwap did not finish within three minutes.', 504);
}
async function withRefreshedAIFaceSession(operation) {
  let lastError;
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      const session = await getAIFaceSession(attempt > 0);
      return await operation(session);
    } catch (error) {
      lastError = error;
      const message = String(error?.message || '').toLowerCase();
      const shouldRefresh = [401, 403, 419].includes(error?.status) || /theme|cookie|signature|x-sign|x-guide/.test(message);
      if (attempt > 0 || !shouldRefresh) throw error;
      aiSession = null;
      await sleep(900);
    }
  }
  throw lastError;
}
async function aifaceSingle(targetBytes, donorBytes) {
  return withRefreshedAIFaceSession(async (session) => {
    const targetPath = await aiUploadImage(targetBytes, session);
    const donorPath = await aiUploadImage(donorBytes, session);
    const nonce = md5Hex(`${baseName(targetPath)}:${baseName(donorPath)}`);
    const taskId = await aiSubmit(
      AIFACE.single,
      0,
      { source_image: targetPath, face_image: donorPath, type_1: 0, type_2: 0 },
      nonce,
      session,
    );
    return aiPoll(taskId, nonce, session);
  });
}
async function aifaceAnalyze(targetBytes) {
  return withRefreshedAIFaceSession(async (session) => {
    const targetPath = await aiUploadImage(targetBytes, session);
    const response = await aiPostJson(AIFACE.extract, { img_url: targetPath }, session, {}, 'AIFaceSwap face extraction');
    const faces = response?.data?.pos || response?.data?.faces || [];
    if (![0, 200].includes(Number(response?.code || 0)) || !Array.isArray(faces)) throw aiApiError('AIFaceSwap face extraction failed', response);
    return { faces: faces.map((face) => face.map((value) => Number(value))), providerTargetPath: targetPath };
  });
}
async function aifaceMulti(targetBytes, donorBytesById, assignments) {
  return withRefreshedAIFaceSession(async (session) => {
    const targetPath = await aiUploadImage(targetBytes, session);
    const uploadById = new Map();
    for (const [donorId, bytes] of donorBytesById) uploadById.set(donorId, await aiUploadImage(bytes, session));
    const ordered = [...assignments]
      .map((entry) => ({ index: Number(entry.faceIndex), donorId: String(entry.donorId) }))
      .filter((entry) => Number.isInteger(entry.index) && uploadById.has(entry.donorId))
      .sort((left, right) => left.index - right.index);
    if (!ordered.length) throw new GatewayError('At least one face assignment is required.', 400);
    let noncePlain = baseName(targetPath);
    const faceImage = ordered.map((entry) => {
      const path = uploadById.get(entry.donorId);
      noncePlain += `:${baseName(path)}_${entry.index}`;
      return { index: String(entry.index), url: path };
    });
    const nonce = md5Hex(noncePlain);
    const taskId = await aiSubmit(
      AIFACE.multi,
      2,
      { source_image: targetPath, face_image: faceImage, type_1: 0, type_2: 0 },
      nonce,
      session,
    );
    return aiPoll(taskId, nonce, session);
  });
}

function resetFjoySession(cooldownMs = 0) {
  fjoySession = null;
  fjoySuccessCount = 0;
  fjoyCooldownUntil = Math.max(fjoyCooldownUntil, Date.now() + Math.max(0, cooldownMs));
}
async function waitFjoyCooldown() {
  const remaining = fjoyCooldownUntil - Date.now();
  if (remaining > 0) await sleep(remaining);
}
function createFjoyIdentity() {
  const models = ['SM-G9880', 'MI-10T', 'Pixel-6', 'OnePlus-9', 'Reno-5', 'V21', 'Realme-8', 'P40-Pro'];
  const brands = ['samsung', 'xiaomi', 'oneplus', 'google', 'oppo', 'vivo', 'realme', 'huawei'];
  return {
    appUuid: `jrxc_${randomUUID().replaceAll('-', '').slice(0, 16)}`,
    deviceUuid: `jtc3_${randomUUID().replaceAll('-', '').slice(0, 32)}`,
    userPseudoId: randomBytes(16).toString('hex'),
    deviceAdId: randomUUID(),
    phoneModel: models[randomInt(models.length)],
    phoneBrand: brands[randomInt(brands.length)],
    requestId: `${process.hrtime.bigint()}${randomInt(10000)}`,
    osVersion: 10 + randomInt(6),
  };
}
function fjoyAnalyticsUserAgent(identity) {
  return `${FJOY.channel}/${FJOY.packageName}/${FJOY.appVersion} (Linux; U; Android ${identity.osVersion}; ${identity.phoneModel}/${identity.phoneBrand})`;
}
function fjoyRegistrationJson(identity) {
  return JSON.stringify({
    deviceUuid: identity.appUuid,
    appVersion: FJOY.appVersion,
    userPseudoId: identity.userPseudoId,
    uuId: identity.deviceUuid,
    phoneModel: identity.phoneModel,
    deviceAdId: identity.deviceAdId,
    osVersion: String(identity.osVersion),
    requestId: identity.requestId,
    phoneBrand: identity.phoneBrand,
    pkgName: FJOY.packageName,
    channelName: FJOY.channel,
    lang: FJOY.lang,
  });
}
function fjoyAddCoinJson(identity) {
  return JSON.stringify({
    deviceUuid: identity.appUuid,
    uuId: identity.deviceUuid,
    userId: '',
    pkgName: FJOY.packageName,
    lang: FJOY.lang,
    versionName: FJOY.appVersion,
    channelName: FJOY.channel,
    requestId: `${process.hrtime.bigint()}${randomInt(10000)}`,
    pointId: identity.appUuid,
    taskId: '24',
  });
}
function encryptDesEde(plain) {
  const cipher = createCipheriv('des-ede3', Buffer.from(FJOY.desEdeKey, 'utf8'), null);
  return Buffer.concat([cipher.update(Buffer.from(plain, 'utf8')), cipher.final()]);
}
async function fjoyPostEncrypted(url, json, identity, includeDeviceIdentity) {
  const headers = {
    'content-type': 'application/octet-stream',
    charset: 'utf-8',
    'user-agent': fjoyAnalyticsUserAgent(identity),
    'x-uuid': identity.deviceUuid,
    'x-openid': '',
  };
  if (includeDeviceIdentity) {
    headers['x-deviceuuid'] = identity.appUuid;
    headers['x-userid'] = '';
  }
  await fetchBufferChecked(url, { method: 'POST', headers, body: encryptDesEde(json) }, 'Face Over FJoy setup', 120000);
}
function fjoyBrowserHeaders(extra = {}) {
  return { 'user-agent': DEFAULT_UA, accept: '*/*', origin: 'https://taoanhdep.com', ...extra };
}
async function fjoyRequestForm(url, values) {
  const body = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) body.set(key, value == null ? '' : String(value));
  const { text } = await fetchTextChecked(
    url,
    { method: 'POST', headers: fjoyBrowserHeaders({ 'content-type': 'application/x-www-form-urlencoded; charset=UTF-8' }), body },
    'FJoy request',
    120000,
  );
  return parseJson(text, 'FJoy response');
}
function parseFjoyOther(json) {
  if (!json || typeof json !== 'object') return null;
  const other = {
    accessKey: String(json.accessKey || ''),
    baseUrl: String(json.baseUrl || ''),
    bucketName: String(json.bucketName || ''),
    bucketType: String(json.bucketType || ''),
    dir: String(json.dir || ''),
    region: String(json.region || ''),
    secretKey: String(json.secretKey || ''),
  };
  return other.accessKey || other.bucketName || other.secretKey ? other : null;
}
async function fjoyGetUuid(identity, uid = '', includeFunctionTag = false) {
  const form = {
    country: FJOY.country,
    uId: uid,
    appVersion: FJOY.appVersion,
    appTime: String(Date.now()),
    appId: FJOY.appId,
    pkgName: FJOY.packageName,
    channelName: FJOY.channel,
    lang: FJOY.lang,
    uuId: identity.appUuid,
    version: FJOY.version,
    isVip: 'false',
  };
  if (includeFunctionTag) form.functionTag = FJOY.functionTag;
  const json = await fjoyRequestForm(FJOY.getUuid, form);
  return {
    success: Boolean(json.success),
    code: Number(json.code || 0),
    data: typeof json.data === 'string' || typeof json.data === 'number' ? String(json.data) : '',
    message: String(json.message || ''),
    other: parseFjoyOther(json.other) || parseFjoyOther(json.data?.other),
  };
}
async function prepareFjoySession() {
  if (fjoySession?.uid && fjoySession?.credentials) {
    try {
      const existing = await fjoyGetUuid(fjoySession.identity, fjoySession.uid, true);
      if (existing.success && existing.other) {
        fjoySession.uid = existing.data.trim() || fjoySession.uid;
        fjoySession.credentials = existing.other;
        return fjoySession;
      }
    } catch {
      // Re-bootstrap below.
    }
  }
  const identity = fjoySession?.identity || createFjoyIdentity();
  await fjoyPostEncrypted(FJOY.register, fjoyRegistrationJson(identity), identity, false);
  await fjoyPostEncrypted(FJOY.getRegister, fjoyRegistrationJson(identity), identity, true);
  await sleep(1800);
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      await fjoyPostEncrypted(FJOY.addCoin, fjoyAddCoinJson(identity), identity, true);
      break;
    } catch (error) {
      if (attempt > 0) break;
      await sleep(1000);
    }
  }
  const first = await fjoyGetUuid(identity, '', false);
  if (!first.success) throw new GatewayError(`FJoy initialization failed: ${first.message}`, 502);
  let uid = first.data.trim();
  let credentials = first.other;
  if (!credentials) {
    if (!uid) throw new GatewayError('FJoy did not return a user id or upload credentials.', 502);
    const second = await fjoyGetUuid(identity, uid, true);
    if (!second.success || !second.other) throw new GatewayError(`FJoy did not return upload credentials: ${second.message}`, 502);
    credentials = second.other;
    uid = second.data.trim() || uid;
  }
  if (!uid) throw new GatewayError('FJoy returned upload credentials but no user id.', 502);
  fjoySession = { identity, uid, credentials };
  await sleep(1200);
  return fjoySession;
}
function awsEncode(value) {
  return encodeURIComponent(value).replace(/[!'()*]/g, (character) => `%${character.charCodeAt(0).toString(16).toUpperCase()}`);
}
function fjoyObjectName(uid) {
  return `${sha256Hex(Buffer.from(`${Date.now()}${uid}`, 'utf8')).slice(0, 32)}.webp`;
}
async function fjoyUploadS3(bytes, filename, other) {
  if (!other.accessKey || !other.secretKey || !other.bucketName || !other.region) throw new GatewayError('FJoy returned incomplete upload credentials.', 502);
  const key = `${FJOY.s3Directory}/${filename}`;
  const encodedPath = key.split('/').map(awsEncode).join('/');
  const region = other.region.trim();
  const host = region === 'us-east-1' ? `${other.bucketName}.s3.amazonaws.com` : `${other.bucketName}.s3.${region}.amazonaws.com`;
  const url = `https://${host}/${encodedPath}`;
  const payloadHash = sha256Hex(bytes);
  const now = new Date();
  const iso = now.toISOString().replace(/[:-]|\.\d{3}/g, '');
  const amzDate = iso;
  const date = iso.slice(0, 8);
  const canonicalHeaders = `host:${host}\nx-amz-acl:public-read\nx-amz-content-sha256:${payloadHash}\nx-amz-date:${amzDate}\n`;
  const signedHeaders = 'host;x-amz-acl;x-amz-content-sha256;x-amz-date';
  const canonicalRequest = `PUT\n/${encodedPath}\n\n${canonicalHeaders}\n${signedHeaders}\n${payloadHash}`;
  const scope = `${date}/${region}/s3/aws4_request`;
  const stringToSign = `AWS4-HMAC-SHA256\n${amzDate}\n${scope}\n${sha256Hex(Buffer.from(canonicalRequest, 'utf8'))}`;
  const kDate = hmacSha256(Buffer.from(`AWS4${other.secretKey}`, 'utf8'), date);
  const kRegion = hmacSha256(kDate, region);
  const kService = hmacSha256(kRegion, 's3');
  const signingKey = hmacSha256(kService, 'aws4_request');
  const signature = hmacSha256(signingKey, stringToSign).toString('hex');
  const authorization = `AWS4-HMAC-SHA256 Credential=${other.accessKey}/${scope}, SignedHeaders=${signedHeaders}, Signature=${signature}`;
  await fetchBufferChecked(
    url,
    {
      method: 'PUT',
      headers: {
        'x-amz-acl': 'public-read',
        'x-amz-content-sha256': payloadHash,
        'x-amz-date': amzDate,
        authorization,
        'content-type': 'application/octet-stream',
      },
      body: bytes,
    },
    'FJoy S3 upload',
  );
  return url;
}
function fjoyRsaEncrypt(plain) {
  return rsaPkcs1EncryptChunks(FJOY.publicKeyDerBase64, Buffer.from(plain, 'utf8'), 117).toString('base64');
}
async function fjoySubmitSwap(identity, uid, targetUrl, donorUrl) {
  const signedTime = Date.now();
  const payload = {
    uId: uid,
    appTime: String(signedTime),
    modelFile: targetUrl,
    imageFile: donorUrl,
    pkgName: FJOY.packageName,
    materialType: '0',
    check: '0',
    priority: '50',
    picId: '0',
    pointId: identity.appUuid,
    consumeType: 'swappictures',
  };
  const form = new FormData();
  const parts = {
    country: FJOY.country,
    uId: uid,
    appVersion: FJOY.appVersion,
    appTime: String(Date.now()),
    appId: FJOY.appId,
    pkgName: FJOY.packageName,
    channelName: FJOY.channel,
    lang: FJOY.lang,
    uuId: identity.appUuid,
    version: FJOY.version,
    isVip: 'false',
    decrypt: fjoyRsaEncrypt(JSON.stringify(payload)),
    sign: md5Hex(`${FJOY.packageName}${uid}${signedTime}`),
  };
  for (const [key, value] of Object.entries(parts)) form.set(key, value);
  const { text } = await fetchTextChecked(
    FJOY.changeFace,
    { method: 'POST', headers: fjoyBrowserHeaders(), body: form },
    'FJoy submit',
    120000,
  );
  const json = parseJson(text, 'FJoy submit response');
  const task = typeof json.data === 'string' || typeof json.data === 'number' ? String(json.data).trim() : '';
  if (Number(json.code ?? -1) !== 0 || !task) throw new GatewayError(`FJoy rejected the swap (code ${json.code}): ${json.message || ''}`, 502);
  return task;
}
async function fjoyPoll(taskId, credentials) {
  const statusUrl = `${FJOY.statusBase}${encodeURIComponent(taskId)}`;
  for (let attempt = 0; attempt < 60; attempt += 1) {
    await sleep(2000);
    const { text } = await fetchTextChecked(
      statusUrl,
      { method: 'POST', headers: fjoyBrowserHeaders(), body: '' },
      'FJoy status',
      120000,
    );
    const json = parseJson(text, 'FJoy status response');
    if (Number(json.code || 0) > 1000) throw new GatewayError(`FJoy status failed with code ${json.code}: ${json.message || ''}`, 502);
    let result = typeof json.data === 'string' ? json.data.trim() : '';
    if (result && result.toLowerCase() !== 'null') {
      if (result.startsWith('//')) result = `https:${result}`;
      else if (result.startsWith('/')) result = new URL(result, FJOY.base).toString();
      else if (!/^https?:\/\//i.test(result) && credentials.baseUrl) result = new URL(result, credentials.baseUrl.endsWith('/') ? credentials.baseUrl : `${credentials.baseUrl}/`).toString();
      if (result.startsWith('https://')) {
        const { response, bytes } = await fetchBufferChecked(result, { headers: { accept: '*/*', 'user-agent': DEFAULT_UA } }, 'FJoy result download');
        return { bytes, contentType: ensureImage(bytes, 'FJoy result') || response.headers.get('content-type') };
      }
    }
  }
  throw new GatewayError('FJoy did not finish the swap within two minutes.', 504);
}
function isMissingFjoyUser(error) {
  const message = String(error?.message || '').toLowerCase();
  return /code 1404|1404:|points-system user does not exist|user does not exist|积分系统用户不存在|用户不存在/.test(message);
}
async function fjoySingle(targetBytes, donorBytes) {
  if (fjoySuccessCount >= 3) {
    resetFjoySession(8000);
    await waitFjoyCooldown();
  }
  let lastError;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    if (attempt === 1) {
      resetFjoySession(8000);
      await waitFjoyCooldown();
    } else if (attempt === 2) {
      resetFjoySession(12000);
      await waitFjoyCooldown();
    }
    try {
      await waitFjoyCooldown();
      const session = await prepareFjoySession();
      const targetUrl = await fjoyUploadS3(targetBytes, fjoyObjectName(session.uid), session.credentials);
      const donorUrl = await fjoyUploadS3(donorBytes, fjoyObjectName(session.uid), session.credentials);
      const taskId = await fjoySubmitSwap(session.identity, session.uid, targetUrl, donorUrl);
      const result = await fjoyPoll(taskId, session.credentials);
      fjoySuccessCount += 1;
      return result;
    } catch (error) {
      lastError = error;
      if (!isMissingFjoyUser(error) || attempt === 2) throw error;
    }
  }
  throw lastError || new GatewayError('FJoy could not establish a valid points-system user.', 502);
}

async function faceOverRoute() {
  if (faceOverRouteCache && Date.now() - faceOverRouteCache.createdAt < 10 * 60 * 1000) return faceOverRouteCache.route;
  const body = new URLSearchParams({ key: FACEOVER.originalAppKey, device_id: `facebatch-web-${randomUUID()}` });
  const { text } = await fetchTextChecked(
    FACEOVER.login,
    { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded; charset=UTF-8' }, body },
    'Face Over routing login',
    120000,
  );
  const json = parseJson(text, 'Face Over routing login');
  const list = json.single_type || json.singleType || json.data?.single_type || json.data?.singleType;
  if (!Array.isArray(list) || !list.length) throw new GatewayError(`Face Over did not return a single-face backend route: ${jsonCompact(json)}`, 502);
  const route = Number.parseInt(String(list[0]), 10);
  if (!Number.isFinite(route)) throw new GatewayError(`Face Over returned an unreadable backend route: ${list[0]}`, 502);
  faceOverRouteCache = { route, createdAt: Date.now() };
  return route;
}

function normalizeCustomSettings(input = {}) {
  return {
    endpoint: String(input.endpoint || TAO_DEFAULT.endpoint).trim(),
    sourceField: String(input.sourceField || TAO_DEFAULT.sourceField).trim(),
    targetField: String(input.targetField || TAO_DEFAULT.targetField).trim(),
    swapMappings: asBoolean(input.swapMappings, false),
    enhancerEnabled: asBoolean(input.enhancerEnabled, true),
    enhancerField: String(input.enhancerField ?? TAO_DEFAULT.enhancerField).trim(),
    safetyField: String(input.safetyField ?? TAO_DEFAULT.safetyField).trim(),
    extraFormFields: input.extraFormFields && typeof input.extraFormFields === 'object' ? input.extraFormFields : {},
    resultPath: String(input.resultPath || TAO_DEFAULT.resultPath).trim(),
    responseMode: String(input.responseMode || 'auto').trim(),
    pollUrlPath: String(input.pollUrlPath || 'polling_url').trim(),
    pollIdPath: String(input.pollIdPath || 'id').trim(),
    pollUrlTemplate: String(input.pollUrlTemplate || '').trim(),
    pollStatusPath: String(input.pollStatusPath || 'status').trim(),
    pollSuccessValue: String(input.pollSuccessValue || 'success').trim(),
    pollFailureValues: String(input.pollFailureValues || 'failed,error,cancelled').split(',').map((value) => value.trim().toLowerCase()).filter(Boolean),
    pollIntervalSeconds: Math.min(60, Math.max(1, Number(input.pollIntervalSeconds || 3))),
    maxPolls: Math.min(1000, Math.max(1, Number(input.maxPolls || 100))),
    origin: String(input.origin || TAO_DEFAULT.origin).trim(),
    userAgent: String(input.userAgent || DEFAULT_UA).trim() || DEFAULT_UA,
    authHeaderName: String(input.authHeaderName || '').trim(),
    authHeaderValue: String(input.authHeaderValue || ''),
    extraHeaders: input.extraHeaders && typeof input.extraHeaders === 'object' ? input.extraHeaders : {},
  };
}
function customHeaders(settings) {
  const headers = { accept: '*/*', 'user-agent': settings.userAgent || DEFAULT_UA, ...settings.extraHeaders };
  if (settings.origin) headers.origin = settings.origin;
  if (settings.authHeaderName && settings.authHeaderValue) headers[settings.authHeaderName] = settings.authHeaderValue;
  return headers;
}
async function resolveCustomResult(value, baseUrl, settings, depth = 0) {
  if (depth > 3) throw new GatewayError('The API result response nested too many times.', 502);
  if (value == null) throw new GatewayError(`The API response did not contain the configured result path: ${settings.resultPath}`, 502);
  if (typeof value !== 'string') value = JSON.stringify(value);
  const trimmed = value.trim();
  if (/^data:image\//i.test(trimmed)) {
    const comma = trimmed.indexOf(',');
    const meta = trimmed.slice(0, comma);
    const bytes = Buffer.from(trimmed.slice(comma + 1), /;base64/i.test(meta) ? 'base64' : 'utf8');
    return { bytes, contentType: ensureImage(bytes, 'Custom API result') };
  }
  if (/^https?:\/\//i.test(trimmed) || trimmed.startsWith('/')) {
    const url = new URL(trimmed, baseUrl).toString();
    if (!url.startsWith('https://')) throw new GatewayError('The custom API returned an insecure result URL.', 502);
    const response = await fetchWithTimeout(url, { headers: customHeaders(settings) }, 150000);
    const bytes = Buffer.from(await response.arrayBuffer());
    if (!response.ok) throw new GatewayError(`Custom API result download returned HTTP ${response.status}`, response.status);
    const type = response.headers.get('content-type') || contentTypeForImage(bytes);
    if (type.startsWith('image/') || contentTypeForImage(bytes).startsWith('image/')) return { bytes, contentType: ensureImage(bytes, 'Custom API result') };
    return resolveCustomPayload(bytes.toString('utf8'), type, url, settings, depth + 1);
  }
  const compactBase64 = trimmed.replace(/\s+/g, '');
  if (compactBase64.length > 100 && /^[A-Za-z0-9+/]+=*$/.test(compactBase64)) {
    const bytes = Buffer.from(compactBase64, 'base64');
    if (contentTypeForImage(bytes).startsWith('image/')) return { bytes, contentType: contentTypeForImage(bytes) };
  }
  throw new GatewayError(`The configured result value was not an image, URL, data URL, or image base64: ${jsonCompact(trimmed)}`, 502);
}
async function resolveCustomPayload(text, contentType, baseUrl, settings, depth = 0) {
  if ((contentType || '').startsWith('image/')) {
    const bytes = Buffer.from(text, 'binary');
    return { bytes, contentType };
  }
  let json;
  try { json = JSON.parse(text); } catch { return resolveCustomResult(text, baseUrl, settings, depth); }
  let value = readPath(json, settings.resultPath);
  if ((value == null || String(value).trim() === '') && settings.responseMode === 'auto') {
    for (const fallback of ['result.image', 'image', 'url', 'output_url', 'data.url']) {
      value = readPath(json, fallback);
      if (value != null && String(value).trim() !== '') break;
    }
  }
  return resolveCustomResult(value, baseUrl, settings, depth);
}
async function customSingle(targetBytes, donorBytes, inputSettings = {}) {
  const settings = normalizeCustomSettings(inputSettings);
  if (!settings.endpoint.startsWith('https://')) throw new GatewayError('Custom endpoints must use HTTPS.', 400);
  const donorField = settings.swapMappings ? settings.targetField : settings.sourceField;
  const targetField = settings.swapMappings ? settings.sourceField : settings.targetField;
  const form = new FormData();
  form.set(donorField, new Blob([donorBytes], { type: 'image/jpeg' }), 'donor.jpg');
  form.set(targetField, new Blob([targetBytes], { type: 'image/jpeg' }), 'target.jpg');
  if (settings.enhancerField) form.set(settings.enhancerField, String(settings.enhancerEnabled));
  if (settings.safetyField) form.set(settings.safetyField, 'true');
  for (const [key, value] of Object.entries(settings.extraFormFields)) form.set(key, String(value));
  const response = await fetchWithTimeout(settings.endpoint, { method: 'POST', headers: customHeaders(settings), body: form }, 150000);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (!response.ok) throw new GatewayError(`Custom API returned HTTP ${response.status}: ${jsonCompact(bytes.toString('utf8'))}`, response.status);
  const contentType = response.headers.get('content-type') || '';
  if (contentType.startsWith('image/') || contentTypeForImage(bytes).startsWith('image/')) return { bytes, contentType: ensureImage(bytes, 'Custom API result') };
  const initialText = bytes.toString('utf8');
  if (settings.responseMode === 'polling') {
    const initialJson = parseJson(initialText, 'Custom API response');
    let pollUrl = readPath(initialJson, settings.pollUrlPath);
    if (!pollUrl && settings.pollUrlTemplate) {
      const pollId = readPath(initialJson, settings.pollIdPath);
      if (pollId) pollUrl = settings.pollUrlTemplate.replace('{id}', encodeURIComponent(String(pollId)));
    }
    if (!pollUrl) return resolveCustomResult(readPath(initialJson, settings.resultPath), settings.endpoint, settings);
    pollUrl = new URL(String(pollUrl), settings.endpoint).toString();
    for (let attempt = 0; attempt < settings.maxPolls; attempt += 1) {
      await sleep(settings.pollIntervalSeconds * 1000);
      const pollResponse = await fetchWithTimeout(pollUrl, { headers: customHeaders(settings) }, 150000);
      const pollBytes = Buffer.from(await pollResponse.arrayBuffer());
      if (!pollResponse.ok) throw new GatewayError(`Custom API polling returned HTTP ${pollResponse.status}`, pollResponse.status);
      if ((pollResponse.headers.get('content-type') || '').startsWith('image/') || contentTypeForImage(pollBytes).startsWith('image/')) {
        return { bytes: pollBytes, contentType: ensureImage(pollBytes, 'Custom API poll result') };
      }
      const pollJson = parseJson(pollBytes.toString('utf8'), 'Custom API polling response');
      const status = String(readPath(pollJson, settings.pollStatusPath) || '').toLowerCase();
      if (status === settings.pollSuccessValue.toLowerCase()) return resolveCustomResult(readPath(pollJson, settings.resultPath), pollUrl, settings);
      if (settings.pollFailureValues.includes(status)) throw new GatewayError(`Custom API polling failed with status: ${status}`, 502);
    }
    throw new GatewayError('Custom API polling timed out.', 504);
  }
  return resolveCustomPayload(initialText, contentType, settings.endpoint, settings);
}
async function taoSingle(targetBytes, donorBytes, settings = {}) {
  return customSingle(targetBytes, donorBytes, {
    ...settings,
    endpoint: TAO_DEFAULT.endpoint,
    sourceField: TAO_DEFAULT.sourceField,
    targetField: TAO_DEFAULT.targetField,
    enhancerField: TAO_DEFAULT.enhancerField,
    safetyField: TAO_DEFAULT.safetyField,
    resultPath: TAO_DEFAULT.resultPath,
    origin: TAO_DEFAULT.origin,
    responseMode: 'auto',
  });
}
async function autoSingle(targetBytes, donorBytes, settings) {
  let route;
  try { route = await faceOverRoute(); } catch { return aifaceSingle(targetBytes, donorBytes); }
  if (route === 0) return aifaceSingle(targetBytes, donorBytes);
  if (route === 2) return fjoySingle(targetBytes, donorBytes);
  if (route === 3) {
    try { return await taoSingle(targetBytes, donorBytes, settings); }
    catch (error) {
      if ([429, 500, 502, 503, 504].includes(error.status)) return aifaceSingle(targetBytes, donorBytes);
      throw error;
    }
  }
  return aifaceSingle(targetBytes, donorBytes);
}

async function parseIncomingRequest(req) {
  const host = req.headers.host || `${HOST}:${PORT}`;
  const url = `http://${host}${req.url}`;
  const contentLength = Number(req.headers['content-length'] || 0);
  if (contentLength > MAX_BODY_BYTES) throw new GatewayError(`Request exceeds the ${MAX_BODY_BYTES} byte gateway limit.`, 413);
  return new Request(url, {
    method: req.method,
    headers: req.headers,
    body: ['GET', 'HEAD'].includes(req.method || '') ? undefined : req,
    duplex: 'half',
  });
}
function requestOrigin(req) {
  return String(req.headers.origin || '');
}
function originAllowed(origin) {
  if (!origin) return true;
  if (!ALLOWED_ORIGINS.size) return true;
  return ALLOWED_ORIGINS.has(origin);
}
function corsHeaders(origin) {
  return {
    'access-control-allow-origin': originAllowed(origin) && origin ? origin : 'null',
    vary: 'Origin',
    'access-control-allow-headers': 'Authorization, Content-Type, X-FaceBatch-Token',
    'access-control-allow-methods': 'GET, POST, OPTIONS',
    'access-control-max-age': '86400',
  };
}
function authorized(req) {
  if (!ACCESS_TOKEN) return true;
  const auth = String(req.headers.authorization || '');
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : String(req.headers['x-facebatch-token'] || '');
  return token === ACCESS_TOKEN;
}
function sendJson(res, status, value, origin = '') {
  const body = Buffer.from(JSON.stringify(value, null, 2));
  res.writeHead(status, { ...corsHeaders(origin), 'content-type': 'application/json; charset=utf-8', 'content-length': body.length, 'cache-control': 'no-store' });
  res.end(body);
}
function sendImage(res, result, filename, origin = '') {
  res.writeHead(200, {
    ...corsHeaders(origin),
    'content-type': result.contentType || contentTypeForImage(result.bytes, 'application/octet-stream'),
    'content-length': result.bytes.length,
    'content-disposition': `inline; filename="${String(filename || 'FaceBatch-result').replace(/[^A-Za-z0-9._-]/g, '_')}"`,
    'cache-control': 'no-store',
    'x-facebatch-provider': 'gateway',
  });
  res.end(result.bytes);
}

async function handleApi(req, res) {
  const origin = requestOrigin(req);
  if (!originAllowed(origin)) return sendJson(res, 403, { error: 'Origin is not allowed.' }, origin);
  if (req.method === 'OPTIONS') {
    res.writeHead(204, corsHeaders(origin));
    return res.end();
  }
  if (!authorized(req)) return sendJson(res, 401, { error: 'Invalid gateway token.' }, origin);
  const request = await parseIncomingRequest(req);
  const url = new URL(request.url);

  if (req.method === 'GET' && url.pathname === '/v1/health') {
    return sendJson(res, 200, {
      ok: true,
      service: 'FaceBatch Web Gateway',
      version: '1.0.0',
      mock: MOCK_MODE,
      engines: ['aifaceswap_hq', 'faceover_auto', 'fjoy', 'tao', 'custom'],
      multi: true,
      tokenRequired: Boolean(ACCESS_TOKEN),
    }, origin);
  }

  if (req.method === 'POST' && url.pathname === '/v1/session/start') {
    let body = {};
    try { body = await request.json(); } catch { /* empty is okay */ }
    currentBatchId = String(body.batchId || randomUUID());
    aiSession = null;
    faceOverRouteCache = null;
    resetFjoySession(2000);
    return sendJson(res, 200, { ok: true, batchId: currentBatchId }, origin);
  }
  if (req.method === 'POST' && url.pathname === '/v1/session/end') {
    resetFjoySession(0);
    currentBatchId = '';
    return sendJson(res, 200, { ok: true }, origin);
  }

  if (req.method === 'POST' && url.pathname === '/v1/analyze') {
    const form = await request.formData();
    const target = requireFile(form, 'target');
    if (MOCK_MODE) return sendJson(res, 200, { faces: [[15, 15, 45, 55], [55, 18, 86, 58]], mock: true }, origin);
    const result = await aifaceAnalyze(await fileBuffer(target));
    return sendJson(res, 200, { faces: result.faces }, origin);
  }

  if (req.method === 'POST' && url.pathname === '/v1/single') {
    const form = await request.formData();
    const target = requireFile(form, 'target');
    const donor = requireFile(form, 'donor');
    const engine = String(form.get('engine') || 'aifaceswap_hq');
    let settings = {};
    try { settings = JSON.parse(String(form.get('settings') || '{}')); } catch { throw new GatewayError('The settings field is not valid JSON.', 400); }
    const targetBytes = await fileBuffer(target);
    const donorBytes = await fileBuffer(donor);
    let result;
    if (MOCK_MODE) result = { bytes: targetBytes, contentType: contentTypeForImage(targetBytes, target.type || 'image/jpeg') };
    else if (engine === 'aifaceswap_hq') result = await aifaceSingle(targetBytes, donorBytes);
    else if (engine === 'faceover_auto') result = await autoSingle(targetBytes, donorBytes, settings);
    else if (engine === 'fjoy') result = await fjoySingle(targetBytes, donorBytes);
    else if (engine === 'tao') result = await taoSingle(targetBytes, donorBytes, settings);
    else if (engine === 'custom') result = await customSingle(targetBytes, donorBytes, settings.custom || settings);
    else throw new GatewayError(`Unknown engine: ${engine}`, 400);
    return sendImage(res, result, 'FaceBatch-result.jpg', origin);
  }

  if (req.method === 'POST' && url.pathname === '/v1/multi') {
    const form = await request.formData();
    const target = requireFile(form, 'target');
    let assignments;
    try { assignments = JSON.parse(String(form.get('assignments') || '[]')); } catch { throw new GatewayError('The assignments field is not valid JSON.', 400); }
    if (!Array.isArray(assignments) || !assignments.length) throw new GatewayError('At least one face assignment is required.', 400);
    const donorIds = new Set(assignments.map((entry) => String(entry.donorId)));
    const donorBytesById = new Map();
    for (const donorId of donorIds) donorBytesById.set(donorId, await fileBuffer(requireFile(form, `donor_${donorId}`)));
    const targetBytes = await fileBuffer(target);
    const result = MOCK_MODE
      ? { bytes: targetBytes, contentType: contentTypeForImage(targetBytes, target.type || 'image/jpeg') }
      : await aifaceMulti(targetBytes, donorBytesById, assignments);
    return sendImage(res, result, 'FaceBatch-multi-result.jpg', origin);
  }

  return sendJson(res, 404, { error: 'Not found.' }, origin);
}

const server = http.createServer(async (req, res) => {
  try {
    await handleApi(req, res);
  } catch (error) {
    const origin = requestOrigin(req);
    const status = error instanceof GatewayError ? error.status : 500;
    console.error(new Date().toISOString(), error);
    if (!res.headersSent) sendJson(res, status, { error: error?.message || 'Unexpected gateway error.', details: error?.details }, origin);
    else res.destroy();
  }
});

server.listen(PORT, HOST, () => {
  console.log(`FaceBatch gateway listening on http://${HOST}:${PORT}`);
  console.log(`Mock mode: ${MOCK_MODE ? 'on' : 'off'}`);
  console.log(`Access token: ${ACCESS_TOKEN ? 'required' : 'not configured'}`);
  console.log(`Allowed origins: ${ALLOWED_ORIGINS.size ? [...ALLOWED_ORIGINS].join(', ') : 'any origin'}`);
});
