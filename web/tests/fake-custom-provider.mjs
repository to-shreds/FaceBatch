#!/usr/bin/env node
import fs from 'node:fs';
import https from 'node:https';

const host = '127.0.0.1';
const port = Number.parseInt(process.env.FAKE_PROVIDER_PORT || '9443', 10);
const key = fs.readFileSync(process.env.FAKE_PROVIDER_KEY);
const cert = fs.readFileSync(process.env.FAKE_PROVIDER_CERT);
const image = fs.readFileSync(process.env.FAKE_PROVIDER_IMAGE);
const base = `https://${host}:${port}`;

function read(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => resolve(Buffer.concat(chunks)));
  });
}

function json(res, status, value) {
  const body = Buffer.from(JSON.stringify(value));
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': body.length });
  res.end(body);
}

const server = https.createServer({ key, cert }, async (req, res) => {
  const body = await read(req);
  const path = new URL(req.url, base).pathname;

  if (path === '/image') {
    res.writeHead(200, { 'content-type': 'image/jpeg', 'content-length': image.length });
    res.end(image);
    return;
  }

  if (path === '/validate') {
    const text = body.toString('latin1');
    const checks = {
      userAgent: req.headers['user-agent'] === 'FaceBatch-QA-UA',
      origin: req.headers.origin === 'https://facebatch.test',
      authorization: req.headers.authorization === 'Bearer qa',
      extraHeader: req.headers['x-extra'] === 'yes',
      donorField: text.includes('name="face"'),
      targetField: text.includes('name="image"'),
      enhancer: text.includes('name="enhancer"') && text.includes('true'),
      safety: text.includes('name="check-nsfw"') && text.includes('true'),
      extraField: text.includes('name="swap_all"') && text.includes('true'),
    };
    if (Object.values(checks).some((value) => !value)) {
      json(res, 400, { error: 'validation failed', checks });
      return;
    }
    res.writeHead(200, { 'content-type': 'image/jpeg', 'content-length': image.length });
    res.end(image);
    return;
  }

  if (path === '/json') {
    json(res, 200, { result: { image: `${base}/image` } });
    return;
  }
  if (path === '/fallback') {
    json(res, 200, { output_url: `${base}/image` });
    return;
  }
  if (path === '/plain') {
    const output = Buffer.from(`${base}/image`);
    res.writeHead(200, { 'content-type': 'text/plain', 'content-length': output.length });
    res.end(output);
    return;
  }
  if (path === '/poll-start') {
    json(res, 200, { id: 'abc' });
    return;
  }
  if (path === '/poll') {
    json(res, 200, { status: 'success', output_url: `${base}/image` });
    return;
  }

  res.writeHead(404);
  res.end('not found');
});

server.listen(port, host, () => console.log(`Fake HTTPS provider listening on ${base}`));
