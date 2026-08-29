#!/usr/bin/env python3
from __future__ import annotations

import contextlib
import os
import shutil
import socket
import subprocess
import tempfile
import time
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw
from playwright.sync_api import expect, sync_playwright

ROOT = Path(__file__).resolve().parents[2]


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return int(sock.getsockname()[1])


def wait_socket(port: int) -> None:
    for _ in range(120):
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=.1):
                return
        except OSError:
            time.sleep(.05)
    raise RuntimeError(f'Port {port} did not open')


def make_image(path: Path, size: tuple[int, int], offset: int = 0) -> None:
    image = Image.new('RGB', size, (235 - offset, 241, 248))
    draw = ImageDraw.Draw(image)
    x, y = size[0] // 2, size[1] // 2
    draw.ellipse((x - 25, y - 31, x + 25, y + 31), fill=(238, 184 + offset, 150), outline=(64, 60, 58), width=2)
    draw.ellipse((x - 11, y - 8, x - 6, y - 3), fill=(20, 20, 20))
    draw.ellipse((x + 6, y - 8, x + 11, y - 3), fill=(20, 20, 20))
    draw.arc((x - 12, y + 2, x + 12, y + 18), start=5, end=175, fill=(40, 30, 30), width=2)
    image.save(path, 'JPEG', quality=92)


def main() -> None:
    static_port = free_port()
    gateway_port = free_port()
    origin = f'http://127.0.0.1:{static_port}'
    token = 'browser-smoke-token'

    with tempfile.TemporaryDirectory(prefix='facebatch-browser-') as temp_name:
        temp = Path(temp_name)
        donor1 = temp / 'donor1.jpg'
        donor2 = temp / 'donor2.jpg'
        target1 = temp / 'target1.jpg'
        target2 = temp / 'target2.jpg'
        make_image(donor1, (80, 80), 0)
        make_image(donor2, (80, 80), 15)
        make_image(target1, (180, 110), 0)
        make_image(target2, (190, 120), 10)

        static = subprocess.Popen(
            ['python', '-m', 'http.server', str(static_port), '--bind', '127.0.0.1', '--directory', str(ROOT / 'web')],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        env = os.environ.copy()
        env.update({
            'HOST': '127.0.0.1',
            'PORT': str(gateway_port),
            'FACEBATCH_MOCK': '1',
            'FACEBATCH_GATEWAY_TOKEN': token,
            'FACEBATCH_ALLOWED_ORIGINS': origin,
        })
        gateway = subprocess.Popen(
            ['node', str(ROOT / 'web' / 'gateway' / 'server.mjs')],
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

        try:
            wait_socket(static_port)
            wait_socket(gateway_port)
            with sync_playwright() as pw:
                executable = os.environ.get('FACEBATCH_CHROMIUM_EXECUTABLE') or shutil.which('chromium') or shutil.which('google-chrome')
                launch_args: dict[str, object] = {'headless': True}
                if executable:
                    launch_args['executable_path'] = executable
                    launch_args['args'] = ['--no-sandbox', '--disable-dev-shm-usage']
                browser = pw.chromium.launch(**launch_args)
                context = browser.new_context(accept_downloads=True, viewport={'width': 1180, 'height': 900})
                page = context.new_page()
                page_errors: list[str] = []
                console_errors: list[str] = []
                page.on('pageerror', lambda exc: page_errors.append(str(exc)))
                page.on('console', lambda msg: console_errors.append(msg.text) if msg.type == 'error' else None)
                page.goto(origin, wait_until='networkidle')
                expect(page).to_have_title('FaceBatch Web')

                page.locator('[data-view="settings"]').click()
                page.locator('#settingGatewayUrl').fill(f'http://127.0.0.1:{gateway_port}')
                page.locator('#settingGatewayToken').fill(token)
                page.locator('#saveSettings').click()
                page.locator('#testGateway').click()
                expect(page.locator('#gatewayBadgeText')).to_contain_text('Gateway ready', timeout=10_000)

                page.locator('[data-view="single"]').click()
                page.locator('#inputSingleDonors').set_input_files([str(donor1), str(donor2)])
                page.locator('#inputSingleTargets').set_input_files([str(target1)])
                expect(page.locator('#singleEquation')).to_have_text('2 × 1 = 2')
                page.locator('#startSingle').click()
                expect(page.locator('#singleProgressTitle')).to_have_text('Batch complete', timeout=20_000)
                expect(page.locator('#resultCountTab')).to_contain_text('2')

                page.locator('[data-view="multi"]').click()
                page.locator('#inputMultiDonors').set_input_files([str(donor1), str(donor2)])
                page.locator('#inputMultiTargets').set_input_files([str(target1), str(target2)])
                expect(page.locator('.target-row')).to_have_count(2)
                page.locator('#analyzeAllRows').click()
                expect(page.locator('.face-card')).to_have_count(4, timeout=20_000)

                rows = page.locator('.target-row')
                rows.nth(0).click()
                page.locator('.donor-card').nth(0).click()
                expect(rows.nth(0).locator('.prompt')).to_contain_text('Face B')
                page.locator('.donor-card').nth(1).click()
                expect(rows.nth(1)).to_have_class('target-row active')

                # Editing a completed row must not jump away from that row.
                rows.nth(0).locator('.face-card').nth(0).click()
                page.locator('.donor-card').nth(1).click()
                expect(rows.nth(0)).to_have_class('target-row active')

                # Shortcut completion follows the same next-row behavior.
                rows.nth(0).get_by_role('button', name='Auto assign').click()
                expect(rows.nth(1)).to_have_class('target-row active')
                rows.nth(1).get_by_role('button', name='Same donor').click()
                expect(page.locator('#multiEquation')).to_have_text('2 ready rows')
                page.locator('#startMulti').click()
                expect(page.locator('#multiProgressTitle')).to_have_text('Multi-face batch complete', timeout=25_000)
                expect(page.locator('#resultCountTab')).to_contain_text('4')

                page.locator('[data-view="results"]').click()
                expect(page.locator('.result-card')).to_have_count(4)
                with page.expect_download(timeout=15_000) as download_info:
                    page.locator('#downloadAllResults').click()
                zip_path = Path(download_info.value.path())
                with zipfile.ZipFile(zip_path) as archive:
                    assert archive.testzip() is None
                    assert len([name for name in archive.namelist() if name.lower().endswith('.jpg')]) == 4

                page.reload(wait_until='networkidle')
                expect(page.locator('#singleDonorCount')).to_have_text('2', timeout=15_000)
                expect(page.locator('#singleTargetCount')).to_have_text('1')
                expect(page.locator('#multiDonorCount')).to_have_text('2')
                expect(page.locator('#multiRowCount')).to_contain_text('2 / 100')

                page.set_viewport_size({'width': 390, 'height': 844})
                page.locator('[data-view="multi"]').click()
                page.wait_for_timeout(200)
                assert page.evaluate('document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1')

                if page_errors:
                    raise AssertionError('Page errors: ' + '; '.join(page_errors))
                if console_errors:
                    raise AssertionError('Console errors: ' + '; '.join(console_errors))
                browser.close()
        finally:
            for process in (gateway, static):
                process.terminate()
            for process in (gateway, static):
                with contextlib.suppress(subprocess.TimeoutExpired):
                    process.wait(timeout=5)
                if process.poll() is None:
                    process.kill()

    print('BROWSER SMOKE PASSED')


if __name__ == '__main__':
    main()
