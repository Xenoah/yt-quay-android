"""Run with: python -m unittest discover -s tests/python -v

Imports the exact bundled yt-dlp zip, not a pip-installed release. Integration
coverage uses host ffmpeg/ffprobe and locally generated media only; it does not
claim Android native-library or live YouTube verification.
"""

import functools
import hashlib
import http.server
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = ROOT / 'app/src/main/res/raw/ytdlp'
PLUGIN = ROOT / 'app/src/main/res/raw/quay_audio.py'
# AAPT treats everything under res/raw as an Android resource. Do not leave
# Python import caches there when developers run these tests before a build.
sys.dont_write_bytecode = True
sys.path.insert(0, str(BUNDLE))
from yt_dlp.utils import PostProcessingError  # noqa: E402
from yt_dlp.version import __version__  # noqa: E402

spec = importlib.util.spec_from_file_location('quay_audio', PLUGIN)
quay_audio = importlib.util.module_from_spec(spec)
spec.loader.exec_module(quay_audio)
QuayAudioPP = quay_audio.QuayAudioPP


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def audio_metadata(codec='mp3', channels=2, video=False, attached=False):
    streams = [{
        'codec_type': 'audio', 'codec_name': codec, 'channels': channels,
        'sample_rate': '48000', 'bit_rate': '192000', 'nb_read_packets': '10',
    }]
    if video or attached:
        streams.insert(0, {'codec_type': 'video', 'codec_name': 'mjpeg',
                           'disposition': {'attached_pic': int(attached)}})
    return {'streams': streams, 'format': {'duration': '2'}}


class ArgumentsTest(unittest.TestCase):
    def test_exact_bundled_version(self):
        self.assertEqual(__version__, '2026.08.19')

    def test_strict_formats(self):
        for value in ('MP3', ' mp3', 'mp3 ', 'aac', '', None, 1, ['mp3']):
            with self.subTest(value=value), self.assertRaises(PostProcessingError):
                QuayAudioPP(format=value)

    def test_strict_bitrates(self):
        for value in ('192k', ' 192', '192 ', '192.0', '0192', '+192',
                      '1e2', '0', '-1', '999', '', 192, 192.0, True, ['192']):
            with self.subTest(value=value), self.assertRaises(PostProcessingError):
                QuayAudioPP(format='mp3', bitrate=value)
        for format, bitrate in [('mp3', '48'), ('m4a', '48'), ('m4a', '320'), ('opus', '320')]:
            with self.subTest(format=format, bitrate=bitrate), self.assertRaises(PostProcessingError):
                QuayAudioPP(format=format, bitrate=bitrate)

    def test_original_rejects_any_bitrate(self):
        for value in ('192', '', 0, False):
            with self.subTest(value=value), self.assertRaises(PostProcessingError):
                QuayAudioPP(format='original', bitrate=value)
        self.assertIsNone(QuayAudioPP()._bitrate)

    def test_every_allowed_manual_bitrate_and_defaults(self):
        allowed = {
            'mp3': [64, 96, 128, 160, 192, 256, 320],
            'm4a': [64, 96, 128, 160, 192, 256],
            'opus': [48, 64, 96, 128, 160, 192, 256],
        }
        for format, values in allowed.items():
            for value in values:
                with self.subTest(format=format, value=value):
                    self.assertEqual(QuayAudioPP(format=format, bitrate=str(value))._bitrate, str(value))
        for format, value in [('mp3', '192'), ('m4a', '192'), ('opus', '128')]:
            self.assertEqual(QuayAudioPP(format=format)._bitrate, value)

    def test_manual_always_selects_encoder_and_target(self):
        for format, codec in [('mp3', 'libmp3lame'), ('m4a', 'aac'), ('opus', 'libopus')]:
            with self.subTest(format=format):
                opts = QuayAudioPP(format=format, bitrate='192')._output_options({'channels': 2})
                self.assertEqual(opts[:6], ['-map', '0:a:0', '-vn', '-sn', '-dn', '-c:a'])
                self.assertEqual(opts[opts.index('-c:a') + 1], codec)
                self.assertEqual(opts[opts.index('-b:a') + 1], '192k')
                self.assertEqual(opts[opts.index('-ar') + 1], '48000')
                self.assertNotIn('copy', opts)
                self.assertNotIn('-q:a', opts)
                self.assertNotIn('-ac', opts)

    def test_only_multichannel_mp3_downmixes(self):
        for channels in (1, 2, 6):
            for format in ('mp3', 'm4a', 'opus'):
                opts = QuayAudioPP(format=format)._output_options({'channels': channels})
                if format == 'mp3' and channels > 2:
                    self.assertEqual(opts[opts.index('-ac') + 1], '2')
                else:
                    self.assertNotIn('-ac', opts)

    def test_original_container_mapping_has_no_lossy_fallback(self):
        for codec, extension in [('aac', 'm4a'), ('alac', 'm4a'), ('mp3', 'mp3'),
                                 ('opus', 'opus'), ('vorbis', 'ogg'), ('flac', 'flac'),
                                 ('pcm_s16le', 'wav'), ('pcm_f32le', 'wav'), ('ac3', 'mka')]:
            self.assertEqual(QuayAudioPP._copy_extension(codec), extension)
        self.assertEqual(QuayAudioPP()._output_options({'channels': 6}),
                         ['-map', '0:a:0', '-vn', '-sn', '-dn', '-c:a', 'copy'])
        self.assertEqual(QuayAudioPP()._output_options({'codec_name': 'alac'})[-2:],
                         ['-use_editlist', '0'])


class PreservationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.source = self.directory / 'source.mp3'
        self.source.write_bytes(b'original-source-bytes')
        self.original_hash = digest(self.source)
        self.info = {'filepath': str(self.source), 'ext': 'mp3', 'vcodec': 'video', 'abr': 999}

    def assert_original_intact(self):
        self.assertEqual(digest(self.source), self.original_hash)
        self.assertEqual(list(self.directory.iterdir()), [self.source])
        self.assertEqual(self.info['vcodec'], 'video')
        self.assertEqual(self.info['abr'], 999)

    @staticmethod
    def fake_encode(source, output, options):
        Path(output).write_bytes(b'new-encoded-audio')

    def test_original_audio_noop_and_attached_picture_are_byte_identical(self):
        for attached in (False, True):
            with self.subTest(attached=attached):
                pp = QuayAudioPP()
                with mock.patch.object(pp, '_probe', return_value=audio_metadata(attached=attached)), \
                        mock.patch.object(pp, 'run_ffmpeg') as encode:
                    deleted, info = pp.run(dict(self.info))
                self.assertEqual(deleted, [])
                self.assertEqual(info['filepath'], str(self.source))
                self.assertEqual(info['vcodec'], 'none')
                encode.assert_not_called()
                self.assertEqual(digest(self.source), self.original_hash)

    def test_no_audio_is_a_clear_error_and_preserves_source(self):
        pp = QuayAudioPP()
        with mock.patch.object(pp, '_probe', return_value={'streams': [{'codec_type': 'video'}]}), \
                self.assertRaisesRegex(PostProcessingError, 'no audio'):
            pp.run(self.info)
        self.assert_original_intact()

    def test_probe_errors_preserve_source(self):
        pp = QuayAudioPP(format='mp3')
        for error in (OSError('ffprobe missing'), ValueError('bad JSON'), PostProcessingError('probe failed')):
            with mock.patch.object(pp, '_probe', side_effect=error), self.assertRaises(PostProcessingError):
                pp.run(self.info)
            self.assert_original_intact()

    def test_encoding_error_cleans_partial_output_and_preserves_source(self):
        pp = QuayAudioPP(format='mp3')
        def fail(source, output, options):
            Path(output).write_bytes(b'partial output')
            raise PostProcessingError('encoder failed')
        with mock.patch.object(pp, '_probe', return_value=audio_metadata()), \
                mock.patch.object(pp, 'run_ffmpeg', side_effect=fail), self.assertRaises(PostProcessingError):
            pp.run(self.info)
        self.assert_original_intact()

    def test_failed_original_remux_never_falls_back_to_lossy_encoding(self):
        pp = QuayAudioPP()
        with mock.patch.object(pp, '_probe', return_value=audio_metadata('ac3', video=True)), \
                mock.patch.object(pp, 'run_ffmpeg', side_effect=PostProcessingError('cannot mux')) as encode, \
                self.assertRaises(PostProcessingError):
            pp.run(self.info)
        self.assertEqual(encode.call_count, 1)
        self.assertEqual(encode.call_args.args[2][-2:], ['-c:a', 'copy'])
        self.assert_original_intact()

    def test_invalid_output_preserves_source(self):
        for output in ({'streams': []}, audio_metadata('aac'), audio_metadata(video=True),
                       {'streams': [{'codec_type': 'audio', 'codec_name': 'mp3', 'nb_read_packets': '0'}]}):
            with self.subTest(output=output):
                pp = QuayAudioPP(format='mp3')
                with mock.patch.object(pp, '_probe', side_effect=[audio_metadata(), output]), \
                        mock.patch.object(pp, 'run_ffmpeg', side_effect=self.fake_encode), \
                        self.assertRaisesRegex(PostProcessingError, 'validation'):
                    pp.run(self.info)
                self.assert_original_intact()

    def test_install_error_preserves_same_extension_source(self):
        pp = QuayAudioPP(format='mp3')
        with mock.patch.object(pp, '_probe', return_value=audio_metadata()), \
                mock.patch.object(pp, 'run_ffmpeg', side_effect=self.fake_encode), \
                mock.patch.object(quay_audio.os, 'replace', side_effect=OSError('disk failure')), \
                self.assertRaises(PostProcessingError):
            pp.run(self.info)
        self.assert_original_intact()

    def test_backup_copy_error_preserves_source(self):
        pp = QuayAudioPP(format='mp3')
        with mock.patch.object(pp, '_probe', return_value=audio_metadata()), \
                mock.patch.object(pp, 'run_ffmpeg', side_effect=self.fake_encode), \
                mock.patch.object(quay_audio.shutil, 'copy2', side_effect=OSError('disk full')), \
                self.assertRaises(PostProcessingError):
            pp.run(self.info)
        self.assert_original_intact()

    def test_same_extension_returns_only_backup_for_deletion(self):
        pp = QuayAudioPP(format='mp3', bitrate='320')
        with mock.patch.object(pp, '_probe', return_value=audio_metadata()), \
                mock.patch.object(pp, 'run_ffmpeg', side_effect=self.fake_encode) as encode:
            deleted, info = pp.run(self.info)
        self.assertEqual(info['filepath'], str(self.source))
        self.assertEqual(self.source.read_bytes(), b'new-encoded-audio')
        self.assertEqual(len(deleted), 1)
        self.assertNotEqual(deleted[0], info['filepath'])
        self.assertEqual(digest(deleted[0]), self.original_hash)
        self.assertIn('320k', encode.call_args.args[2])
        os.unlink(deleted[0])  # yt-dlp's normal cleanup must not remove final output
        self.assertTrue(self.source.exists())
        self.assertEqual(list(self.directory.iterdir()), [self.source])

    def test_changed_extension_returns_source_only_after_valid_output(self):
        pp = QuayAudioPP(format='m4a')
        with mock.patch.object(pp, '_probe', side_effect=[audio_metadata(), audio_metadata('aac')]), \
                mock.patch.object(pp, 'run_ffmpeg', side_effect=self.fake_encode):
            deleted, info = pp.run(self.info)
        self.assertEqual(deleted, [str(self.source)])
        self.assertEqual(digest(self.source), self.original_hash)
        self.assertEqual(info['filepath'], str(self.directory / 'source.m4a'))
        self.assertEqual(info['acodec'], 'aac')
        self.assertEqual(info['vcodec'], 'none')


FFMPEG = shutil.which('ffmpeg')
FFPROBE = shutil.which('ffprobe')


@unittest.skipUnless(FFMPEG and FFPROBE, 'host ffmpeg and ffprobe are required')
class FFmpegIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.directory = Path(cls.temp.name)
        cls.noise = cls.directory / 'noise.wav'
        cls.ffmpeg('-f', 'lavfi', '-i', 'anoisesrc=duration=4:sample_rate=48000:seed=12345',
                   '-ac', '2', '-c:a', 'pcm_s16le', str(cls.noise))

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    @staticmethod
    def ffmpeg(*args):
        result = subprocess.run([FFMPEG, '-hide_banner', '-loglevel', 'error', '-y', *args],
                                capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise AssertionError(result.stderr)

    @staticmethod
    def probe(path):
        result = subprocess.run([FFPROBE, '-v', 'error', '-show_streams', '-show_format',
                                 '-of', 'json', str(path)], capture_output=True, text=True, check=True, timeout=30)
        return json.loads(result.stdout)

    @staticmethod
    def packet_hashes(path):
        result = subprocess.run([FFPROBE, '-v', 'error', '-select_streams', 'a:0', '-show_packets',
                                 '-show_entries', 'packet=data_hash', '-show_data_hash', 'sha256',
                                 '-of', 'json', str(path)], capture_output=True, text=True, check=True, timeout=30)
        return [packet['data_hash'] for packet in json.loads(result.stdout)['packets']]

    def fresh_directory(self):
        temporary = tempfile.TemporaryDirectory(dir=self.directory)
        self.addCleanup(temporary.cleanup)
        return Path(temporary.name)

    def test_audio_only_original_is_byte_identical_for_all_manual_formats(self):
        directory = self.fresh_directory()
        for extension, encoder in [('mp3', 'libmp3lame'), ('m4a', 'aac'), ('opus', 'libopus')]:
            source = directory / f'original.{extension}'
            self.ffmpeg('-i', str(self.noise), '-c:a', encoder, '-b:a', '128k', str(source))
            before = digest(source)
            deleted, info = QuayAudioPP().run({'filepath': str(source), 'ext': extension})
            self.assertEqual(deleted, [])
            self.assertEqual(digest(info['filepath']), before)

    def test_embedded_cover_original_is_byte_identical(self):
        directory = self.fresh_directory()
        source = directory / 'cover.mp3'
        self.ffmpeg('-i', str(self.noise), '-f', 'lavfi', '-i', 'color=c=red:s=32x32:d=1',
                    '-map', '0:a', '-map', '1:v', '-c:a', 'libmp3lame', '-c:v', 'mjpeg',
                    '-frames:v', '1', '-disposition:v', 'attached_pic', str(source))
        self.assertTrue(QuayAudioPP._audio_streams(self.probe(source)))
        self.assertFalse(QuayAudioPP._has_video(self.probe(source)))
        before = digest(source)
        deleted, info = QuayAudioPP().run({'filepath': str(source), 'ext': 'mp3'})
        self.assertEqual(deleted, [])
        self.assertEqual(digest(info['filepath']), before)

    def test_same_codec_low_high_manual_targets_really_reencode(self):
        directory = self.fresh_directory()
        for extension, encoder, codec, low, high in [
            ('mp3', 'libmp3lame', 'mp3', '64', '320'),
            ('m4a', 'aac', 'aac', '64', '256'),
            ('opus', 'libopus', 'opus', '48', '256'),
        ]:
            with self.subTest(format=extension):
                seed = directory / f'seed.{extension}'
                self.ffmpeg('-i', str(self.noise), '-c:a', encoder, '-b:a', '128k', str(seed))
                sizes = []
                for bitrate in (low, high):
                    source = directory / f'target-{bitrate}.{extension}'
                    shutil.copy2(seed, source)
                    original = digest(source)
                    deleted, info = QuayAudioPP(format=extension, bitrate=bitrate).run(
                        {'filepath': str(source), 'ext': extension})
                    self.assertEqual(info['filepath'], str(source))
                    self.assertNotEqual(digest(source), original)
                    self.assertEqual(digest(deleted[0]), original)
                    audio = self.probe(source)['streams'][0]
                    self.assertEqual(audio['codec_name'], codec)
                    self.assertEqual(audio['sample_rate'], '48000')
                    self.assertEqual(info['audio_channels'], 2)
                    sizes.append(source.stat().st_size)
                    os.unlink(deleted[0])
                    self.assertTrue(source.exists())
                # AAC/Opus are targets, not an exact file-size guarantee. Broadband
                # noise gives a robust separation without asserting false CBR.
                self.assertGreater(sizes[1], sizes[0] * 2)

    def test_original_combined_audio_packets_are_identical(self):
        directory = self.fresh_directory()
        for encoder, codec, extension in [
            ('aac', 'aac', 'm4a'), ('alac', 'alac', 'm4a'), ('libmp3lame', 'mp3', 'mp3'),
            ('libopus', 'opus', 'opus'), ('libvorbis', 'vorbis', 'ogg'), ('flac', 'flac', 'flac'),
            ('pcm_s16le', 'pcm_s16le', 'wav'), ('ac3', 'ac3', 'mka'),
        ]:
            with self.subTest(codec=codec):
                source = directory / f'combined-{codec}.mkv'
                self.ffmpeg('-i', str(self.noise), '-f', 'lavfi', '-i', 'color=s=32x32:r=2:d=4',
                            '-map', '1:v', '-map', '0:a', '-c:v', 'mpeg4', '-c:a', encoder,
                            '-shortest', str(source))
                original = digest(source)
                packets = self.packet_hashes(source)
                deleted, info = QuayAudioPP().run({'filepath': str(source), 'ext': 'mkv'})
                self.assertEqual(deleted, [str(source)])
                self.assertEqual(digest(source), original)
                self.assertEqual(info['ext'], extension)
                self.assertEqual(info['acodec'], codec)
                self.assertFalse(QuayAudioPP._has_video(self.probe(info['filepath'])))
                output_packets = self.packet_hashes(info['filepath'])
                if codec.startswith('pcm_'):
                    # WAV's packet boundaries may differ; compare the untouched
                    # encoded audio payload after demuxing both containers.
                    payloads = []
                    for path in (source, info['filepath']):
                        result = subprocess.run([FFMPEG, '-v', 'error', '-i', str(path), '-map', '0:a:0',
                                                 '-c:a', 'copy', '-f', 's16le', '-'],
                                                capture_output=True, check=True, timeout=30)
                        payloads.append(hashlib.sha256(result.stdout).hexdigest())
                    self.assertEqual(payloads[0], payloads[1])
                else:
                    self.assertEqual(output_packets, packets)

    def test_low_rate_mono_normalized_and_high_bitrate_valid(self):
        directory = self.fresh_directory()
        for extension, bitrate in [('mp3', '320'), ('m4a', '256'), ('opus', '256')]:
            source = directory / f'low-rate-{extension}.wav'
            self.ffmpeg('-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=8000:duration=1', str(source))
            _, info = QuayAudioPP(format=extension, bitrate=bitrate).run({'filepath': str(source), 'ext': 'wav'})
            stream = self.probe(info['filepath'])['streams'][0]
            self.assertEqual(stream['sample_rate'], '48000')
            self.assertEqual(stream['channels'], 1)

    def test_mp3_surround_downmix_and_other_formats_preserve_channels(self):
        directory = self.fresh_directory()
        source = directory / 'surround.wav'
        self.ffmpeg('-f', 'lavfi', '-i', 'anullsrc=r=48000:cl=5.1', '-t', '1', str(source))
        for extension, expected_channels in [('mp3', 2), ('m4a', 6), ('opus', 6)]:
            _, info = QuayAudioPP(format=extension).run({'filepath': str(source), 'ext': 'wav'})
            self.assertEqual(self.probe(info['filepath'])['streams'][0]['channels'], expected_channels)

    def test_no_audio_and_corrupt_input_preserve_original(self):
        directory = self.fresh_directory()
        no_audio = directory / 'silent.mp4'
        self.ffmpeg('-f', 'lavfi', '-i', 'color=s=32x32:r=2:d=1', '-c:v', 'mpeg4', str(no_audio))
        corrupt = directory / 'corrupt.mp4'
        corrupt.write_bytes(b'invalid media')
        for source in (no_audio, corrupt):
            original = digest(source)
            with self.assertRaisesRegex(PostProcessingError, 'no audio|inspect|processing failed'):
                QuayAudioPP(format='mp3').run({'filepath': str(source), 'ext': 'mp4'})
            self.assertEqual(digest(source), original)
        self.assertEqual(set(directory.iterdir()), {no_audio, corrupt})

    def test_cli_plugin_discovery_and_after_move_success_failure(self):
        directory = self.fresh_directory()
        plugin_root = directory / 'plugins'
        plugin_directory = plugin_root / 'quay/yt_dlp_plugins/postprocessor'
        plugin_directory.mkdir(parents=True)
        shutil.copy2(PLUGIN, plugin_directory / 'quay_audio.py')
        media_directory = directory / 'media'
        media_directory.mkdir()
        source = media_directory / 'input.mp3'
        self.ffmpeg('-i', str(self.noise), '-c:a', 'libmp3lame', '-b:a', '128k', str(source))
        no_audio = media_directory / 'silent.mp4'
        self.ffmpeg('-f', 'lavfi', '-i', 'color=s=32x32:r=2:d=1', '-c:v', 'mpeg4', str(no_audio))
        class QuietHandler(http.server.SimpleHTTPRequestHandler):
            def log_message(self, *args):
                pass
        handler = functools.partial(QuietHandler, directory=str(media_directory))
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        for format in ('original', 'mp3', 'm4a', 'opus', 'failure'):
            with self.subTest(format=format):
                target = directory / format
                target.mkdir()
                completed = target / 'completed.txt'
                request = 'QuayAudio:when=post_process;format=' + (format if format != 'failure' else 'mp3')
                if format not in ('original', 'failure'):
                    request += ';bitrate=192'
                filename = 'silent.mp4' if format == 'failure' else 'input.mp3'
                command = [sys.executable, str(BUNDLE), '--ignore-config', '--no-plugin-dirs',
                           '--plugin-dirs', str(plugin_root), '--no-cache-dir', '--no-progress',
                           '--no-playlist', '--no-simulate', '--use-postprocessor', request,
                           '--print-to-file', 'after_move:%(filepath)s', str(completed),
                           '-o', str(target / 'download.%(ext)s'),
                           f'http://127.0.0.1:{server.server_port}/{filename}']
                result = subprocess.run(command, capture_output=True, text=True, timeout=60,
                                        env={k: v for k, v in os.environ.items() if k != 'YTDLP_NO_PLUGINS'})
                if format == 'failure':
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn('no audio', result.stderr)
                    self.assertFalse(completed.exists(), result.stdout + result.stderr)
                    self.assertEqual(digest(target / 'download.mp4'), digest(no_audio))
                else:
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertIn('[QuayAudio]', result.stdout)
                    final = Path(completed.read_text().strip())
                    self.assertTrue(final.is_file())
                    self.assertEqual(final.suffix, '.mp3' if format == 'original' else '.' + format)
                    self.assertEqual(len(list(target.glob('download.*'))), 1)
                    self.assertFalse(list(target.glob('.quay-*')))
                    if format == 'original':
                        self.assertEqual(digest(final), digest(source))
                    else:
                        self.assertNotEqual(digest(final), digest(source))


if __name__ == '__main__':
    unittest.main()
