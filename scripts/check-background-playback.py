#!/usr/bin/env python3
"""ADB regression check against a debuggable mpvRx APK and a local A/V test clip.

Example:
  python3 scripts/check-background-playback.py --serial emulator-5554 \
      --video /tmp/test.mp4 --pause-resize --output build/continuity

The clip must last at least 60 seconds. The check enables video background playback,
exercises Home/Recents and screen off/on, and rejects native seek/restart events after
startup. It saves screenshots for visual inspection and restores the app preferences.
Use --expect-interruptions with an unpatched build to verify the negative control.
Use --inspect-video to check native video teardown and audio progress through temporary
IPC. --video-track exercises non-default track restoration; --url checks a direct stream.
"""

import argparse
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
    parser.add_argument('--video', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/continuity'))
    parser.add_argument('--texture', action='store_true')
    parser.add_argument('--gpu-next', action='store_true')
    parser.add_argument('--hardware-decoding', action='store_true')
    parser.add_argument('--mini-player', action='store_true')
    parser.add_argument('--pause-resize', action='store_true')
    parser.add_argument('--check-paused-image', action='store_true',
                        help='Compare the paused frame before/after resizing (requires Pillow and --pause-resize)')
    parser.add_argument('--rapid-cycles', type=int, default=0)
    parser.add_argument('--inspect-video', action='store_true',
                        help='Use temporary mpv IPC to verify background video suspension and audio progress')
    parser.add_argument('--video-track', type=int, help='Select this video track through test IPC')
    parser.add_argument('--url', help='Play this URL instead of the pushed local clip')
    parser.add_argument('--expect-interruptions', action='store_true')
    args = parser.parse_args()
    if not args.video.is_file():
        parser.error('The test clip does not exist')
    if args.video_track is not None and not args.inspect_video:
        parser.error('--video-track requires --inspect-video')
    if args.check_paused_image and not args.pause_resize:
        parser.error('--check-paused-image requires --pause-resize')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    command = ['adb'] + (['-s', args.serial] if args.serial else [])
    package = 'app.gyrolet.mpvrx.debug'
    activity = package + '/app.gyrolet.mpvrx.ui.player.PlayerActivity'
    preferences = f'shared_prefs/{package}_preferences.xml'
    remote_video = f'/sdcard/Download/mpvrx-continuity-{uuid.uuid4().hex}.mp4'
    ipc_name = 'mpvrx-continuity-' + uuid.uuid4().hex
    ipc_port = None
    snapshots = {}
    paused_image_error = None

    def adb(*parts, binary=False):
        return subprocess.check_output(command + list(parts), text=not binary, timeout=30)

    def mark(label):
        adb('shell', 'log', '-t', 'CONTINUITY', label)
        print(label, flush=True)

    def screenshot(label):
        (output / f'{label}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))

    def inspect(label, background=False):
        if ipc_port is None:
            return
        values = {}
        with socket.create_connection(('127.0.0.1', ipc_port), timeout=5) as connection:
            connection.settimeout(5)
            with connection.makefile('rwb') as stream:
                for request_id, name in enumerate(('vid', 'current-vo', 'video-codec', 'time-pos',
                                                    'audio-pts', 'pause', 'audio-codec', 'hwdec-current')):
                    request = {'command': ['get_property', name], 'request_id': request_id}
                    stream.write(json.dumps(request).encode() + b'\n')
                    stream.flush()
                    while True:
                        response = json.loads(stream.readline())
                        if response.get('request_id') == request_id:
                            values[name] = response.get('data')
                            break
        snapshots[label] = values
        (output / 'native-state.json').write_text(json.dumps(snapshots, indent=2))
        if background:
            if values['vid'] not in ('no', -2, False) or values['current-vo'] not in (None, 'null'):
                raise RuntimeError(f'Background video is still enabled: {values}')
            if values['video-codec'] is not None:
                raise RuntimeError(f'Background video decoder is still present: {values}')
            if values['pause'] is not False or not values['audio-codec']:
                raise RuntimeError(f'Background audio is not running: {values}')
        elif not isinstance(values['vid'], int) or values['vid'] <= 0 or not values['video-codec']:
            raise RuntimeError(f'Foreground video did not recover: {values}')
        elif args.video_track is not None and values['vid'] != args.video_track:
            raise RuntimeError(f'The selected video track changed: {values}')

    def return_from_recents():
        adb('shell', 'input', 'keyevent', '187')
        time.sleep(1)
        dimensions = re.search(r'Physical size: (\d+)x(\d+)', adb('shell', 'wm', 'size'))
        if dimensions is None:
            raise RuntimeError('Cannot determine the display size')
        w, h = map(int, dimensions.groups())
        adb('shell', 'input', 'tap', str(w // 2), str(h // 2))
        time.sleep(2)
        resumed = adb('shell', 'dumpsys', 'activity', 'activities')
        if not any('PlayerActivity' in line and ('ResumedActivity' in line or 'mResumedActivity' in line)
                   for line in resumed.splitlines()):
            raise RuntimeError('Recents did not return to the video player; inspect the screenshots')

    adb('shell', 'am', 'force-stop', package)
    adb('shell', 'run-as', package, 'mkdir', '-p', 'shared_prefs')
    try:
        original = adb('shell', 'run-as', package, 'cat', preferences, binary=True)
        root = ET.fromstring(original)
    except subprocess.CalledProcessError:
        original = None
        root = ET.Element('map')
    settings = {
        'automatic_background_playback': True,
        'enable_video_mini_player': args.mini_player,
        'auto_pip_on_navigation': False,
        'verbose_logging': True,
        'try_hw_dec': args.hardware_decoding,
        'use_vulkan': False,
        'gpu_next': args.gpu_next,
        'liquid_glass_enabled': args.texture,
    }
    for key, value in settings.items():
        for node in list(root):
            if node.get('name') == key:
                root.remove(node)
        ET.SubElement(root, 'boolean', name=key, value=str(value).lower())
    if args.inspect_video:
        config = root.find("string[@name='mpv.conf']")
        if config is None:
            config = ET.SubElement(root, 'string', name='mpv.conf')
        config.text = (config.text or '') + f'\ninput-ipc-server=@{ipc_name}\n'
    modified = output / 'test-preferences.xml'
    modified.write_bytes(ET.tostring(root, encoding='utf-8', xml_declaration=True))
    sdk = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    logcat = None
    resized = False
    try:
        if sdk <= 32:
            adb('shell', 'pm', 'grant', package, 'android.permission.READ_EXTERNAL_STORAGE')
        if sdk >= 33:
            adb('shell', 'pm', 'grant', package, 'android.permission.POST_NOTIFICATIONS')
        if sdk >= 30:
            adb('shell', 'appops', 'set', package, 'MANAGE_EXTERNAL_STORAGE', 'allow')
        adb('push', str(modified), '/data/local/tmp/mpvrx-continuity-preferences.xml')
        adb('shell', 'run-as', package, 'cp', '/data/local/tmp/mpvrx-continuity-preferences.xml', preferences)
        adb('push', str(args.video.resolve()), remote_video)
        with (output / 'logcat.txt').open('w') as log:
            logcat = subprocess.Popen(command + ['logcat', '-T', '1', '-v', 'threadtime',
                '-s', 'mpv:V', 'PlaybackSession:D',
                'CONTINUITY:I', 'AndroidRuntime:E', 'BLASTBufferQueue:E'], stdout=log)
            adb('shell', 'am', 'start', '-W', '-n', activity, '-a', 'android.intent.action.VIEW',
                '-d', args.url or 'file://' + remote_video, '-t', 'video/mp4')
            # Wait for a real initial playback restart, rather than treating failure to open
            # the decoder or native library as an apparently interruption-free result.
            deadline = time.monotonic() + 20
            while 'event: playback-restart' not in (output / 'logcat.txt').read_text():
                if time.monotonic() > deadline:
                    raise RuntimeError('Playback did not start; inspect logcat.txt')
                time.sleep(0.2)
            time.sleep(2)
            if args.inspect_video:
                ipc_port = int(adb('forward', 'tcp:0', 'localabstract:' + ipc_name).strip())
            if args.video_track is not None:
                with socket.create_connection(('127.0.0.1', ipc_port), timeout=5) as connection:
                    connection.settimeout(5)
                    with connection.makefile('rwb') as stream:
                        stream.write(json.dumps({'command': ['set_property', 'vid', args.video_track],
                                                 'request_id': 1}).encode() + b'\n')
                        stream.flush()
                        while True:
                            response = json.loads(stream.readline())
                            if response.get('request_id') == 1:
                                if response.get('error') != 'success':
                                    raise RuntimeError(f'Cannot select the requested track: {response}')
                                break
                time.sleep(0.3)
            inspect('initial')
            screenshot('initial')
            mark('HOME_BEGIN')
            adb('shell', 'input', 'keyevent', '3')
            time.sleep(3)
            inspect('background', background=True)
            if args.inspect_video:
                progress = snapshots['background']['audio-pts'] - snapshots['initial']['audio-pts']
                if progress < 1:
                    raise RuntimeError(f'Audio did not advance in the background: {progress}')
            mark('RETURN_BEGIN')
            return_from_recents()
            inspect('returned')
            screenshot('returned')
            mark('SCREEN_OFF_BEGIN')
            adb('shell', 'input', 'keyevent', '223')
            time.sleep(3)
            inspect('screen-off', background=True)
            mark('SCREEN_ON_BEGIN')
            adb('shell', 'input', 'keyevent', '224')
            adb('shell', 'wm', 'dismiss-keyguard')
            time.sleep(3)
            inspect('unlocked')
            screenshot('unlocked')
            for cycle in range(args.rapid_cycles):
                mark(f'RAPID_HOME_{cycle}')
                adb('shell', 'input', 'keyevent', '3')
                time.sleep(0.15)
                return_from_recents()
                inspect(f'rapid-return-{cycle}')
            if args.pause_resize:
                mark('PAUSE_BEGIN')
                adb('shell', 'input', 'keyevent', '127')
                time.sleep(1)
                screenshot('paused')
                mark('PAUSED_HOME_BEGIN')
                adb('shell', 'input', 'keyevent', '3')
                time.sleep(2)
                return_from_recents()
                inspect('paused-returned')
                screenshot('paused-returned')
                mark('PAUSED_RESIZE_BEGIN')
                adb('shell', 'wm', 'size', '540x960')
                resized = True
                time.sleep(2)
                screenshot('paused-resized')
                adb('shell', 'wm', 'size', 'reset')
                resized = False
                time.sleep(2)
                screenshot('paused-restored')
                if args.check_paused_image:
                    from PIL import Image, ImageChops, ImageStat
                    with Image.open(output / 'paused-returned.png') as before, \
                            Image.open(output / 'paused-restored.png') as after:
                        if before.size != after.size:
                            raise RuntimeError('The display size did not recover after the resize')
                        difference = ImageChops.difference(before.convert('RGB'), after.convert('RGB'))
                        paused_image_error = sum(ImageStat.Stat(difference).mean) / (3 * 255)
                    if paused_image_error > 0.04:
                        raise RuntimeError(f'The paused frame was cropped/scaled after resize: {paused_image_error:.3f}')
                mark('RESUME_BEGIN')
                adb('shell', 'input', 'keyevent', '126')
                time.sleep(2)
            (output / 'media-session.txt').write_text(adb('shell', 'dumpsys', 'media_session'))
            mark('COMPLETE')
            time.sleep(0.2)
    finally:
        if resized:
            adb('shell', 'wm', 'size', 'reset')
        if logcat is not None:
            logcat.terminate()
            try:
                logcat.wait(timeout=5)
            except subprocess.TimeoutExpired:
                logcat.kill()
                logcat.wait()
        adb('shell', 'am', 'force-stop', package)
        if ipc_port is not None:
            adb('forward', '--remove', f'tcp:{ipc_port}')
        if original is None:
            adb('shell', 'run-as', package, 'rm', '-f', preferences)
        else:
            restored = output / 'original-preferences.xml'
            restored.write_bytes(original)
            adb('push', str(restored), '/data/local/tmp/mpvrx-continuity-preferences.xml')
            adb('shell', 'run-as', package, 'cp', '/data/local/tmp/mpvrx-continuity-preferences.xml', preferences)
        adb('shell', 'rm', '-f', remote_video, '/data/local/tmp/mpvrx-continuity-preferences.xml')

    text = (output / 'logcat.txt').read_text()
    for marker in ('HOME_BEGIN', 'RETURN_BEGIN', 'SCREEN_OFF_BEGIN', 'SCREEN_ON_BEGIN', 'COMPLETE'):
        if marker not in text:
            raise RuntimeError(f'Missing log marker {marker}; the playback check was incomplete')
    phase = 'STARTUP'
    events = {}
    for line in text.splitlines():
        if 'CONTINUITY' in line:
            phase = line.split('CONTINUITY', 1)[1].split(':', 1)[-1].strip()
        if 'event: seek' in line or 'event: playback-restart' in line:
            events.setdefault(phase, []).append(line)
    after_startup = [e for key, group in events.items() if key != 'STARTUP' for e in group]
    summary = {'android_api': sdk, 'settings': settings, 'events': events, 'native_state': snapshots,
               'paused_image_mean_error': paused_image_error,
               'interruptions_after_startup': len(after_startup)}
    (output / 'result.json').write_text(json.dumps(summary, indent=2))
    if 'FATAL EXCEPTION' in text or 'Playback window rejected a video buffer' in text:
        raise RuntimeError('A native window/consumer failed; inspect logcat.txt')
    playing_window = text.split('HOME_BEGIN', 1)[1].split('PAUSE_BEGIN', 1)[0]
    if 'Set property: pause=true' in playing_window and not args.expect_interruptions:
        raise RuntimeError('Playback was paused during a foreground/background handoff')
    if bool(after_startup) != args.expect_interruptions:
        raise RuntimeError(f'Unexpected playback events after startup: {len(after_startup)}')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
